package io.github.gighi947.ankeshelf.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生书增量追加的双端 golden 对照：消费
 * `contracts/fixtures/native-book/append-cases.json`，Windows 侧同一份夹具由
 * `tests/test_contracts.py::NativeAppendFixtureTest` 消费。
 *
 * 夹具的 expected 是【正确行为】的权威定义（见 contracts/README 规则 4），
 * 不是某端当前实现的输出快照；任何一端与期望不符即为契约漂移。
 *
 * 重点覆盖"楼层正文含字面量 `</body>`"这类边界输入：历史上两端都用
 * `replace` 定位插入点，会把新楼层插到每一处匹配，造成内容重复并破坏
 * text_offset 坐标。两端曾同时存在该缺陷而互不察觉——因为契约此前只覆盖
 * 数据格式与 text_offset 计算，未覆盖"章节追加算法"。本夹具即为此而设。
 */
class NativeAppendFixtureTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val repoRoot: File = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "contracts").isDirectory) dir = dir.parentFile
        dir ?: error("contracts/ 未找到")
    }

    private fun countOccurrences(text: String, token: String): Int =
        if (token.isEmpty()) 0 else text.windowed(token.length).count { it == token }

    @Test
    fun `append matches shared fixture`() {
        val fixture = File(repoRoot, "contracts/fixtures/native-book/append-cases.json")
        assertTrue("夹具缺失：$fixture", fixture.isFile)
        val root = json.parseToJsonElement(fixture.readText(Charsets.UTF_8)).jsonObject
        val defaults = root["defaults"]?.jsonObject
        val cases = root["cases"]!!.jsonArray
        assertTrue("夹具用例数应覆盖核心边界", cases.size >= 5)

        for (element in cases) {
            val c = element.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val perChapter = c["perChapter"]?.jsonPrimitive?.int
                ?: c["per_chapter"]!!.jsonPrimitive.int
            val imageMode = c["image_mode"]?.jsonPrimitive?.content
                ?: defaults?.get("image_mode")?.jsonPrimitive?.content ?: "online"
            val theme = c["theme"]?.jsonPrimitive?.content
                ?: defaults?.get("theme")?.jsonPrimitive?.content ?: "light"

            val tmp = kotlin.io.path.createTempDirectory("native-append-fixture-").toFile()
            try {
                val initial = c["initial"]!!.jsonArray.map { floorFromJson(it.jsonObject) }
                NativeBookWriter.writeContainer(
                    ngaLibraryRoot = tmp,
                    folderName = "case",
                    tieziTitle = "t",
                    author = "a",
                    tid = 1,
                    authorId = 0,
                    createdTime = "2026-01-01T00:00:00+08:00",
                    updatedTime = "2026-01-01T00:00:00+08:00",
                    validFloors = initial,
                    perChapter = perChapter,
                    imageMode = imageMode,
                    theme = theme,
                    bookId = "bookid123",
                )

                val append = c["append"]!!.jsonArray.map { floorFromJson(it.jsonObject) }
                val got = NativeBookWriter.appendContainer(
                    tmp, "case", append, perChapter, imageMode, theme,
                )

                val exp = c["expected"]!!.jsonObject
                assertEquals("追加数不符：$name", exp["appended_count"]!!.jsonPrimitive.int, got)

                val dir = NativeBookWriter.nativeDirFor(tmp, "case")
                val meta = NativeBookWriter.loadMeta(dir)
                assertEquals(
                    "章节数不符：$name",
                    exp["chapter_count"]!!.jsonPrimitive.int,
                    meta.chapters.size,
                )
                assertEquals(
                    "末章 floor_count 不符：$name",
                    exp["last_chapter_floor_count"]!!.jsonPrimitive.int,
                    meta.chapters.last().floor_count,
                )
                assertEquals(
                    "末章 last_lou 不符：$name",
                    exp["last_chapter_last_lou"]!!.jsonPrimitive.int,
                    meta.chapters.last().last_lou,
                )

                val text = File(dir, exp["chapter_file"]!!.jsonPrimitive.content)
                    .readText(Charsets.UTF_8)
                val probe = exp["probe"]!!.jsonPrimitive.content
                assertEquals(
                    "探针 $probe 出现次数不符：$name（重复插入或未插入都会体现在这里）",
                    exp["probe_count"]!!.jsonPrimitive.int,
                    countOccurrences(text, probe),
                )
                assertEquals(
                    "</body> 总数不符：$name",
                    exp["body_marker_count"]!!.jsonPrimitive.int,
                    countOccurrences(text, "</body>"),
                )
                if (exp["probe_before_last_body_marker"]!!.jsonPrimitive.content.toBoolean()) {
                    assertTrue(
                        "追加内容必须落在真实闭合标签之前：$name",
                        text.indexOf(probe) < text.lastIndexOf("</body>"),
                    )
                }
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    private fun floorFromJson(o: kotlinx.serialization.json.JsonObject): NativeFloor = NativeFloor(
        pid = o["pid"]!!.jsonPrimitive.content.toLong(),
        lou = o["lou"]!!.jsonPrimitive.int,
        username = "u",
        user_id = 1,
        raw_content = o["raw_content"]!!.jsonPrimitive.content,
    )
}
