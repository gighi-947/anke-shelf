package io.github.gighi947.ankeshelf.ui.reader.native

/**
 * 换章/开书时的恢复锚点：把“crossJump 跨章跳转优先、initialChapter 按存储
 * 恢复、会话内普通换章从头开始”的单一策略编码在一处。
 *
 * 模式隔离不变量：跨章跳转按文本锚点定位，page/total/ratio 必须全部让位
 * （-1），否则 reader-lite 的 restoreScrollOffset 对 ratio∈[0,1] 优先于
 * text_offset，会把位置拉回旧比例处。
 */
data class RestoreAnchor(
    val offset: Int = 0,
    val page: Int = -1,
    val total: Int = -1,
    val ratio: Double = -1.0,
)

fun restoreAnchorFor(
    chapterIndex: Int,
    initialChapter: Int,
    crossJump: Pair<Int, Int>?,
    savedAnchor: RestoreAnchor,
): RestoreAnchor = when {
    crossJump != null && crossJump.first == chapterIndex ->
        RestoreAnchor(offset = crossJump.second)
    chapterIndex == initialChapter -> savedAnchor
    else -> RestoreAnchor()
}
