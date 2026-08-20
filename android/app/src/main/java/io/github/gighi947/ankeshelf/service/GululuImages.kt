package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.GululuAst
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 骨碌碌图片模式（online=在线直链、embedded=内嵌 EPUB、none=不含图）。 */
enum class GululuImageMode(val wire: String) {
    ONLINE("online"),
    EMBEDDED("embedded"),
    NONE("none"),
    ;

    companion object {
        /** 未知取值返回 null，由调用方给出用户可读错误（对齐桌面 normalize_image_mode 的拒绝语义）。 */
        fun fromWire(value: String?): GululuImageMode? {
            val mode = value?.trim()?.lowercase().orEmpty().ifEmpty { ONLINE.wire }
            return entries.firstOrNull { it.wire == mode }
        }

        const val INVALID_MESSAGE = "骨碌碌图片模式必须是 online、embedded 或 none"
    }
}

/** 已内嵌图片资源（file_name 为 EPUB 内相对路径，与桌面同命名规则）。 */
data class EmbeddedImage(
    val sourceUrl: String,
    val fileName: String,
    val mediaType: String,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EmbeddedImage && sourceUrl == other.sourceUrl && fileName == other.fileName
    override fun hashCode(): Int = sourceUrl.hashCode() * 31 + fileName.hashCode()
}

/** 单张图片失败原因（不静默丢弃：失败数写进任务状态并在正文转占位）。 */
data class ImageFailure(val sourceUrl: String, val error: String)

data class ImageBatch(
    val resources: List<EmbeddedImage> = emptyList(),
    val failures: List<ImageFailure> = emptyList(),
)

/** 图片内嵌被所属导入任务取消。 */
class GululuImageCancelled(message: String) : Exception(message)

/**
 * 骨碌碌正文图片三态与内嵌资源准备（Kotlin 版 `app/gululu_images.py`）。
 *
 * 红线（与桌面一致）：只接受 HTTPS；按**文件签名**判定类型（不信 HTTP 头）；
 * 单图上限 25 MB；6 路并发；失败逐张记录而不是整批回退在线。
 */
object GululuImages {

    private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    private const val MAX_WORKERS = 6

    /** 注入点：测试与离线场景用假 fetcher，返回 (字节, content-type)。 */
    fun interface ImageFetcher {
        fun fetch(url: String): Pair<ByteArray, String>
    }

    /** 收集正文中唯一的 HTTPS 图片 URL，保持出现顺序。 */
    fun collectImageUrls(floors: List<JsonObject>): List<String> {
        val urls = LinkedHashSet<String>()

        fun visit(value: JsonElement?) {
            when (value) {
                is JsonArray -> value.forEach { visit(it) }
                is JsonObject -> {
                    if (value["type"].asContent() == "image") {
                        val src = (value["attrs"] as? JsonObject)?.get("src").asContent().trim()
                        if (src.startsWith("https://")) urls.add(src)
                    }
                    visit(value["content"])
                }
                else -> Unit
            }
        }

        floors.forEach { visit(it["paragraphContents"]) }
        return urls.toList()
    }

    /** EPUB 内资源名：`images/<sha256(url) 前 16 位十六进制>.<ext>`（双端同规则）。 */
    fun embeddedFileName(url: String, extension: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "images/$digest.$extension"
    }

    /**
     * 按签名识别受支持位图 → (mediaType, extension)；不支持返回 null，
     * 由调用方转成该图的显式失败（对齐桌面 `_detect_image` 抛 ValueError）。
     */
    fun detectImage(content: ByteArray): Pair<String, String>? {
        fun startsWith(vararg bytes: Int): Boolean =
            content.size >= bytes.size && bytes.withIndex().all { (i, b) -> content[i] == b.toByte() }

        if (startsWith(0xFF, 0xD8, 0xFF)) return "image/jpeg" to "jpg"
        if (startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png" to "png"
        if (content.size >= 6) {
            val head = String(content, 0, 6, Charsets.ISO_8859_1)
            if (head == "GIF87a" || head == "GIF89a") return "image/gif" to "gif"
        }
        if (content.size >= 12) {
            val riff = String(content, 0, 4, Charsets.ISO_8859_1)
            val webp = String(content, 8, 4, Charsets.ISO_8859_1)
            if (riff == "RIFF" && webp == "WEBP") return "image/webp" to "webp"
            val ftyp = String(content, 4, 4, Charsets.ISO_8859_1)
            val brand = String(content, 8, 4, Charsets.ISO_8859_1)
            if (ftyp == "ftyp" && (brand == "avif" || brand == "avis")) return "image/avif" to "avif"
        }
        return null
    }

    const val UNSUPPORTED_MESSAGE = "响应不是受支持的 JPEG、PNG、GIF、WebP 或 AVIF 图片"
    const val TOO_LARGE_MESSAGE = "图片超过 25 MB 限制"

    /**
     * 并发下载并准备内嵌资源。逐张失败只记 [ImageFailure]；取消抛
     * [GululuImageCancelled]（由导入任务转为"已取消"，不落半成品 EPUB）。
     */
    fun prepareEmbeddedImages(
        urls: List<String>,
        fetcher: ImageFetcher? = null,
        progress: ((current: Int, total: Int, ok: Int, failed: Int) -> Unit)? = null,
        cancel: (() -> Boolean)? = null,
    ): ImageBatch {
        val sources = urls.distinct()
        if (sources.isEmpty()) return ImageBatch()

        var http: OkHttpClient? = null
        val fetch: ImageFetcher = fetcher ?: run {
            http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            ImageFetcher { url -> downloadImage(http!!, url) }
        }

        val pool = Executors.newFixedThreadPool(MAX_WORKERS) { r ->
            Thread(r, "gululu-image").apply { isDaemon = true }
        }
        val resources = mutableListOf<EmbeddedImage>()
        val failures = mutableListOf<ImageFailure>()
        try {
            val futures: List<Future<Any>> = sources.map { source ->
                pool.submit(
                    Callable {
                        try {
                            val (content, _) = fetch.fetch(source)
                            val detected = detectImage(content)
                                ?: return@Callable ImageFailure(source, UNSUPPORTED_MESSAGE)
                            EmbeddedImage(
                                sourceUrl = source,
                                fileName = embeddedFileName(source, detected.second),
                                mediaType = detected.first,
                                content = content,
                            ) as Any
                        } catch (e: Exception) {
                            ImageFailure(source, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                        }
                    },
                )
            }
            futures.forEachIndexed { index, future ->
                if (cancel?.invoke() == true) {
                    futures.forEach { it.cancel(true) }
                    throw GululuImageCancelled("骨碌碌图片内嵌已取消")
                }
                when (val item = future.get()) {
                    is EmbeddedImage -> resources.add(item)
                    is ImageFailure -> failures.add(item)
                }
                progress?.invoke(index + 1, sources.size, resources.size, failures.size)
            }
        } finally {
            pool.shutdownNow()
            http?.dispatcher?.executorService?.shutdown()
        }
        return ImageBatch(resources, failures)
    }

    private fun downloadImage(client: OkHttpClient, url: String): Pair<ByteArray, String> {
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://www.gululu.world/")
            .build()
        client.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            if (response.request.url.scheme != "https") {
                throw IllegalStateException("图片重定向到非 HTTPS 地址")
            }
            val declared = response.header("Content-Length")?.toLongOrNull()
            if (declared != null && declared > MAX_IMAGE_BYTES) {
                throw IllegalStateException(TOO_LARGE_MESSAGE)
            }
            val body = response.body
            val stream = body.byteStream()
            val buffer = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_IMAGE_BYTES) throw IllegalStateException(TOO_LARGE_MESSAGE)
                out.write(buffer, 0, read)
            }
            return out.toByteArray() to (response.header("Content-Type") ?: "")
        }
    }

    private fun JsonElement?.asContent(): String {
        val primitive = this as? JsonPrimitive ?: return ""
        return if (primitive.isString) primitive.content else primitive.content.takeUnless { it == "null" }.orEmpty()
    }

    /** 图片三态 → AST 渲染用的 resolver（与桌面 build_epub 内的三分支一致）。 */
    fun resolverFor(mode: GululuImageMode, embedded: Map<String, String>): (String) -> String = when (mode) {
        GululuImageMode.ONLINE -> { url -> url }
        GululuImageMode.NONE -> { _ -> "" }
        GululuImageMode.EMBEDDED -> { url -> embedded[url] ?: "" }
    }

    /** 内嵌资源 → AST resolver 映射（EPUB 章节在 chapters/ 下，故加 `../` 前缀）。 */
    fun embeddedSources(batch: ImageBatch): Map<String, String> =
        batch.resources.associate { it.sourceUrl to "../${it.fileName}" }

    /** 与 [GululuAst] 一致的转义（导出摘要/日志复用，避免各处手写）。 */
    fun escape(text: String): String = GululuAst.escape(text)
}
