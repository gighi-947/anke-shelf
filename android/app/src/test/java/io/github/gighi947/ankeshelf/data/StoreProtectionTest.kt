package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 权威存储损坏/读取失败语义回归（2026-08-22 审查清理）：
 * - Corrupt：原文件隔离为 .corrupt-*，空状态是新状态，允许继续写，但必须
 *   报告 StoreLoadIssue（用户可见），不再静默回退默认；
 * - IoError：原文件仍在原位（如瞬时读取失败），存储暂停写入，防止把好文件
 *   覆盖成空数据；flush 显式失败（StoreWriteProtectedException）；
 * - 恢复成功（Ok/Missing）后写保护解除。
 */
class StoreProtectionTest {

    private fun tempDir(): File = Files.createTempDirectory("storeguard").toFile()

    @Test
    fun `损坏的 shelf json 报告 Corrupt 并隔离原文件后允许重建`() {
        val dir = tempDir()
        val f = File(dir, "shelf.json")
        f.writeText("{not json", Charsets.UTF_8)
        val shelf = Shelf(f, File(dir, "covers"))

        val issues = shelf.load()
        assertEquals(1, issues.size)
        assertEquals(StoreLoadIssue.Kind.Corrupt, issues[0].kind)
        assertEquals("shelf.json", issues[0].fileName)
        assertTrue("横幅文案需含文件名", issues[0].userMessage().contains("shelf.json"))

        // 原字节保留在 .corrupt 备份中，供人工恢复
        val quarantine = dir.listFiles { _, n -> n.startsWith("shelf.json.corrupt-") }.orEmpty()
        assertEquals(1, quarantine.size)
        assertEquals("{not json", quarantine[0].readText(Charsets.UTF_8))

        // 已隔离 → 空状态即新状态，允许继续写
        shelf.upsert(BookRecord(id = "b1", path = "/x", title = "t"))
        shelf.save()
        assertTrue(f.isFile)
    }

    @Test
    fun `IoError 的 shelf json 暂停写入不覆盖原文件`() {
        val dir = tempDir()
        val f = File(dir, "shelf.json")
        assertTrue(f.mkdirs()) // 目录冒充不可读文件 → readText 抛 IOException

        val shelf = Shelf(f, File(dir, "covers"))
        val issues = shelf.load()
        assertEquals(1, issues.size)
        assertEquals(StoreLoadIssue.Kind.IoError, issues[0].kind)

        shelf.upsert(BookRecord(id = "b1", path = "/x", title = "t"))
        shelf.save() // 不得抛异常，也不得写入
        assertTrue("原路径不得被替换", f.isDirectory)
        assertEquals(
            "不得残留 .tmp",
            emptyList<File>(),
            dir.listFiles { _, n -> n.endsWith(".tmp") }.orEmpty().toList(),
        )
    }

    @Test
    fun `IoError 的 progress json 写入被拦且 flush 显式失败`() {
        val dir = tempDir()
        val f = File(dir, "progress.json")
        assertTrue(f.mkdirs())

        val store = ProgressStore(f)
        val issues = store.load()
        assertEquals(StoreLoadIssue.Kind.IoError, issues.single().kind)

        store.set("b1", 2, 100)
        val r = store.flush()
        assertTrue("flush 必须显式失败", r.isFailure)
        assertTrue(
            "失败原因必须是写保护",
            r.exceptionOrNull() is StoreWriteProtectedException,
        )
        assertEquals(
            emptyList<File>(),
            dir.listFiles { _, n -> n.endsWith(".tmp") }.orEmpty().toList(),
        )
    }

    @Test
    fun `恢复正常后写保护解除`() {
        val dir = tempDir()
        val f = File(dir, "progress.json")
        assertTrue(f.mkdirs())
        val store = ProgressStore(f)
        store.load()

        assertTrue(f.delete()) // 移走障碍 → Missing 语义 → 解除写保护
        assertTrue(store.load().isEmpty())
        store.set("b1", 1, 10)
        assertTrue(store.flush().isSuccess)
    }

    @Test
    fun `annotations stats gululu 解锁共用同一语义`() {
        val dir = tempDir()

        val af = File(dir, "annotations.json")
        af.writeText("[", Charsets.UTF_8)
        val ann = AnnotationStore(af)
        assertEquals(StoreLoadIssue.Kind.Corrupt, ann.load().single().kind)
        // 损坏已隔离：原路径不再存在（被移走为 .corrupt-*）
        assertEquals(emptyList<File>(), listOfFiles(dir, "annotations.json"))

        val sf = File(dir, "statistics.json")
        assertTrue(sf.mkdirs())
        val stats = StatsStore(sf)
        assertEquals(StoreLoadIssue.Kind.IoError, stats.load().single().kind)
        stats.recordReading("b", 30) // 内部 save() 必须被拦
        assertEquals(
            emptyList<File>(),
            dir.listFiles { _, n -> n.endsWith(".tmp") }.orEmpty().toList(),
        )

        val uf = File(dir, "gululu_unlocks.json")
        val cf = File(dir, "gululu_clues.json")
        cf.writeText("oops", Charsets.UTF_8)
        val gs = GululuUnlockStore(uf, cf)
        val gi = gs.load()
        assertEquals(1, gi.size)
        assertEquals("gululu_clues.json", gi[0].fileName)
        assertEquals(StoreLoadIssue.Kind.Corrupt, gi[0].kind)
    }

    private fun listOfFiles(dir: File, exactName: String): List<File> =
        dir.listFiles { _, n -> n == exactName }.orEmpty().toList()
}
