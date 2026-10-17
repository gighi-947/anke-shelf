package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.GululuUpdate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 进书更新统一入口（骨碌碌侧）：sourceId 解析 + 基线 append-only 前缀比对的
 * 三态结果。基线用 GululuUpdate.writeBaseline 写真文件，fetchIndex 注入假实现。
 */
class BookUpdateCheckerGululuTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val libDir: File get() = File(tmp.root, "gululu_library")

    private fun index(vararg ids: Int): List<JsonObject> = ids.mapIndexed { i, id ->
        buildJsonObject {
            put("floorId", id)
            put("floorNum", i + 1)
        }
    }

    private fun floors(vararg ids: Int): List<JsonObject> = ids.mapIndexed { i, id ->
        json.parseToJsonElement(
            """{"id":$id,"floorNum":${i + 1},"paragraphContents":[
                 {"type":"paragraph","content":[{"type":"text","text":"楼 $id"}]}]}""",
        ).jsonObject
    }

    private fun detail(sourceId: Int) = buildJsonObject {
        put("bookId", sourceId)
        put("name", "检查样本")
    }

    private fun writeBaseline(sourceId: Int, vararg ids: Int) {
        GululuUpdate.writeBaseline(
            GululuUpdate.baselineFile(libDir, sourceId),
            sourceId,
            detail(sourceId),
            index(*ids),
            emptyList(),
            floors(*ids),
            "online",
        )
    }

    private fun record(sourceId: Int): BookRecord = BookRecord(
        id = "g-$sourceId",
        path = File(libDir, "$sourceId/post.epub").absolutePath,
        title = "样本",
    )

    private fun remote(sourceId: Int, ids: IntArray): GululuIndex =
        GululuIndex(detail(sourceId), index(*ids), emptyList())

    @Test
    fun `新增 N 楼返回精确 Available`() {
        writeBaseline(48856, 101, 102)
        val result = BookUpdateChecker.checkGululu(libDir, record(48856)) {
            remote(48856, intArrayOf(101, 102, 103, 104, 106))
        }
        assertEquals(BookUpdateCheck.Available(3, approximate = false), result)
    }

    @Test
    fun `无新增返回 None`() {
        writeBaseline(48856, 101, 102)
        val result = BookUpdateChecker.checkGululu(libDir, record(48856)) {
            remote(48856, intArrayOf(101, 102))
        }
        assertEquals(BookUpdateCheck.None, result)
    }

    @Test
    fun `基线缺失返回 Unavailable`() {
        // 缺失走手动更新的一次性迁移，不做进书提示
        val result = BookUpdateChecker.checkGululu(libDir, record(48856)) {
            remote(48856, intArrayOf(101, 102))
        }
        assertEquals(BookUpdateCheck.Unavailable, result)
    }

    @Test
    fun `fetchIndex 抛异常返回 Unavailable`() {
        writeBaseline(48856, 101)
        val result = BookUpdateChecker.checkGululu(libDir, record(48856)) {
            throw GululuApiException("网络失败")
        }
        assertEquals(BookUpdateCheck.Unavailable, result)
    }

    @Test
    fun `远端删除旧楼（前缀冲突）返回 Unavailable`() {
        writeBaseline(48856, 101, 102)
        val result = BookUpdateChecker.checkGululu(libDir, record(48856)) {
            remote(48856, intArrayOf(101, 999))
        }
        assertEquals(BookUpdateCheck.Unavailable, result)
    }

    @Test
    fun `sourceId 解析`() {
        assertEquals(48856, BookUpdateChecker.gululuSourceIdFor(libDir, record(48856)))

        val nga = record(48856).copy(nga_tid = 123)
        assertEquals("nga_tid>0 → 0", 0, BookUpdateChecker.gululuSourceIdFor(libDir, nga))

        val foreign = BookRecord(
            id = "x",
            path = File(tmp.root, "books/other.epub").absolutePath,
            title = "普通 EPUB",
        )
        assertEquals("路径不在骨碌碌库 → 0", 0, BookUpdateChecker.gululuSourceIdFor(libDir, foreign))
    }
}
