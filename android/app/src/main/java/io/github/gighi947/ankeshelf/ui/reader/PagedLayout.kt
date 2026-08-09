package io.github.gighi947.ankeshelf.ui.reader

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 分页几何（纯函数，与 assets/reader/reader-lite.js 的 geometry 逐项对照）。
 *
 * 双页模式自动判定规则（Android 专用）：
 * - 强制双页（dual_page）：任何屏幕都双页；
 * - 自动双页（auto_dual，默认）：仅横屏且宽度 >= 800px；
 *   宽高比 < 1.2（过方）或 > 2.6（超宽屏）不自动双页；
 *   双页后单列宽 < 300px 时回退单页（保护狭长屏幕）。
 * - 列宽按 reader-lite.js 几何计算：左右对称 padding P = min(margin, gap - 8)
 *   （P <= gap - 8 保证翻页后左缘落在列间隙而非上一列尾部），
 *   单页 colW = fw - 2P，双页 colW = (fw - 2P - G) / 2（可为小数）；
 *   内容可用宽恰好等于目标列宽，浏览器不会拉伸/钳制列宽。
 */
object PagedLayout {

    const val MAX_PAGED_TEXT = 800_000
    const val MIN_DUAL_COL = 300.0

    data class Geometry(
        val dual: Boolean,
        val colW: Double,
        val advance: Double,
        val margin: Int,
        val paddingRight: Int,
        val gap: Int,
        val contentWidth: Int,
    )

    fun clamp(v: Int, lo: Int, hi: Int): Int = v.coerceIn(lo, hi)

    /** 有效内容宽度：min(视口宽, 46 * page_width * fontSize)。暂用于未来 page_width 支持。 */
    fun contentWidth(fw: Int, pageWidth: Double, fontSize: Int): Int {
        val maxW = (46.0 * pageWidth * fontSize).roundToInt()
        return max(120, min(fw, maxW))
    }

    /** 自动双页比例判定（与 JS shouldAutoDual 一致）。 */
    fun shouldAutoDual(fw: Int, fh: Int): Boolean {
        if (fw < 800 || fw <= fh) return false
        val aspect = fw.toDouble() / fh.toDouble()
        if (aspect < 1.2 || aspect > 2.6) return false
        return true
    }

    fun isDual(paged: Boolean, dualPage: Boolean, autoDual: Boolean, fw: Int, fh: Int): Boolean {
        if (!paged) return false
        if (dualPage) return true
        if (!autoDual) return false
        return shouldAutoDual(fw, fh)
    }

    fun geometry(
        fw: Int,
        fh: Int,
        paged: Boolean,
        dualPage: Boolean,
        autoDual: Boolean,
        margin: Int,
        gap: Int,
        pageWidth: Double,
        fontSize: Int,
    ): Geometry {
        var dual = isDual(paged, dualPage, autoDual, fw, fh)
        // 分页容器占满视口宽；左右对称 padding P，与 reader-lite.js 一致。
        val m = clamp(margin, 8, 160)
        val g = clamp(gap, 8, 120)
        val p = max(4, min(m, g - 8))
        var colW = if (dual) {
            max(120.0, (fw - 2 * p - g) / 2.0)
        } else {
            max(120.0, (fw - 2 * p).toDouble())
        }
        if (dual && colW < MIN_DUAL_COL) {
            dual = false
            colW = max(120.0, (fw - 2 * p).toDouble())
        }
        return Geometry(
            dual = dual,
            colW = colW,
            advance = colW + g,
            margin = p,
            paddingRight = p,
            gap = g,
            contentWidth = fw,
        )
    }

    /** 列数 -> 总页数（双页时一屏两列），返回 (total, step)。
     *  容器左 padding = margin、右 padding = paddingRight：
     *  scrollWidth = margin + n*colW + (n-1)*gap + paddingRight，与 reader-lite.js 一致。 */
    fun pages(scrollWidth: Int, g: Geometry, hasSpacer: Boolean): Pair<Int, Int> {
        var cols = max(
            1,
            ((scrollWidth - g.margin - g.paddingRight + g.gap).toDouble() / g.advance).roundToInt(),
        )
        if (hasSpacer) cols = max(1, cols - 1)
        val step = if (g.dual) 2 else 1
        return max(1, ceil(cols.toDouble() / step).toInt()) to step
    }

    fun currentPage(scrollLeft: Int, g: Geometry, total: Int, step: Int): Int {
        if (g.advance <= 0) return 0
        return (scrollLeft.toDouble() / (step * g.advance)).roundToInt().coerceIn(0, total - 1)
    }
}
