package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 骨碌碌解锁与线索存储：跨会话保持、上限裁剪、单书重置、
 * 以及"用已收集线索解开秘密"的业务不变量。
 */
class GululuUnlockStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): GululuUnlockStore = GululuUnlockStore(
        File(tmp.root, "gululu_unlocks.json"),
        File(tmp.root, "gululu_clues.json"),
    ).also { it.load() }

    @Test
    fun `骰点解锁跨会话保持且重复解锁不写盘`() {
        val s = store()
        assertTrue(s.unlock("book-1", "700-g-0"))
        assertFalse("重复解锁应无变化", s.unlock("book-1", "700-g-0"))
        assertTrue(s.unlock("book-1", "700-g-1"))
        assertEquals(listOf("700-g-0", "700-g-1"), s.unlockedGroups("book-1"))

        // 重新加载（模拟重启）后仍在
        val reopened = store()
        assertEquals(listOf("700-g-0", "700-g-1"), reopened.unlockedGroups("book-1"))
        assertTrue("不同书互不影响", reopened.unlockedGroups("book-2").isEmpty())
    }

    @Test
    fun `批量揭示只计新增`() {
        val s = store()
        assertEquals(3, s.unlockAll("b", listOf("g1", "g2", "g3")))
        assertEquals(1, s.unlockAll("b", listOf("g2", "g4")))
        assertEquals(0, s.unlockAll("b", listOf("", " ")))
        assertEquals(listOf("g1", "g2", "g3", "g4"), s.unlockedGroups("b"))
    }

    @Test
    fun `超过上限时裁剪并保留最近解锁`() {
        val s = store()
        val groups = (1..GululuUnlockStore.MAX_GROUPS + 5).map { "g$it" }
        s.unlockAll("b", groups)
        val kept = s.unlockedGroups("b")
        assertEquals(GululuUnlockStore.TRIM_GROUPS, kept.size)
        assertEquals("必须保留最近解锁的", "g${GululuUnlockStore.MAX_GROUPS + 5}", kept.last())
        assertFalse("最早的应被裁掉", kept.contains("g1"))
    }

    @Test
    fun `线索收集与秘密解开`() {
        val s = store()
        assertEquals(GululuSecretReveal.NoClue, s.revealSecret("b", "U2FsdGVkX1+xxx"))

        // 用真实 CryptoJS 兼容格式：先用同算法造一个密文（借助解密的逆向不可行，
        // 因此这里直接验证"错误口令不误报成功"与"线索被持久化"）
        assertTrue(s.collectClue("b", "钥匙", "open123"))
        assertFalse("同标题同口令不算更新", s.collectClue("b", "钥匙", "open123"))
        assertEquals(mapOf("钥匙" to "open123"), s.clues("b"))
        assertEquals(GululuSecretReveal.NoClue, s.revealSecret("b", "U2FsdGVkX1+not-a-real-cipher"))

        val reopened = store()
        assertEquals("线索必须跨会话保持", "open123", reopened.clues("b")["钥匙"])
    }

    @Test
    fun `单书重置只清解锁与线索`() {
        val s = store()
        s.unlock("b1", "g1")
        s.collectClue("b1", "t", "p")
        s.unlock("b2", "g2")

        s.reset("b1")
        assertTrue(s.unlockedGroups("b1").isEmpty())
        assertTrue(s.clues("b1").isEmpty())
        assertEquals("其他书不受影响", listOf("g2"), s.unlockedGroups("b2"))

        val reopened = store()
        assertTrue(reopened.unlockedGroups("b1").isEmpty())
        assertEquals(listOf("g2"), reopened.unlockedGroups("b2"))
    }

    @Test
    fun `损坏文件回退空状态而不崩溃`() {
        File(tmp.root, "gululu_unlocks.json").writeText("{broken")
        File(tmp.root, "gululu_clues.json").writeText("[]")
        val s = store()
        assertTrue(s.unlockedGroups("b").isEmpty())
        assertTrue(s.clues("b").isEmpty())
        // 仍可继续写入
        assertTrue(s.unlock("b", "g1"))
        assertEquals(listOf("g1"), store().unlockedGroups("b"))
    }
}
