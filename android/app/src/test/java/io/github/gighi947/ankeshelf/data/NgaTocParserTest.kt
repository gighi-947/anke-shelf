package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * NGA 目录楼解析 + `toc_mode=split` 分章的**双端 golden 对照**：
 * 夹具与期望值在 `contracts/fixtures/nga-toc/`，Windows 侧同一份夹具由
 * `tests/test_contracts.py::NgaTocFixtureTest` 消费。任一端解析结果不符即为契约漂移。
 */
class NgaTocParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val repoRoot: File = run {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, ".git").exists() && d.parentFile != null) d = d.parentFile
        d
    }

    private val fixtureDir = File(repoRoot, "contracts/fixtures/nga-toc")
    private val json = Json { ignoreUnknownKeys = true }

    private fun expected(): JsonObject =
        json.parseToJsonElement(File(fixtureDir, "expected-toc.json").readText()).jsonObject

    @Test
    fun `目录楼解析结果与双端夹具一致`() {
        val content = File(fixtureDir, "toc-floor.html").readText()
        val parsed = NgaTocParser.parseToc(content)
        val want = expected()["chapters"]!!.jsonArray

        assertEquals("章节数一致（无条目的折叠块必须丢弃）", want.size, parsed.size)
        want.forEachIndexed { i, element ->
            val chapter = element.jsonObject
            assertEquals(chapter["title"]!!.jsonPrimitive.content, parsed[i].title)
            val entries = chapter["entries"]!!.jsonArray
            assertEquals("第 $i 章条目数", entries.size, parsed[i].entries.size)
            entries.forEachIndexed { j, entryElement ->
                val pair = (entryElement as JsonArray)
                assertEquals(pair[0].jsonPrimitive.content, parsed[i].entries[j].title)
                assertEquals(pair[1].jsonPrimitive.longOrNull, parsed[i].entries[j].pid)
            }
        }
    }

    @Test
    fun `split 模式按目录楼切章并与夹具期望一致`() {
        val toc = NgaTocParser.parseToc(File(fixtureDir, "toc-floor.html").readText())
        val grouping = expected()["split_grouping"]!!.jsonObject
        val floors = grouping["floors"]!!.jsonArray.map { element ->
            val obj = element.jsonObject
            NativeFloor(
                pid = obj["pid"]!!.jsonPrimitive.content.toLong(),
                lou = obj["lou"]!!.jsonPrimitive.content.toInt(),
                timestamp = 0,
                username = "u",
                user_id = 1,
                like_num = 0,
                raw_content = "正文",
            )
        }

        val root = tmp.newFolder("nga_library")
        NativeBookWriter.writeContainer(
            ngaLibraryRoot = root,
            folderName = "12345",
            tieziTitle = "契约样本",
            author = "作者",
            tid = 12345,
            authorId = 1,
            createdTime = nowIso(),
            updatedTime = nowIso(),
            validFloors = floors,
            perChapter = 20,
            imageMode = "online",
            theme = "light",
            bookId = "fixture-book",
            tocChapters = toc,
            tocMode = "split",
        )

        val meta = NativeBookWriter.loadMeta(NativeBookWriter.nativeDirFor(root, "12345"))
        assertEquals("split", meta.toc_mode)
        assertEquals("meta.toc 必须落盘目录（供热更新与导出复用）", toc.size, meta.toc.size)

        val want = grouping["expected"]!!.jsonArray
        assertEquals(want.size, meta.chapters.size)
        want.forEachIndexed { i, element ->
            val obj = element.jsonObject
            val got = meta.chapters[i]
            assertEquals("第 $i 章标题", obj["title"]!!.jsonPrimitive.content, got.title)
            assertEquals("第 $i 章首楼", obj["first_lou"]!!.jsonPrimitive.content.toInt(), got.first_lou)
            assertEquals("第 $i 章末楼", obj["last_lou"]!!.jsonPrimitive.content.toInt(), got.last_lou)
            assertEquals("第 $i 章楼数", obj["floor_count"]!!.jsonPrimitive.content.toInt(), got.floor_count)
        }
        assertTrue("主楼独占首章", meta.chapters.first().main)
    }

    @Test
    fun `index 模式仍按每章楼数分章`() {
        val floors = (0..5).map { i ->
            NativeFloor(
                pid = i.toLong(),
                lou = i,
                timestamp = 0,
                username = "u",
                user_id = 1,
                like_num = 0,
                raw_content = "正文$i",
            )
        }
        val root = tmp.newFolder("nga_library_index")
        NativeBookWriter.writeContainer(
            ngaLibraryRoot = root,
            folderName = "999",
            tieziTitle = "按楼分章",
            author = "作者",
            tid = 999,
            authorId = 0,
            createdTime = nowIso(),
            updatedTime = nowIso(),
            validFloors = floors,
            perChapter = 2,
            imageMode = "online",
            theme = "light",
            bookId = "fixture-index",
            tocChapters = null,
            tocMode = "index",
        )
        val meta = NativeBookWriter.loadMeta(NativeBookWriter.nativeDirFor(root, "999"))
        // 主楼独占 1 章 + 其余 5 楼按每章 2 楼 → 3 章
        assertEquals(listOf("序章 · 主楼", "第 1~2 楼", "第 3~4 楼", "第 5 楼"), meta.chapters.map { it.title })
        assertTrue(meta.toc.isEmpty())
    }

    @Test
    fun `目录内容异常时回退空表`() {
        assertTrue(NgaTocParser.parseToc("").isEmpty())
        assertTrue(NgaTocParser.parseToc("<div>普通楼层，没有目录折叠块</div>").isEmpty())
    }
}
