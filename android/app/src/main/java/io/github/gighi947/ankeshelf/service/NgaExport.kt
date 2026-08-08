package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.EpubExporter
import io.github.gighi947.ankeshelf.data.NativeBookWriter
import io.github.gighi947.ankeshelf.data.NativeFloor
import io.github.gighi947.ankeshelf.data.NativeMeta
import io.github.gighi947.ankeshelf.data.NgaMarkdown
import java.io.File

/** 导出文件名清洗（对齐桌面 export_service._safe_filename）。 */
fun safeExportName(title: String): String {
    val cleaned = title.replace(Regex("""[<>:"/\\|?*\u0000-\u001f]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '.')
    return cleaned.take(80).ifBlank { "未命名安科" }
}

/** NGA 书导出：EPUB（原生书章节打包）/ Markdown（楼层转换）。 */
object NgaExport {

    fun epubBytes(nativeDir: File, meta: NativeMeta): ByteArray =
        EpubExporter.build(nativeDir, meta)

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
