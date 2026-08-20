package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.SpineItem
import io.github.gighi947.ankeshelf.data.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 目录树扁平化：对齐桌面 `web/js/toc.js` 的多级目录语义
 * （前序遍历 + 层级缩进 + 只有可定位项可点击 + 当前章高亮取最后一个匹配项）。
 */
class TocTreeTest {

    private val nested = listOf(
        TocEntry(
            label = "第一部",
            href = "part1.xhtml",
            spineIndex = 0,
            children = listOf(
                TocEntry(label = "第 1 章", href = "ch1.xhtml", spineIndex = 1),
                TocEntry(
                    label = "第 2 章",
                    href = "ch2.xhtml",
                    spineIndex = 2,
                    children = listOf(
                        TocEntry(label = "2.1 小节", href = "ch2.xhtml#s1", spineIndex = 2),
                    ),
                ),
            ),
        ),
        TocEntry(label = "附录", href = "appendix.xhtml", spineIndex = 3),
    )

    @Test
    fun `前序遍历保留层级`() {
        val nodes = TocTree.flatten(nested) { null }
        assertEquals(
            listOf(
                "第一部" to 0,
                "第 1 章" to 1,
                "第 2 章" to 1,
                "2.1 小节" to 2,
                "附录" to 0,
            ),
            nodes.map { it.label to it.depth },
        )
        assertEquals(listOf(0, 1, 2, 2, 3), nodes.map { it.chapterIndex })
    }

    @Test
    fun `spineIndex 缺失时用 href 解析`() {
        val entries = listOf(TocEntry(label = "序", href = "intro.xhtml", spineIndex = null))
        val nodes = TocTree.flatten(entries) { href -> if (href == "intro.xhtml") 7 else null }
        assertEquals(7, nodes.single().chapterIndex)
    }

    @Test
    fun `无法定位的分组标题保持不可点击`() {
        val entries = listOf(
            TocEntry(
                label = "  ",
                href = "#",
                spineIndex = null,
                children = listOf(TocEntry(label = "子章", href = "c.xhtml", spineIndex = 1)),
            ),
        )
        val nodes = TocTree.flatten(entries) { null }
        assertEquals("(无标题)", nodes[0].label)
        assertNull(nodes[0].chapterIndex)
        assertEquals(1, nodes[1].chapterIndex)
    }

    @Test
    fun `当前章高亮取最后一个匹配节点`() {
        val nodes = TocTree.flatten(nested) { null }
        // 第 2 章与其 2.1 小节都指向 spine 2：高亮更靠后的小节标题。
        assertEquals(3, TocTree.activeIndex(nodes, 2))
        assertEquals(0, TocTree.activeIndex(nodes, 0))
        assertEquals(-1, TocTree.activeIndex(nodes, 99))
    }

    @Test
    fun `无目录树时回退 spine 章节扁平列表`() {
        val chapters = listOf(
            SpineItem(index = 0, idref = "c0", href = "a.xhtml"),
            SpineItem(index = 1, idref = "c1", href = "b.xhtml"),
        )
        val nodes = TocTree.fromChapters(chapters) { "第 ${it + 1} 章" }
        assertEquals(listOf("第 1 章", "第 2 章"), nodes.map { it.label })
        assertEquals(listOf(0, 0), nodes.map { it.depth })
        assertEquals(listOf(0, 1), nodes.map { it.chapterIndex })
    }
}
