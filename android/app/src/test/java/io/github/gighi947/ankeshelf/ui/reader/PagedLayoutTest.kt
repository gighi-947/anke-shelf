package io.github.gighi947.ankeshelf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedLayoutTest {

    @Test
    fun autoDualRespectsAspectRatio() {
        // 横屏且宽高比合适 -> 双页
        assertTrue(PagedLayout.shouldAutoDual(2400, 1080))
        assertTrue(PagedLayout.shouldAutoDual(1280, 720))
        assertTrue(PagedLayout.shouldAutoDual(1024, 768))
        assertTrue(PagedLayout.shouldAutoDual(800, 600))
        // 过方（接近正方形）不自动双页
        assertFalse(PagedLayout.shouldAutoDual(800, 700))
        assertFalse(PagedLayout.shouldAutoDual(900, 900))
        // 超宽屏不自动双页
        assertFalse(PagedLayout.shouldAutoDual(2400, 600))
        assertFalse(PagedLayout.shouldAutoDual(3440, 900))
        // 宽度不足或竖屏不自动双页
        assertFalse(PagedLayout.shouldAutoDual(700, 600))
        assertFalse(PagedLayout.shouldAutoDual(1080, 2400))
    }

    @Test
    fun forcedDualOverridesAuto() {
        assertTrue(PagedLayout.isDual(true, true, true, 1080, 2400))
        assertTrue(PagedLayout.isDual(true, true, false, 360, 800))
        assertFalse(PagedLayout.isDual(false, true, true, 1080, 2400))
    }

    @Test
    fun geometryUsesFullViewportWidthWithNoRightLeak() {
        // 宽屏横屏双页：内容宽 = 视口宽；下一列起点恰好落在视口右边界
        val g = PagedLayout.geometry(
            fw = 2400, fh = 1080, paged = true, dualPage = false, autoDual = true,
            margin = 40, gap = 28, pageWidth = 1.0, fontSize = 18,
        )
        assertEquals(2400, g.contentWidth)
        assertTrue(g.dual)
        assertEquals(1166, g.colW) // (2400 - 40 - 28) / 2
        assertEquals(1194, g.advance)
    }

    @Test
    fun geometryFallsBackToSingleWhenDualColumnTooNarrow() {
        val g = PagedLayout.geometry(
            fw = 360, fh = 800, paged = true, dualPage = true, autoDual = true,
            margin = 40, gap = 28, pageWidth = 1.0, fontSize = 18,
        )
        // 强制双页但列宽 (360-40-28)/2=146 过窄 -> 回退单页
        assertEquals(360, g.contentWidth)
        assertFalse(g.dual)
        assertEquals(292, g.colW)
    }

    @Test
    fun pagesAndCurrentMatchMultiColumnGeometry() {
        val g = PagedLayout.Geometry(dual = true, colW = 360, advance = 388, margin = 40, gap = 28, contentWidth = 828)
        // 3 列内容 + 1 占位列 = 4 列，双页 2 屏
        // 容器仅左 padding：scrollWidth = margin + n*colW + (n-1)*gap
        val scrollWidth = 40 + 4 * 360 + 3 * 28
        val (total, step) = PagedLayout.pages(scrollWidth = scrollWidth, g = g, hasSpacer = true)
        assertEquals(2, total)
        assertEquals(2, step)
        assertEquals(0, PagedLayout.currentPage(scrollLeft = 0, g = g, total = total, step = step))
        assertEquals(1, PagedLayout.currentPage(scrollLeft = 776, g = g, total = total, step = step))
    }
}
