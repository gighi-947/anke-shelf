package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 骨碌碌 EPUB 生成：分组与单楼 HTML 走**双端 golden**
 * （`contracts/fixtures/gululu/ast-cases.json` 的 epub_group_cases / epub_floor_cases，
 * Windows 侧见 `tests/test_contracts.py::GululuEpubFixtureTest`）；
 * 整包结构用自家 [EpubBook] 解析器做 round-trip，确保产物真的可被阅读器打开。
 */
class GululuEpubTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val repoRoot: File = run {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, ".git").exists() && d.parentFile != null) d = d.parentFile
        d
    }
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): JsonObject =
        json.parseToJsonElement(
            File(repoRoot, "contracts/fixtures/gululu/ast-cases.json").readText(),
        ).jsonObject

    @Test
    fun `章节分组与双端夹具一致`() {
        val cases = fixture()["epub_group_cases"]!!.jsonArray
        assertTrue(cases.size >= 3)
        for (element in cases) {
            val case = element.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val groups = GululuEpub.chapterGroups(
                floorIndex = case["floor_index"]!!.jsonArray.map { it.jsonObject },
                chapterIndex = case["chapter_index"]!!.jsonArray.map { it.jsonObject },
                floors = case["floors"]!!.jsonArray.map { it.jsonObject },
            )
            val expected = case["expected_groups"]!!.jsonArray
            assertEquals("用例 $id 的章节数", expected.size, groups.size)
            expected.forEachIndexed { i, want ->
                val obj = want.jsonObject
                assertEquals("用例 $id 第 $i 章标题", obj["title"]!!.jsonPrimitive.content, groups[i].title)
                val floorNums = obj["floor_nums"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
                assertEquals(
                    "用例 $id 第 $i 章楼号",
                    floorNums,
                    groups[i].floors.map { it.first["floorNum"]!!.jsonPrimitive.content.toInt() },
                )
            }
        }
    }

    @Test
    fun `单楼 HTML 与双端夹具一致`() {
        val cases = fixture()["epub_floor_cases"]!!.jsonArray
        assertTrue(cases.size >= 2)
        for (element in cases) {
            val case = element.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val floor = case["floor"]!!.jsonObject
            val immersive = GululuImmersive.prepareImmersiveFloor(floor["paragraphContents"])
            val html = GululuEpub.floorHtml(
                indexItem = case["index_item"]!!.jsonObject,
                floor = floor,
                comments = case["comments"]!!.jsonArray.map { it.jsonObject },
                immersive = immersive,
                imageResolver = { url -> url },
                jumpFloorResolver = { "" },
                sourceBookId = case["source_book_id"]!!.jsonPrimitive.content.toInt(),
            )
            assertEquals("用例 $id", case["expected_html"]!!.jsonPrimitive.content, html)
        }
    }

    @Test
    fun `缺少楼层正文时显式失败`() {
        val error = runCatching {
            GululuEpub.chapterGroups(
                floorIndex = listOf(buildJsonObject { put("floorId", 1); put("floorNum", 1) }),
                chapterIndex = emptyList(),
                floors = emptyList(),
            )
        }.exceptionOrNull()
        assertTrue("缺正文必须报错而不是产出缺楼的书", error is GululuEpubFormatException)
    }

    // ---------- 整包结构 ----------

    private fun sampleEpubBytes(): ByteArray = GululuEpub.build(
        detail = buildJsonObject {
            put("bookId", 48856)
            put("name", "契约样本 & 测试")
            put("oneLineText", "一句话简介")
            put("author", buildJsonObject { put("nickName", "作者甲") })
        },
        floorIndex = listOf(
            buildJsonObject { put("floorId", 700); put("floorNum", 1); put("name", "开场") },
            buildJsonObject { put("floorId", 701); put("floorNum", 2) },
        ),
        chapterIndex = listOf(buildJsonObject { put("floor", 1); put("title", "第一章") }),
        floors = listOf(
            json.parseToJsonElement(
                """{"id":700,"floorNum":1,"paragraphContents":[
                     {"type":"paragraph","content":[{"type":"text","text":"正文一"}]},
                     {"type":"image","attrs":{"src":"https://img.example/a.png"}}]}""",
            ).jsonObject,
            json.parseToJsonElement(
                """{"id":701,"floorNum":2,"paragraphContents":[
                     {"type":"paragraph","content":[{"type":"text","text":"正文二"}]}]}""",
            ).jsonObject,
        ),
        imageResolver = { url -> url },
        images = listOf(
            GululuEpubImage("images/abc0123456789def.png", "image/png", byteArrayOf(0x89.toByte(), 0x50)),
        ),
        cover = GululuEpubImage("images/cover-src.jpg", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte())),
    )

    @Test
    fun `产物结构与桌面同构且 mimetype 未压缩`() {
        val file = File(tmp.root, "post.epub").apply { writeBytes(sampleEpubBytes()) }
        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals("mimetype 必须是第一个条目", "mimetype", names.first())
            assertEquals(
                "mimetype 必须 STORED（EPUB 规范）",
                ZipEntry.STORED,
                zip.getEntry("mimetype").method,
            )
            for (required in listOf(
                "META-INF/container.xml",
                "EPUB/content.opf",
                "EPUB/style/main.css",
                "EPUB/nav.xhtml",
                "EPUB/toc.ncx",
                "EPUB/chapters/chapter_0001.xhtml",
                "EPUB/images/abc0123456789def.png",
                "EPUB/cover.jpg",
            )) {
                assertNotNull("缺少条目 $required", zip.getEntry(required))
            }
            val opf = zip.getInputStream(zip.getEntry("EPUB/content.opf")).readBytes().toString(Charsets.UTF_8)
            assertTrue("identifier 必须是 gululu-<id>（来源识别依赖它）", opf.contains("gululu-48856"))
            assertTrue(opf.contains("<dc:source>https://www.gululu.world/book/48856</dc:source>"))
            assertTrue("书名需转义", opf.contains("契约样本 &amp; 测试"))
            assertTrue(opf.contains("properties=\"cover-image\""))

            val chapter = zip.getInputStream(zip.getEntry("EPUB/chapters/chapter_0001.xhtml"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue("楼层锚点是热更新基线迁移的依据", chapter.contains("id=\"floor-700\""))
            assertTrue(chapter.contains("id=\"floor-701\""))
            assertTrue("首章需带来源行", chapter.contains("class=\"book-meta\""))
            assertTrue(chapter.contains("<h1 class=\"chapter-title\">第一章</h1>"))
        }
    }

    @Test
    fun `产物可被自家解析器打开`() {
        val file = File(tmp.root, "roundtrip.epub").apply { writeBytes(sampleEpubBytes()) }
        val book = EpubBook(file).open()
        try {
            assertEquals("契约样本 & 测试", book.title)
            assertEquals("作者甲", book.author)
            assertEquals("gululu-48856", book.identifier)
            assertEquals(48856, GululuSource.parseGululuIdentifier(book.identifier))
            assertEquals(1, book.chapters.size)
            assertEquals("第一章", book.chapterTitle(0))
            val text = book.chapterText(0)
            assertTrue(text is ChapterReadResult.Success)
            assertTrue((text as ChapterReadResult.Success).text.contains("正文一"))
        } finally {
            book.close()
        }
    }
}
