package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.SpineItem
import io.github.gighi947.ankeshelf.data.TocEntry

/**
 * 目录节点（扁平化后的树）：`depth` 为层级（0 起），`chapterIndex` 为可跳转的
 * spine 索引；无法定位到章节的中间层级节点（只有标题的 li）保持 null，仅作分组标题。
 *
 * 对齐桌面 `web/js/toc.js`：多级目录展开显示、当前章高亮、只有可定位项才能点击。
 */
data class TocNode(
    val label: String,
    val chapterIndex: Int?,
    val depth: Int,
)

object TocTree {

    /** 目录树 → 扁平节点列表（前序遍历，保留层级）。 */
    fun flatten(entries: List<TocEntry>, resolve: (String) -> Int?): List<TocNode> {
        val out = mutableListOf<TocNode>()

        fun walk(list: List<TocEntry>, depth: Int) {
            for (e in list) {
                val label = e.label.trim().ifEmpty { "(无标题)" }
                out += TocNode(
                    label = label,
                    chapterIndex = e.spineIndex ?: resolve(e.href),
                    depth = depth,
                )
                if (e.children.isNotEmpty()) walk(e.children, depth + 1)
            }
        }

        walk(entries, 0)
        return out
    }

    /** 无目录树时的兜底：spine 章节扁平列表（与旧抽屉行为一致）。 */
    fun fromChapters(chapters: List<SpineItem>, titleFn: (Int) -> String): List<TocNode> =
        chapters.indices.map { i -> TocNode(label = titleFn(i), chapterIndex = i, depth = 0) }

    /**
     * 当前章对应的目录项下标：取"可定位到该章"的最后一个节点
     * （同章多个锚点时高亮最靠后的那个标题，与桌面 toc.js 的当前项语义一致）。
     */
    fun activeIndex(nodes: List<TocNode>, chapterIndex: Int): Int =
        nodes.indexOfLast { it.chapterIndex == chapterIndex }
}
