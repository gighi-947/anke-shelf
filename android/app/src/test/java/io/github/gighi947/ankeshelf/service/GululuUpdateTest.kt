package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.GululuBaseline
import io.github.gighi947.ankeshelf.data.GululuUpdate
import io.github.gighi947.ankeshelf.data.GululuUpdateConflict
import io.github.gighi947.ankeshelf.data.ProgressStore
import io.github.gighi947.ankeshelf.data.Shelf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 骨碌碌热更新：**append-only 是不可协商的不变量**。
 * 旧楼被删/重排/替换必须显式失败；无新增且模式未变不得重建 EPUB；
 * 旧书（无基线）走"读现有 EPUB 楼层锚点"的一次性迁移校验。
 */
class GululuUpdateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

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
        put("name", "更新样本")
    }

    private fun snapshot(sourceId: Int, ids: IntArray) = GululuSnapshot(
        detail = detail(sourceId),
        floorIndex = index(*ids),
        chapterIndex = emptyList(),
        floors = floors(*ids),
    )

    // ---------- 纯逻辑：基线 / 前缀 / 合并 ----------

    @Test
    fun `基线往返与字段校验`() {
        val file = File(tmp.root, "snapshot.json")
        assertEquals(GululuBaseline.Missing, GululuUpdate.loadBaseline(file, 1))

        GululuUpdate.writeBaseline(file, 1, detail(1), index(10, 11), emptyList(), floors(10, 11), "online")
        val loaded = GululuUpdate.loadBaseline(file, 1)
        assertTrue(loaded is GululuBaseline.Ok)
        assertEquals(2, (loaded as GululuBaseline.Ok).floors.size)
        assertEquals("online", loaded.imageMode)

        // 来源不一致 / JSON 损坏 / 索引与正文不同序 → 全部显式 Invalid
        assertTrue(GululuUpdate.loadBaseline(file, 2) is GululuBaseline.Invalid)
        File(tmp.root, "broken.json").writeText("{oops")
        assertTrue(GululuUpdate.loadBaseline(File(tmp.root, "broken.json"), 1) is GululuBaseline.Invalid)
        val mismatch = File(tmp.root, "mismatch.json")
        GululuUpdate.writeBaseline(mismatch, 1, detail(1), index(10, 11), emptyList(), floors(10, 11), "none")
        mismatch.writeText(mismatch.readText().replace("\"id\": 11", "\"id\": 99"))
        assertTrue(GululuUpdate.loadBaseline(mismatch, 1) is GululuBaseline.Invalid)
    }

    @Test
    fun `远端必须是旧楼层的严格前缀`() {
        val old = index(1, 2, 3)
        assertEquals(listOf(4, 5), GululuUpdate.planIncrementalUpdate(old, index(1, 2, 3, 4, 5)).newFloorIds)
        assertEquals(emptyList<Int>(), GululuUpdate.planIncrementalUpdate(old, index(1, 2, 3)).newFloorIds)

        for (bad in listOf(index(1, 3, 2, 4), index(1, 2), index(9, 1, 2, 3))) {
            val error = runCatching { GululuUpdate.planIncrementalUpdate(old, bad) }.exceptionOrNull()
            assertTrue("删除/重排/替换必须冲突：$bad", error is GululuUpdateConflict)
        }
        // 重复 ID 也是索引损坏
        assertTrue(
            runCatching {
                GululuUpdate.planIncrementalUpdate(old, index(1, 2, 3, 4, 4))
            }.exceptionOrNull() is GululuUpdateConflict,
        )
    }

    @Test
    fun `合并按远端顺序_缺正文显式失败`() {
        val plan = GululuUpdate.planIncrementalUpdate(index(1, 2), index(1, 2, 3))
        val merged = GululuUpdate.mergeIncrementalFloors(floors(1, 2), index(1, 2, 3), floors(3), plan)
        assertEquals(
            listOf(1, 2, 3),
            merged.map { it["id"].toString().toInt() },
        )

        val wrongNew = runCatching {
            GululuUpdate.mergeIncrementalFloors(floors(1, 2), index(1, 2, 3), floors(99), plan)
        }.exceptionOrNull()
        assertTrue(wrongNew is GululuUpdateConflict)

        val missingOld = runCatching {
            GululuUpdate.mergeIncrementalFloors(floors(1), index(1, 2, 3), floors(3), plan)
        }.exceptionOrNull()
        assertTrue(missingOld is GululuUpdateConflict)
    }

    // ---------- 编排：更新决策 ----------

    private class Harness(root: File) {
        val appPaths = AppPaths(root).also { it.ensure() }
        val shelf = Shelf(appPaths.shelfFile, appPaths.coversDir).also { it.load() }
        val repository = BookRepository(
            appPaths,
            shelf,
            ProgressStore(appPaths.progressFile).also { it.load() },
        )
        val folder = File(appPaths.gululuLibraryDir, "48856")

        fun importer(snapshot: GululuSnapshot) =
            GululuImporter(appPaths, repository, { _, _, _ -> snapshot })

        fun updater(
            importSnapshot: GululuSnapshot,
            index: GululuIndex,
            newFloors: List<JsonObject> = emptyList(),
            fullSnapshot: GululuSnapshot = importSnapshot,
        ): GululuUpdater = GululuUpdater(
            appPaths = appPaths,
            repository = repository,
            importer = importer(importSnapshot),
            fetchIndex = { _, _, _ -> index },
            fetchFloors = { _, _, _, _ -> newFloors },
            fetchSnapshot = { _, _, _ -> fullSnapshot },
        )
    }

    @Test
    fun `导入即建立基线_无新增时不重建 EPUB`() {
        val h = Harness(tmp.root)
        val base = snapshot(48856, intArrayOf(101, 102))
        assertTrue(h.importer(base).import(48856, GululuImageMode.ONLINE) is GululuImportResult.Ok)
        val baselineFile = GululuUpdate.baselineFile(h.appPaths.gululuLibraryDir, 48856)
        assertTrue("导入必须落基线，下次才能走增量", baselineFile.isFile)

        val target = File(h.folder, "post.epub")
        val before = target.lastModified()
        Thread.sleep(20)
        val result = h.updater(base, GululuIndex(base.detail, base.floorIndex, emptyList()))
            .update(48856, GululuImageMode.ONLINE)
        assertTrue(result is GululuUpdateResult.UpToDate)
        assertEquals("无新增且模式未变不得重建", before, target.lastModified())
    }

    @Test
    fun `有新增楼层时增量重建并更新基线`() {
        val h = Harness(tmp.root)
        val base = snapshot(48856, intArrayOf(101, 102))
        h.importer(base).import(48856, GululuImageMode.ONLINE)

        val remote = GululuIndex(detail(48856), index(101, 102, 103), emptyList())
        val result = h.updater(base, remote, newFloors = floors(103))
            .update(48856, GululuImageMode.ONLINE)

        assertTrue("应更新：$result", result is GululuUpdateResult.Updated)
        assertEquals(1, (result as GululuUpdateResult.Updated).newCount)
        // 新基线含 3 楼；EPUB 里出现新楼锚点
        val baseline = GululuUpdate.loadBaseline(
            GululuUpdate.baselineFile(h.appPaths.gululuLibraryDir, 48856),
            48856,
        )
        assertEquals(3, (baseline as GululuBaseline.Ok).floors.size)
        assertTrue(GululuUpdate.readEpubFloorIds(File(h.folder, "post.epub")).contains(103))
    }

    @Test
    fun `远端删除旧楼时拒绝更新并保留原书`() {
        val h = Harness(tmp.root)
        val base = snapshot(48856, intArrayOf(101, 102, 103))
        h.importer(base).import(48856, GululuImageMode.ONLINE)
        val originalIds = GululuUpdate.readEpubFloorIds(File(h.folder, "post.epub"))

        val remote = GululuIndex(detail(48856), index(101, 103), emptyList())
        val result = h.updater(base, remote).update(48856, GululuImageMode.ONLINE)

        assertTrue(result is GululuUpdateResult.Err)
        assertTrue((result as GululuUpdateResult.Err).message.contains("完整重新导入"))
        assertEquals("原书必须原样保留", originalIds, GululuUpdate.readEpubFloorIds(File(h.folder, "post.epub")))
    }

    @Test
    fun `旧书无基线时按 EPUB 锚点迁移`() {
        val h = Harness(tmp.root)
        val base = snapshot(48856, intArrayOf(101, 102))
        h.importer(base).import(48856, GululuImageMode.ONLINE)
        // 模拟"旧书"：删掉基线
        GululuUpdate.baselineFile(h.appPaths.gululuLibraryDir, 48856).delete()

        val full = snapshot(48856, intArrayOf(101, 102, 103))
        val result = h.updater(base, GululuIndex(detail(48856), full.floorIndex, emptyList()), fullSnapshot = full)
            .update(48856, GululuImageMode.ONLINE)

        assertTrue("应更新并建立基线：$result", result is GululuUpdateResult.Updated)
        assertTrue((result as GululuUpdateResult.Updated).baselineInitialized)
        assertEquals(1, result.newCount)
        assertTrue(GululuUpdate.baselineFile(h.appPaths.gululuLibraryDir, 48856).isFile)
    }

    @Test
    fun `旧书与远端历史不一致时拒绝迁移`() {
        val h = Harness(tmp.root)
        h.importer(snapshot(48856, intArrayOf(101, 102))).import(48856, GululuImageMode.ONLINE)
        GululuUpdate.baselineFile(h.appPaths.gululuLibraryDir, 48856).delete()

        val diverged = snapshot(48856, intArrayOf(999, 101, 102))
        val result = h.updater(
            diverged,
            GululuIndex(detail(48856), diverged.floorIndex, emptyList()),
            fullSnapshot = diverged,
        ).update(48856, GululuImageMode.ONLINE)

        assertTrue(result is GululuUpdateResult.Err)
        assertTrue((result as GululuUpdateResult.Err).message.contains("楼层历史不一致"))
    }

    @Test
    fun `基线损坏时要求完整重导`() {
        val h = Harness(tmp.root)
        val base = snapshot(48856, intArrayOf(101))
        h.importer(base).import(48856, GululuImageMode.ONLINE)
        GululuUpdate.baselineFile(h.appPaths.gululuLibraryDir, 48856).writeText("{corrupt")

        val result = h.updater(base, GululuIndex(detail(48856), base.floorIndex, emptyList()))
            .update(48856, GululuImageMode.ONLINE)
        assertTrue(result is GululuUpdateResult.Err)
        assertTrue((result as GululuUpdateResult.Err).message.contains("请完整重新导入"))
    }

    @Test
    fun `没有本地 EPUB 时不允许更新`() {
        val h = Harness(tmp.root)
        val base = snapshot(48856, intArrayOf(101))
        val result = h.updater(base, GululuIndex(detail(48856), base.floorIndex, emptyList()))
            .update(48856, GululuImageMode.ONLINE)
        assertTrue(result is GululuUpdateResult.Err)
        assertTrue((result as GululuUpdateResult.Err).message.contains("请先完成导入"))
        assertFalse(File(h.folder, "post.epub").exists())
    }

    @Test
    fun `仅图片模式变化也会重建`() {
        val h = Harness(tmp.root)
        val base = snapshot(48856, intArrayOf(101))
        h.importer(base).import(48856, GululuImageMode.ONLINE)
        val result = h.updater(base, GululuIndex(detail(48856), base.floorIndex, emptyList()))
            .update(48856, GululuImageMode.NONE)
        assertTrue("图片模式变化应重建：$result", result is GululuUpdateResult.Updated)
        assertEquals(0, (result as GululuUpdateResult.Updated).newCount)
    }
}
