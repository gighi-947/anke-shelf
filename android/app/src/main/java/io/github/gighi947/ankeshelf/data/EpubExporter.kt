package io.github.gighi947.ankeshelf.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 从原生书目录生成 EPUB3（自写 ZIP/OPF，不依赖第三方库）。
 * 章节 XHTML 复用原生书 chapters/，与桌面 rebuild_epub_for_native 语义一致
 * （桌面用同一套 render_content_html 产物）。
 */
object EpubExporter {

    fun build(nativeDir: File, meta: NativeMeta): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // mimetype 必须为第一个条目且 STORED（EPUB 规范）
            zip.putNextEntry(storedEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(CONTAINER_XML.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("EPUB/content.opf"))
            zip.write(opfXml(meta).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for (ch in meta.chapters) {
                val file = File(nativeDir, ch.file)
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry("EPUB/${ch.file}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun storedEntry(name: String): ZipEntry =
        ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = 20
            crc = CRC32().apply { update("application/epub+zip".toByteArray(Charsets.US_ASCII)) }.value
        }

    private fun opfXml(meta: NativeMeta): String {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        meta.chapters.forEachIndexed { i, ch ->
            val id = "ch$i"
            manifest.append(
                "<item id=\"$id\" href=\"${xmlEscape(ch.file)}\" media-type=\"application/xhtml+xml\"/>",
            )
            spine.append("<itemref idref=\"$id\"/>")
        }
        val bookId = "urn:uuid:${UUID.randomUUID()}"
        return """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">$bookId</dc:identifier>
    <dc:title>${xmlEscape(meta.title)}</dc:title>
    <dc:creator>${xmlEscape(meta.author)}</dc:creator>
    <dc:language>zh</dc:language>
  </metadata>
  <manifest>
    $manifest
  </manifest>
  <spine>
    $spine
  </spine>
</package>
"""
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="EPUB/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""
}
