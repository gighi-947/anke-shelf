package io.github.gighi947.ankeshelf.data

/**
 * HTML 字符串 → 折叠后纯文本（与桌面 app/text.py 的 extract_dom_text、
 * web/js/textpos.js 的 buildPlainText 逐字符对齐）。
 *
 * 规则：
 * 1. 只取 <body> 内文本（跳过 script/style）；无 body 时退化为全部；
 * 2. 每个标签（开始/结束/自闭合）视为相邻文本块之间的一个空格；
 * 3. \s+ 折叠为单个空格；
 * 4. 首尾 trim。
 */
object TextExtractor {

    private val WS = Regex("\\s+")
    private val SKIP_TAGS = setOf("script", "style")

    /** HTML 实体（与 Python html.unescape 的常用子集对齐，未知实体原样保留）。 */
    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "middot" to "\u00B7",
        "bull" to "\u2022", "deg" to "\u00B0", "plusmn" to "\u00B1",
        "times" to "\u00D7", "divide" to "\u00F7", "frac12" to "\u00BD",
        "frac14" to "\u00BC", "frac34" to "\u00BE", "sup2" to "\u00B2",
        "sup3" to "\u00B3", "eacute" to "\u00E9", "egrave" to "\u00E8",
        "agrave" to "\u00E0", "ccedil" to "\u00E7", "uuml" to "\u00FC",
        "ouml" to "\u00F6", "auml" to "\u00E4", "szlig" to "\u00DF",
        "alpha" to "\u03B1", "beta" to "\u03B2", "gamma" to "\u03B3",
        "delta" to "\u03B4", "sigma" to "\u03C3", "pi" to "\u03C0",
        "infin" to "\u221E", "ne" to "\u2260", "le" to "\u2264", "ge" to "\u2265",
    )

    fun extractDomText(htmlText: String): String {
        if (htmlText.isEmpty()) return ""
        val allChunks = StringBuilder()
        val bodyChunks = StringBuilder()
        var skipDepth = 0
        var bodyDepth = 0
        var sawBody = false

        fun tagSpace() {
            allChunks.append(' ')
            if (bodyDepth > 0) bodyChunks.append(' ')
        }

        fun appendData(data: String) {
            if (skipDepth == 0) {
                allChunks.append(data)
                if (bodyDepth > 0) bodyChunks.append(data)
            }
        }

        var i = 0
        val n = htmlText.length
        while (i < n) {
            val c = htmlText[i]
            when {
                c != '<' && c != '&' -> {
                    var j = i
                    while (j < n && htmlText[j] != '<' && htmlText[j] != '&') j++
                    appendData(htmlText.substring(i, j))
                    i = j
                }

                c == '&' -> {
                    val semi = htmlText.indexOf(';', i + 1)
                    if (semi in (i + 1) until minOf(i + 32, n)) {
                        val body = htmlText.substring(i + 1, semi)
                        appendData(decodeEntity(body) ?: htmlText.substring(i, semi + 1))
                        i = semi + 1
                    } else {
                        appendData("&")
                        i++
                    }
                }

                htmlText.startsWith("<!--", i) -> {
                    val end = htmlText.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }

                htmlText.startsWith("<![CDATA[", i) -> {
                    val end = htmlText.indexOf("]]>", i + 9)
                    if (end < 0) {
                        appendData(htmlText.substring(i + 9))
                        i = n
                    } else {
                        appendData(htmlText.substring(i + 9, end))
                        i = end + 3
                    }
                }

                htmlText.startsWith("<!", i) -> {
                    val end = htmlText.indexOf('>', i + 2)
                    i = if (end < 0) n else end + 1
                }

                htmlText.startsWith("<?", i) -> {
                    val end = htmlText.indexOf('>', i + 2)
                    i = if (end < 0) n else end + 1
                }

                c == '<' && i + 1 < n && (htmlText[i + 1].isLetter() || htmlText[i + 1] == '/') -> {
                    val end = htmlText.indexOf('>', i + 1)
                    if (end < 0) {
                        appendData(htmlText.substring(i))
                        i = n
                    } else {
                        val tagText = htmlText.substring(i + 1, end).trim()
                        val isEnd = tagText.startsWith("/")
                        val name = tagText.trimStart('/').substringBefore(' ').substringBefore('\t')
                            .substringBefore('\n').lowercase()
                        val selfClosing = !isEnd && tagText.endsWith("/")
                        if (isEnd) {
                            if (name in SKIP_TAGS && skipDepth > 0) skipDepth--
                            if (name == "body") {
                                if (bodyDepth > 0) bodyDepth--
                            } else if (bodyDepth > 0) {
                                bodyDepth--
                            }
                            tagSpace()
                        } else if (selfClosing) {
                            tagSpace()
                        } else {
                            if (name in SKIP_TAGS) skipDepth++
                            if (name == "body") {
                                sawBody = true
                                bodyDepth++
                            } else if (bodyDepth > 0) {
                                bodyDepth++
                            }
                            tagSpace()
                        }
                        i = end + 1
                    }
                }

                else -> {
                    appendData(c.toString())
                    i++
                }
            }
        }

        val chunks = if (sawBody) bodyChunks.toString() else allChunks.toString()
        return WS.replace(chunks, " ").trim()
    }

    private fun decodeEntity(body: String): String? {
        if (body.startsWith("#x") || body.startsWith("#X")) {
            return body.substring(2).toIntOrNull(16)?.let { codepoint ->
                if (codepoint in 0..0x10FFFF) String(Character.toChars(codepoint)) else null
            }
        }
        if (body.startsWith("#")) {
            return body.substring(1).toIntOrNull()?.let { codepoint ->
                if (codepoint in 0..0x10FFFF) String(Character.toChars(codepoint)) else null
            }
        }
        return ENTITIES[body]
    }
}
