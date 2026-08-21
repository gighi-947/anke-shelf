package io.github.gighi947.ankeshelf.ui.reader.native

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 恢复锚点单点策略回归（2026-08-22 审查清理）。
 *
 * 修复的破洞：restoreRatio 此前漏接 crossJump——跨章跳转（标注抽屉/书签）
 * 回到初始章时，旧代码仍把 tracker 里的滚动比例传给 reader-lite，而
 * restoreScrollOffset 对 ratio∈[0,1] 优先于 text_offset，结果按旧比例滚动、
 * 无视跳转锚点。收敛为 restoreAnchorFor 单函数后，crossJump 命中时
 * page/total/rate 全部让位（-1），文本锚点唯一生效。
 */
class RestoreAnchorTest {

    private val saved = RestoreAnchor(offset = 512, page = 7, total = 30, ratio = 0.5)

    @Test
    fun `crossJump 命中本章时文本锚点唯一生效`() {
        val a = restoreAnchorFor(
            chapterIndex = 5,
            initialChapter = 5,
            crossJump = 5 to 1024,
            savedAnchor = saved,
        )
        assertEquals(1024, a.offset)
        assertEquals(-1, a.page)
        assertEquals(-1, a.total)
        assertEquals(-1.0, a.ratio, 0.0)
    }

    @Test
    fun `初始章按存储恢复四种锚点`() {
        val a = restoreAnchorFor(
            chapterIndex = 5,
            initialChapter = 5,
            crossJump = null,
            savedAnchor = saved,
        )
        assertEquals(saved, a)
    }

    @Test
    fun `会话内普通换章从头开始`() {
        val a = restoreAnchorFor(
            chapterIndex = 3,
            initialChapter = 5,
            crossJump = null,
            savedAnchor = saved,
        )
        assertEquals(RestoreAnchor(), a)
    }

    @Test
    fun `crossJump 指向他章时不影响当前章`() {
        // crossJump 目标是第 7 章，而当前渲染第 3 章（非初始章）：从章头开始
        assertEquals(
            RestoreAnchor(),
            restoreAnchorFor(3, 5, crossJump = 7 to 900, savedAnchor = saved),
        )
        // 跨章跳转后回到初始章（crossJump 已被清空）：按存储恢复
        assertEquals(
            saved,
            restoreAnchorFor(5, 5, crossJump = null, savedAnchor = saved),
        )
    }
}
