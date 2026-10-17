package io.github.gighi947.ankeshelf.service

import android.content.Context
import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.EpubExporter
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.NativeBookWriter
import io.github.gighi947.ankeshelf.data.NativeMeta
import io.github.gighi947.ankeshelf.data.NgaMarkdown
import io.github.gighi947.ankeshelf.ui.reader.ReaderEgress
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

/** 导出文件名清洗（对齐桌面 export_service._safe_filename）。 */
fun safeExportName(title: String): String {
    val cleaned = title.replace(Regex("""[<>:"/\\|?*\u0000-\u001f]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '.')
    return cleaned.take(80).ifBlank { "未命名安科" }
}

/** NGA 书导出：EPUB（原生书章节打包）/ Markdown（楼层转换）。 */
object NgaExport {

    fun epubBytes(nativeDir: File, meta: NativeMeta, imagesDir: File? = null): ByteArray =
        EpubExporter.build(nativeDir, meta, imagesDir)

    /** embedded 图片目录（与 NgaDownloader.downloadImages 落盘位置一致）。 */
    fun imagesDirFor(context: Context, bookId: String): File =
        File(File(context.filesDir, AppPaths.APP_DIR_NAME), "images/$bookId")

    /**
     * 导出 EPUB 并在导出前把章节引用的在线图下载到 imagesDir（EPUB 阅读器普遍
     * 不加载远程图）。已内嵌的书直接打包；无 NGA 凭据时降级为远程链接导出。
     * 单图失败保留远程链接，不阻断导出。
     */
    fun epubBytesEmbedOnline(
        nativeDir: File,
        meta: NativeMeta,
        imagesDir: File,
        http: OkHttpClient,
        config: NgaConfig,
    ): ByteArray {
        if (meta.image_mode != "embedded") {
            downloadOnlineImages(nativeDir, meta, imagesDir, http, config)
        }
        return epubBytes(nativeDir, meta, imagesDir)
    }

    private val RE_IMG_SRC = Regex("""<img[^>]+src="(https?://[^"]+)"""")

    private fun downloadOnlineImages(
        nativeDir: File,
        meta: NativeMeta,
        imagesDir: File,
        http: OkHttpClient,
        config: NgaConfig,
    ) {
        val cfg = config.load()
        if (!cfg.configured) return
        val urls = LinkedHashSet<String>()
        for (ch in meta.chapters) {
            val f = File(nativeDir, ch.file)
            if (!f.isFile) continue
            RE_IMG_SRC.findAll(f.readText(Charsets.UTF_8)).forEach { m ->
                // 存量书可能烤入已下线的历史表情宿主，归一到现役宿主再下载。
                urls.add(ReaderEgress.normalizeLegacyNgaImageUrl(m.groupValues[1]))
            }
        }
        if (urls.isEmpty()) return
        imagesDir.mkdirs()
        NgaImageDownloads.drain(
            urls = urls.toList(),
            isCached = { url ->
                val f = File(imagesDir, NativeBookWriter.imageFileName(url))
                f.isFile && f.length() > 0
            },
            load = { url ->
                runCatching {
                    val req = Request.Builder().url(url).ngaHeaders(cfg).build()
                    http.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body.bytes() else null
                    }
                }.getOrNull()
            },
            persist = { url, bytes ->
                File(imagesDir, NativeBookWriter.imageFileName(url)).writeBytes(bytes)
            },
            onProgress = { _, _, _, _ -> },
            isCancelled = { false },
        )
    }

    fun markdownText(nativeDir: File, meta: NativeMeta): String {
        val floors = NativeBookWriter.loadFloors(nativeDir)
        val sb = StringBuilder()
        sb.append("# ").append(meta.title).append("\n\n")
        sb.append("作者：").append(meta.author).append(" · NGA tid ").append(meta.tid).append("\n\n")
        for (ch in meta.chapters) {
            val range = floors.filter { f ->
                f.lou != -1 && f.lou >= ch.first_lou && f.lou <= ch.last_lou
            }
            if (range.isEmpty()) continue
            sb.append(NgaMarkdown.chapterToMarkdown(ch.title, range)).append("\n")
        }
        return sb.toString().trim() + "\n"
    }

    fun metaOf(nativeDir: File): NativeMeta? = try {
        NativeBookWriter.loadMeta(nativeDir)
    } catch (_: Exception) {
        null
    }
}
