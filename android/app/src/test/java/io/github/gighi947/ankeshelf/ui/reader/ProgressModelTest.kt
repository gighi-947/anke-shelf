package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.ProgressEntry
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/** P1 进度事件回放：contracts/fixtures/progress 下的 JSON 夹具驱动纯决策层（虚拟时钟）。 */
class ProgressModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `progress fixtures replay through pure decision layer`() {
        val fixtures = progressRoot().listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }
        org.junit.Assert.assertTrue("progress fixtures 至少 7 个", fixtures.size >= 7)
        for (file in fixtures) {
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val id = root.getValue("id").jsonPrimitive.content
            val initial = root.getValue("initial").jsonObject
            val initialChapter = initial["chapter"]?.jsonPrimitive?.int ?: 0
            val initialOffset = initial["offset"]?.jsonPrimitive?.int ?: 0
            val storedJson = initial["stored"]
            val stored = if (storedJson == null || storedJson is JsonNull) null else {
                val o = storedJson.jsonObject
                ProgressEntry(
                    chapter_index = o["chapter_index"]?.jsonPrimitive?.int ?: 0,
                    text_offset = o["text_offset"]?.jsonPrimitive?.int ?: 0,
                    page_index = o["page_index"]?.jsonPrimitive?.int ?: -1,
                    page_total = o["page_total"]?.jsonPrimitive?.int ?: -1,
                    scroll_ratio = o["scroll_ratio"]?.jsonPrimitive?.doubleOrNull ?: -1.0,
                )
            }

            var state = ProgressModel.initialState(stored, initialChapter, initialOffset)
            val persists = mutableListOf<ProgressPersist>()
            for (ev in root.getValue("events").jsonArray) {
                val o = ev.jsonObject
                val at = o.getValue("at").jsonPrimitive.long
                val event = when (o.getValue("type").jsonPrimitive.content) {
                    "scroll" -> ProgressEvent.Scroll(
                        o.getValue("chapter").jsonPrimitive.int,
                        o.getValue("offset").jsonPrimitive.int,
                        o["page"]?.jsonPrimitive?.int ?: -1,
                        o["total"]?.jsonPrimitive?.int ?: -1,
                        o["ratio"]?.jsonPrimitive?.doubleOrNull ?: -1.0,
                        at,
                    )
                    "page-turn" -> ProgressEvent.PageTurn(
                        o.getValue("chapter").jsonPrimitive.int,
                        o.getValue("offset").jsonPrimitive.int,
                        o["page"]?.jsonPrimitive?.int ?: -1,
                        o["total"]?.jsonPrimitive?.int ?: -1,
                        o["ratio"]?.jsonPrimitive?.doubleOrNull ?: -1.0,
                        at,
                    )
                    "switch" -> ProgressEvent.ChapterSwitch(
                        o.getValue("from").jsonPrimitive.int,
                        o.getValue("to").jsonPrimitive.int,
                        at,
                    )
                    "flush" -> ProgressEvent.Flush(at)
                    "close" -> ProgressEvent.Close(at)
                    "debounce" -> ProgressEvent.DebounceDue(o.getValue("chapter").jsonPrimitive.int, at)
                    else -> error("$id: 未知事件类型")
                }
                val decision = ProgressModel.apply(state, event)
                state = decision.state
                persists += decision.persists
            }

            val expected = root.getValue("expect").jsonObject
            val expectedPersists = expected.getValue("persists").jsonArray.map { e ->
                val p = e.jsonObject
                ProgressPersist(
                    chapter = p.getValue("chapter").jsonPrimitive.int,
                    offset = p.getValue("offset").jsonPrimitive.int,
                    page = p.getValue("page").jsonPrimitive.int,
                    total = p.getValue("total").jsonPrimitive.int,
                    ratio = p.getValue("ratio").jsonPrimitive.double,
                )
            }
            assertEquals("$id: persists", expectedPersists, persists)

            val restore = expected["restore"]
            if (restore != null) {
                val reopens = expected["reopens"]?.jsonPrimitive?.int ?: 1
                val entries = restore.jsonArray.map { e ->
                    val o = e.jsonObject
                    o.getValue("chapter").jsonPrimitive.int to o.getValue("offset").jsonPrimitive.int
                }
                for (i in 0 until reopens) {
                    val fresh = ProgressModel.initialState(stored, initialChapter, initialOffset)
                    for ((chapter, offset) in entries) {
                        assertEquals("$id: reopen #$i restore", offset, fresh.restoreOffsetFor(chapter))
                    }
                    val flushDecision = ProgressModel.apply(fresh, ProgressEvent.Flush(0))
                    assertEquals("$id: reopen #$i flush 无重复写入", emptyList<ProgressPersist>(), flushDecision.persists)
                }
            }
        }
    }

    private fun progressRoot(): File {
        System.getProperty("contracts.dir")?.let { return File(it).resolve("fixtures/progress") }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts")
            if (candidate.isDirectory) return candidate.resolve("fixtures/progress")
            dir = dir.parentFile
        }
        error("contracts/ 未找到；可用 -Dcontracts.dir=... 指定")
    }
}
