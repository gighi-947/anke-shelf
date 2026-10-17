package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 进书更新提示屏蔽：默认未屏蔽、mute 后跨实例（模拟重启）可读、
 * 损坏 JSON 容错读回空且不抛异常。
 */
class UpdatePromptPrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prefs(): UpdatePromptPrefs =
        UpdatePromptPrefs(File(tmp.root, "update_prompt_muted.json"))

    @Test
    fun `默认未屏蔽`() {
        assertFalse(prefs().isMuted("book-1"))
    }

    @Test
    fun `mute 后即屏蔽且同路径新实例可读`() {
        val s = prefs()
        s.mute("book-1")
        assertTrue(s.isMuted("book-1"))

        // 同路径新实例（模拟重启）持久化可读
        val reopened = prefs()
        assertTrue(reopened.isMuted("book-1"))
        assertFalse("不同书互不影响", reopened.isMuted("book-2"))
    }

    @Test
    fun `写入损坏 JSON 后读取回空不抛异常`() {
        val file = File(tmp.root, "update_prompt_muted.json")
        file.writeText("{corrupt")
        val s = UpdatePromptPrefs(file)
        assertFalse(s.isMuted("book-1"))
    }
}
