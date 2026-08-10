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

    /** Unicode 空白（对齐 Python \\s / JS \\s 的常用集合，含 NBSP 与 U+2000–U+3000 空白族）。 */
    private val WS = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")
    private val SKIP_TAGS = setOf("script", "style")

    /** HTML 命名实体解码：完整 HTML5 表（Html5Entities.kt），未知实体原样保留。 */
    private val ENTITIES: Map<String, String> = HTML5_ENTITIES

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
                        i = n
                    } else {
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
