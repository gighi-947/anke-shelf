package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 更新检查状态存储：默认开关、关闭清徽标、持久化、清零删条目、
 * 旧 muted 文件迁移、revision 递增、会话内节流、损坏容错读。
 */
class UpdateCheckStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): UpdateCheckStore =
        UpdateCheckStore(File(tmp.root, "update_checks.json"))

    @Test
    fun `默认自动检查开启`() {
        assertTrue(store().isAutoCheckEnabled("book-1"))
    }

    @Test
    fun `关闭自动检查同时清除 available 徽标`() {
        val s = store()
        s.setAvailable("book-1", 5)
        assertEquals(5, s.availableFloors("book-1"))
        s.setAutoCheck("book-1", false)
        assertFalse(s.isAutoCheckEnabled("book-1"))
        assertEquals("关闭后徽标应清除", 0, s.availableFloors("book-1"))
    }

    @Test
    fun `持久化：同路径新实例可读开关与徽标`() {
        val s = store()
        s.setAutoCheck("book-1", false)
        s.setAvailable("book-2", 7)
        val reopened = store()
        assertFalse(reopened.isAutoCheckEnabled("book-1"))
        assertTrue("不同书互不影响", reopened.isAutoCheckEnabled("book-2"))
        assertEquals(7, reopened.availableFloors("book-2"))
    }

    @Test
    fun `available 写读清零删条目`() {
        val s = store()
        s.setAvailable("book-1", 3)
        assertEquals(3, s.availableFloors("book-1"))
        assertEquals(mapOf("book-1" to 3), s.availableSnapshot())
        s.setAvailable("book-1", 0)
        assertEquals(0, s.availableFloors("book-1"))
        assertTrue("清零后条目应删除", s.availableSnapshot().isEmpty())
    }

    @Test
    fun `旧 muted 文件迁移：开关为关且旧文件被删`() {
        val legacy = File(tmp.root, "update_prompt_muted.json")
        legacy.writeText("""{"version":1,"muted":["book-1","book-2"]}""")
        val s = UpdateCheckStore(File(tmp.root, "update_checks.json"), legacyMutedFile = legacy)
        assertFalse(s.isAutoCheckEnabled("book-1"))
        assertFalse(s.isAutoCheckEnabled("book-2"))
        assertTrue(s.isAutoCheckEnabled("book-3"))
        assertFalse("旧文件应被删除", legacy.exists())
        // 迁移结果已持久化：不带 legacy 的新实例仍读到关闭。
        assertFalse(store().isAutoCheckEnabled("book-1"))
    }

    @Test
    fun `revision 随写盘递增且无变化不写盘`() {
        val s = store()
        val before = s.revision
        s.setAvailable("book-1", 2)
        assertEquals(before + 1, s.revision)
        s.setAvailable("book-1", 4)
        assertEquals(before + 2, s.revision)
        // 值未变化：不写盘、不递增。
        s.setAvailable("book-1", 4)
        assertEquals(before + 2, s.revision)
        // 默认开的书设 true：无变化不写盘。
        s.setAutoCheck("book-9", true)
        assertEquals(before + 2, s.revision)
    }

    @Test
    fun `checkedThisSession 会话内记忆且不持久化`() {
        val s = store()
        assertFalse(s.checkedThisSession("book-1"))
        s.markChecked("book-1")
        assertTrue(s.checkedThisSession("book-1"))
        assertFalse("不同书互不影响", s.checkedThisSession("book-2"))
        // 新实例（模拟重启）：会话内标记不跨实例。
        assertFalse(store().checkedThisSession("book-1"))
    }

    @Test
    fun `损坏 JSON 容错读回空不抛异常`() {
        val file = File(tmp.root, "update_checks.json")
        file.writeText("{corrupt")
        val s = UpdateCheckStore(file)
        assertTrue(s.isAutoCheckEnabled("book-1"))
        assertEquals(0, s.availableFloors("book-1"))
    }
}
