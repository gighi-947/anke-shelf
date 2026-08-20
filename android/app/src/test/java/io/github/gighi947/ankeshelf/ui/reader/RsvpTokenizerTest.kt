package io.github.gighi947.ankeshelf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 速读分词业务不变量：中文不得退化成"整段一个词"（桌面 assist.js 的已知缺陷），
 * 标点不单独占帧，起点/长度受控。
 */
class RsvpTokenizerTest {

    @Test
    fun `中文按两字一块且标点吸附前块`() {
        val tokens = RsvpTokenizer.tokenize("今天天气不错，出门走走。")
        assertEquals(listOf("今天", "天气", "不错，", "出门", "走走。"), tokens)
    }

    @Test
    fun `拉丁与数字按词切分`() {
        val tokens = RsvpTokenizer.tokenize("roll 2d6 = 7 damage")
        assertEquals(listOf("roll", "2d6", "=", "7", "damage"), tokens)
    }

    @Test
    fun `中英混排各按自身规则切分`() {
        val tokens = RsvpTokenizer.tokenize("骰点 roll 成功")
        assertEquals(listOf("骰点", "roll", "成功"), tokens)
    }

    @Test
    fun `从指定偏移开始并受长度上限约束`() {
        val text = "甲乙丙丁戊己庚辛"
        assertEquals(listOf("丙丁", "戊己"), RsvpTokenizer.tokenize(text, from = 2, limit = 4))
        assertTrue(RsvpTokenizer.tokenize(text, from = text.length).isEmpty())
        assertTrue(RsvpTokenizer.tokenize("", from = 0).isEmpty())
    }

    @Test
    fun `速率换算夹在桌面同样的边界内`() {
        assertEquals(200L, RsvpTokenizer.intervalMs(300))
        assertEquals(80L, RsvpTokenizer.intervalMs(100000))
        assertEquals(2000L, RsvpTokenizer.intervalMs(1))
    }
}
