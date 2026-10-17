package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.GululuEpubImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 骨碌碌在线 EPUB 导出内嵌：下载章节引用的在线图进包（EPUB/images/），
 * 改写章节引用并登记 OPF manifest。EPUB 阅读器普遍不加载远程图。
 * 单图失败保留远程链接，不阻断导出。
 */
object GululuExportEmbed {

    private val RE_IMG_SRC = Regex("""<img[^>]+src="(https?://[^"]+)"""")

    fun embedOnlineImages(epub: File, http: OkHttpClient): ByteArray =
        embed(epub) { url ->
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body.bytes() else null
                }
            }.getOrNull()
        }

    /** 测试缝：fetch 返回 null = 该图下载失败（保留远程链接）。 */
    internal fun embed(epub: File, fetch: (String) -> ByteArray?): ByteArray {
        val original = epub.readBytes()
        val names = mutableListOf<String>()
        val contents = HashMap<String, ByteArray>()
        ZipFile(epub).use { zip ->
            for (e in zip.entries()) {
                names.add(e.name)
                contents[e.name] = zip.getInputStream(e).readBytes()
            }
        }

        val urls = linkedSetOf<String>()
        for (name in names) {
            if (name.startsWith("EPUB/chapters/") && name.endsWith(".xhtml")) {
                RE_IMG_SRC.findAll(contents.getValue(name).toString(Charsets.UTF_8))
                    .forEach { urls.add(it.groupValues[1]) }
            }
        }
        if (urls.isEmpty()) return original

        // 下载（串行足量：导出是低频动作；失败跳过）
        val downloaded = LinkedHashMap<String, GululuEpubImage>() // url -> image
        for (url in urls) {
            val bytes = fetch(url) ?: continue
            val ext = url.substringBefore('?').substringAfterLast('.', "jpg").lowercase()
                .takeIf { it.length <= 5 } ?: "jpg"
            downloaded[url] = GululuEpubImage(GululuImages.embeddedFileName(url, ext), mimeOf(ext), bytes)
        }
        if (downloaded.isEmpty()) return original

        // 章节引用改写：章节在 EPUB/chapters/，图片在 EPUB/images/，相对 ../images/
        for ((url, image) in downloaded) {
            for (name in names) {
                if (!name.startsWith("EPUB/chapters/") || !name.endsWith(".xhtml")) continue
                val text = contents.getValue(name).toString(Charsets.UTF_8)
                if (url in text) {
                    contents[name] = text
                        .replace("src=\"$url\"", "src=\"../${image.fileName}\"")
                        .toByteArray(Charsets.UTF_8)
                }
            }
        }

        // OPF manifest 登记
        val opfName = "EPUB/content.opf"
        contents[opfName]?.let { opfBytes ->
            val opf = opfBytes.toString(Charsets.UTF_8)
            if ("</manifest>" in opf) {
                val items = downloaded.values.joinToString("") { image ->
                    val base = image.fileName.substringAfterLast('/').substringBeforeLast('.')
                    "<item id=\"img-$base\" href=\"${image.fileName}\" media-type=\"${image.mediaType}\"/>"
                }
                contents[opfName] = opf.replace("</manifest>", items + "</manifest>")
                    .toByteArray(Charsets.UTF_8)
            }
        }

        // 重打包：mimetype 必须第一个条目且 STORED（EPUB 规范）
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                val data = contents.getValue(name)
                if (name == "mimetype") {
                    zip.putNextEntry(
                        ZipEntry("mimetype").apply {
                            method = ZipEntry.STORED
                            size = data.size.toLong()
                            crc = CRC32().apply { update(data) }.value
                        },
                    )
                } else {
                    zip.putNextEntry(ZipEntry(name))
                }
                zip.write(data)
                zip.closeEntry()
            }
            for (image in downloaded.values) {
                zip.putNextEntry(ZipEntry("EPUB/${image.fileName}"))
                zip.write(image.content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }
}
