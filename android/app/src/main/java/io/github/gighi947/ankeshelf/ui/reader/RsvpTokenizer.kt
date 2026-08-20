package io.github.gighi947.ankeshelf.ui.reader

/**
 * 速读（RSVP）分词：把折叠纯文本切成逐个闪现的词块。
 *
 * 与桌面 `web/js/assist.js` 的差异（有意改进）：桌面按空白切词，中文正文没有空格
 * 会退化成"整段一个词"，速读实际不可用；这里按字符类别分段——
 * 拉丁/数字按词、CJK 按 [cjkChunk] 个字一块、标点单独成块并吸附到前一块尾部。
 */
object RsvpTokenizer {

    private const val DEFAULT_CJK_CHUNK = 2

    /**
     * CJK「文字」判定：范围命中 **且** 是字母。
     * `isLetter` 这道闸门很关键——全角标点（，。！）与 CJK 标点（、《》）都落在
     * 上述码位区间内，但不是字母；漏掉它会把标点当成汉字凑进词块（如"，出"）。
     */
    private fun isCjk(c: Char): Boolean {
        val code = c.code
        val inRange = code in 0x2E80..0x2FFF ||
            code in 0x3040..0x30FF ||
            code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF ||
            code in 0xFF66..0xFF9F
        return inRange && c.isLetter()
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() && !isCjk(c)

    /** 标点/符号（非空白、非词字符、非 CJK 文字）。 */
    private fun isPunct(c: Char): Boolean = !c.isWhitespace() && !isWordChar(c) && !isCjk(c)

    fun tokenize(text: String, from: Int = 0, limit: Int = 3000, cjkChunk: Int = DEFAULT_CJK_CHUNK): List<String> {
        if (text.isEmpty() || limit <= 0) return emptyList()
        val start = from.coerceIn(0, text.length)
        val end = (start + limit).coerceAtMost(text.length)
        if (start >= end) return emptyList()
        val chunk = cjkChunk.coerceIn(1, 8)
        val out = mutableListOf<String>()
        var i = start
        // 紧跟上一块（中间无空白）的标点才吸附；空白分隔的符号（如 "a = b" 的 =）独立成块。
        var adjacent = false
        while (i < end) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    adjacent = false
                    i++
                }
                isWordChar(c) -> {
                    val begin = i
                    while (i < end && isWordChar(text[i])) i++
                    out += text.substring(begin, i)
                    adjacent = true
                }
                isCjk(c) -> {
                    val begin = i
                    while (i < end && isCjk(text[i]) && i - begin < chunk) i++
                    out += text.substring(begin, i)
                    adjacent = true
                }
                else -> {
                    val begin = i
                    while (i < end && isPunct(text[i])) i++
                    val punct = text.substring(begin, i)
                    if (adjacent && out.isNotEmpty()) {
                        out[out.lastIndex] = out.last() + punct
                    } else {
                        out += punct
                    }
                    adjacent = true
                }
            }
        }
        return out
    }

    /** 速率（字/分钟）→ 每块停留毫秒；夹在 80..2000ms（与桌面 setSpeed 边界一致）。 */
    fun intervalMs(rate: Int): Long =
        (60_000.0 / rate.coerceIn(30, 750)).toLong().coerceIn(80L, 2000L)
}
