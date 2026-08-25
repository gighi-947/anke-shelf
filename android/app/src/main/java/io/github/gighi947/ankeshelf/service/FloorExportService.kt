package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.GululuUpdate
import io.github.gighi947.ankeshelf.data.NativeBook
import io.github.gighi947.ankeshelf.data.NativeBookWriter
import io.github.gighi947.ankeshelf.data.NativeFloor
import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 楼层导出：可导出的楼层描述。 */
data class FloorExportFloor(
    val num: Int,
    val label: String,
    val chapterIndex: Int,
    val selector: String,
    val floorId: Long = 0L,
    val timeText: String = "",
)

/** 楼层导出结果：kind + 楼层列表（空列表表示无法定位/无快照）。 */
data class FloorExportList(
    val kind: String,
    val floors: List<FloorExportFloor>,
)

/**
 * 楼层导出映射器：把书架记录解析为可导出的楼层。
 * NGA 走原生书 floors.json；骨碌碌走 snapshot.json 的 floor_index/chapter_index。
 */
object FloorExportMapper {

    fun list(record: BookRecord, session: BookSession): FloorExportList {
        if (record.nga_tid > 0) return listNga(record)
        return listGululu(record, session)
    }

    private fun listNga(record: BookRecord): FloorExportList {
        val root = File(record.path)
        if (!root.isDirectory) return FloorExportList("nga", emptyList())
        val book: NativeBook = NativeBook(root).open()
        val floors: List<NativeFloor> = NativeBookWriter.loadFloors(root)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val list = floors.mapNotNull { f ->
            val chapter = book.chapterIndexForLou(f.lou)
            if (chapter < 0) null
            else FloorExportFloor(
                num = f.lou,
                label = (if (f.lou == 0) "主楼" else "${f.lou}楼") +
                    (if (f.timestamp > 0) " · ${fmt.format(Instant.ofEpochSecond(f.timestamp).atZone(ZoneId.systemDefault()))}" else ""),
                chapterIndex = chapter,
                selector = "#pid${f.pid}",
                floorId = f.pid,
                timeText = if (f.timestamp > 0) fmt.format(Instant.ofEpochSecond(f.timestamp).atZone(ZoneId.systemDefault())) else "",
            )
        }.sortedByDescending { it.num }
        return FloorExportList("nga", list)
    }

    private fun listGululu(record: BookRecord, session: BookSession): FloorExportList {
        val sourceId = session.gululuSourceId
        if (sourceId <= 0) return FloorExportList("gululu", emptyList())
        val snapshotFile = File(File(record.path).parentFile, "snapshot.json")
        val baseline = when (val b = GululuUpdate.loadBaseline(snapshotFile, sourceId)) {
            is io.github.gighi947.ankeshelf.data.GululuBaseline.Ok -> b
            else -> return FloorExportList("gululu", emptyList())
        }
        val chapters = session.chapters
        if (chapters.isEmpty()) return FloorExportList("gululu", emptyList())
        val floorByNum = mutableMapOf<Int, Int>()
        val floorNames = mutableMapOf<Int, String>()
        val floorTimes = mutableMapOf<Int, String>()
        for (item in baseline.floorIndex) {
            val num = item["floorNum"]?.jsonPrimitive?.intOrNull ?: continue
            val id = item["floorId"]?.jsonPrimitive?.intOrNull ?: continue
            floorByNum[num] = id
            floorNames[num] = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        for (floor in baseline.floors) {
            val id = floor["id"]?.jsonPrimitive?.intOrNull ?: continue
            val num = floorByNum.entries.firstOrNull { it.value == id }?.key ?: continue
            val time = floor["updateTime"]?.jsonPrimitive?.contentOrNull
                ?: floor["createTime"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (time.isNotBlank()) floorTimes[num] = time
        }
        val chapterStarts = mutableListOf<Int>()
        for (item in baseline.chapterIndex) {
            item["floor"]?.jsonPrimitive?.intOrNull?.let { chapterStarts.add(it) }
        }
        chapterStarts.sort()
        val list = mutableListOf<FloorExportFloor>()
        for (item in baseline.floorIndex) {
            val num = item["floorNum"]?.jsonPrimitive?.intOrNull ?: continue
            val id = floorByNum[num] ?: continue
            val chapter = chapterStarts.indexOfLast { it <= num }.coerceAtLeast(0)
                .coerceAtMost(chapters.size - 1)
            val timeText = floorTimes[num].orEmpty()
            list.add(
                FloorExportFloor(
                    num = num,
                    label = ("第${num}楼 ${floorNames[num].orEmpty()}".trim() +
                        (if (timeText.isNotBlank()) " · $timeText" else "")),
                    chapterIndex = chapter,
                    selector = "#floor-$id",
                    floorId = id.toLong(),
                    timeText = timeText,
                ),
            )
        }
        return FloorExportList("gululu", list.sortedByDescending { it.num })
    }
}

/** 把单个楼层渲染为阅读器同款 HTML（供离屏 WebView 截图）。 */
object FloorExportHtml {

    /** 深色背景判定：仅用于 NGA 楼层内联兜底色（与阅读器 dark 主题一致）。 */
    fun isDarkColor(hex: String): Boolean = try {
        val c = android.graphics.Color.parseColor(hex)
        android.graphics.Color.luminance(c) < 0.35f
    } catch (e: Exception) {
        hex.lowercase() in setOf("#000000", "#1e1e1e", "#222222", "#2e3440", "#002b36")
    }

    fun nga(
        record: BookRecord,
        floor: FloorExportFloor,
        theme: ReaderThemeColors,
        settings: SettingsData,
    ): String {
        val root = File(record.path)
        val book: NativeBook = NativeBook(root).open()
        val dark = isDarkColor(theme.background)
        val nativeFloor = NativeBookWriter.loadFloors(root).firstOrNull { it.lou == floor.num }
            ?: error("第 ${floor.num} 楼不存在")
        val floorHtml = NativeBookWriter.renderFloorHtml(nativeFloor, dark = dark, imgSrc = { it })
        val parts = io.github.gighi947.ankeshelf.ui.reader.ReaderHtmlParts(
            body = floorHtml.replace("loading=\"lazy\"", "loading=\"eager\""),
            headStyles = "",
        )
        return io.github.gighi947.ankeshelf.ui.reader.buildReaderHtml(parts, theme, settings, record.id)
            .replace("<script src=\"file:///android_asset/reader/reader-lite.js\"></script>", "")
            .replace("<div class=\"chapter-nav-row\">", "<div class=\"chapter-nav-row\" style=\"display:none;\">")
    }

    fun gululu(
        session: BookSession,
        floor: FloorExportFloor,
        theme: ReaderThemeColors,
        settings: SettingsData,
    ): String {
        val chapter = session.chapterText(floor.chapterIndex)
        if (chapter is io.github.gighi947.ankeshelf.data.ChapterReadResult.Success) {
            val doc = Jsoup.parse(chapter.text)
            val section = doc.selectFirst(floor.selector)
                ?: error("第 ${floor.num} 楼不存在")
            val body = section.outerHtml().replace("loading=\"lazy\"", "loading=\"eager\"")
            val linkedCss = doc.select("link").filter { it.attr("rel").lowercase() == "stylesheet" }
                .mapNotNull { link -> session.readAsset(floor.chapterIndex, link.attr("href"))?.decodeToString() }
                .joinToString("\n")
            val parts = io.github.gighi947.ankeshelf.ui.reader.ReaderHtmlParts(
                body = body,
                headStyles = linkedCss,
            )
            return io.github.gighi947.ankeshelf.ui.reader.buildReaderHtml(parts, theme, settings, session.id)
                .replace("<script src=\"file:///android_asset/reader/reader-lite.js\"></script>", "")
                .replace("<div class=\"chapter-nav-row\">", "<div class=\"chapter-nav-row\" style=\"display:none;\">")
        } else {
            error("第 ${floor.num} 楼所在章节读取失败")
        }
    }
}
