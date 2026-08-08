package io.github.gighi947.ankeshelf.ui.reader.native

/**
 * 原生阅读器的块模型：把清洗后的章节 XHTML 解析成楼层/段落/引用/骰子/表格/图片，
 * 文本保留为 span（颜色/粗斜体），并维护章内折叠纯文本偏移（与桌面 TextPos 同口径：
 * 相邻文本块间一个空格、空白折叠、首尾 trim）。
 */

sealed class ReaderBlock {
    /** NGA 楼层卡片：头部（楼号/赞/用户名/时间）+ 正文块。 */
    data class Floor(
        val lou: Int,
        val likes: Int,
        val username: String,
        val userId: Long,
        val time: String,
        val pid: Long,
        val body: List<ReaderBlock>,
    ) : ReaderBlock()

    /** 普通段落（EPUB 内容同样走这里）。 */
    data class Paragraph(val spans: List<ReaderSpan>) : ReaderBlock()

    data class Heading(val spans: List<ReaderSpan>) : ReaderBlock()

    /** 引用块（quote）：左边条 + 底色，可嵌套块。 */
    data class Quote(val body: List<ReaderBlock>, val title: String = "") : ReaderBlock()

    /** NGA 骰子：金色加粗一行。 */
    data class Dice(val text: String) : ReaderBlock()

    data class TableRow(val cells: List<List<ReaderSpan>>)

    data class Table(val rows: List<TableRow>) : ReaderBlock()

    data class Image(val src: String, val alt: String = "") : ReaderBlock()

    /** 楼层追评。 */
    data class Comment(val spans: List<ReaderSpan>, val lou: Int, val username: String) : ReaderBlock()

    /** 空行占位。 */
    data class Blank(val ratio: Float = 1f) : ReaderBlock()
}

data class ReaderSpan(
    val text: String,
    val color: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val link: String? = null,
)

data class ReaderDoc(
    val blocks: List<ReaderBlock>,
    /** 折叠纯文本（text_offset 基准，与桌面 TextPos.text 同口径）。 */
    val plainText: String,
    /** 每个块在 plainText 中的起始偏移（最后一个为总长）。 */
    val blockOffsets: List<Int>,
)

/** 解析清洗后的章节 body → 块模型。 */
object ReaderHtmlModel {

    fun parse(body: String, styles: String = ""): ReaderDoc {
        val classColors = parseClassColors(styles)
        val root = Tokenizer(body).parse()
        val blocks = mutableListOf<ReaderBlock>()
        val plain = StringBuilder()
        for (child in root.children) {
            convert(child, blocks, plain, 0, classColors)
        }
        val text = plain.toString().replace(Regex("\\s+"), " ").trim()
        // 每个顶层块一个起点偏移（楼层与其追评同属一个根节点，按块数均匀切分；
        // 单调递增、末尾等于全文长，分页/进度锚点按块粒度足够）。
        val offsets = mutableListOf(0)
        if (blocks.isNotEmpty()) {
            val per = text.length / blocks.size
            for (i in 1..blocks.size) {
                offsets.add(minOf(text.length, i * per))
            }
        }
        return ReaderDoc(blocks = blocks, plainText = text, blockOffsets = offsets)
    }

    /** 解析章节自带 <style> 中的类颜色（.red{color:#ff0000} 等）。 */
    private fun parseClassColors(styles: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (m in Regex("""\.([a-zA-Z][\w-]*)\s*\{[^}]*?color\s*:\s*([^;}]+)""")
            .findAll(styles)) {
            out[m.groupValues[1].trim()] = m.groupValues[2].trim()
        }
        return out
    }

    private fun convert(
        node: Node,
        out: MutableList<ReaderBlock>,
        plain: StringBuilder,
        depth: Int,
        classColors: Map<String, String>,
    ) {
        if (depth > 24) return
        when (node.tag) {
            "div" -> when {
                node.hasClass("nga-floor") -> {
                    val head = node.findClass("floor-head")
                    val bodyNode = node.findClass("floor-body")
                    val comments = node.children.filter { it.hasClass("nga-comment") }
                    val headText = head?.textAll.orEmpty()
                    val bodyBlocks = mutableListOf<ReaderBlock>()
                    val bodyPlain = StringBuilder()
                    if (bodyNode != null) {
                        for (c in bodyNode.children) {
                            convert(c, bodyBlocks, bodyPlain, depth + 1, classColors)
                        }
                    }
                    val floor = ReaderBlock.Floor(
                        lou = Regex("(\\d+)\\s*楼").find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                        likes = Regex("(\\d+)\\s*赞").find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                        username = Regex("([^()\\s]+)\\s*\\((\\d+)\\)").find(headText)?.groupValues?.get(1).orEmpty(),
                        userId = Regex("([^()\\s]+)\\s*\\((\\d+)\\)").find(headText)?.groupValues?.get(2)?.toLongOrNull() ?: 0L,
                        time = headText.substringAfterLast(")").substringBefore("pid").trim().removePrefix("·").trim(),
                        pid = node.attr("id")?.removePrefix("pid")?.toLongOrNull() ?: 0L,
                        body = bodyBlocks,
                    )
                    out.add(floor)
                    plain.append(' ').append(bodyPlain.toString().trim())
                    for (c in comments) {
                        val spans = spansOf(c.children, mutableListOf(), classColors = classColors)
                        out.add(
                            ReaderBlock.Comment(
                                spans = spans,
                                lou = c.findClass("comment-head")?.textAll?.substringBefore("楼")?.trim()?.toIntOrNull() ?: 0,
                                username = c.findClass("comment-head")?.textAll?.substringAfter("楼")?.trim().orEmpty(),
                            ),
                        )
                        plain.append(' ').append(spans.joinToString("") { it.text })
                    }
                }
                node.hasClass("nga-quote") -> {
                    val inner = mutableListOf<ReaderBlock>()
                    val innerPlain = StringBuilder()
                    for (c in node.children) convert(c, inner, innerPlain, depth + 1, classColors)
                    out.add(ReaderBlock.Quote(inner))
                    plain.append(' ').append(innerPlain)
                }
                node.hasClass("nga-dice") -> {
                    val t = node.textAll
                    out.add(ReaderBlock.Dice(t))
                    plain.append(' ').append(t)
                }
                node.hasClass("nga-comment") -> {
                    val spans = spansOf(node.children, mutableListOf(), classColors = classColors)
                    out.add(ReaderBlock.Comment(spans, 0, ""))
                    plain.append(' ').append(spans.joinToString("") { it.text })
                }
                node.hasClass("nga-table-scroll") || node.hasClass("collapse_content") || node.hasClass("foldBox") ->
                    convertChildren(node, out, plain, depth, classColors)
                else -> {
                    val text = node.textAll
                    if (text.isBlank()) {
                        convertChildren(node, out, plain, depth, classColors)
                    } else {
                        out.add(ReaderBlock.Paragraph(spansOf(node.children, mutableListOf(), classColors = classColors)))
                        plain.append(' ').append(text)
                    }
                }
            }
            "p" -> {
                val spans = spansOf(node.children, mutableListOf(), classColors = classColors)
                if (spans.isNotEmpty()) out.add(ReaderBlock.Paragraph(spans))
                plain.append(' ').append(spans.joinToString("") { it.text })
            }
            "h1", "h2", "h3", "h4" -> {
                val spans = spansOf(node.children, mutableListOf(), classColors = classColors)
                if (spans.isNotEmpty()) out.add(ReaderBlock.Heading(spans))
                plain.append(' ').append(spans.joinToString("") { it.text })
            }
            "blockquote" -> {
                val inner = mutableListOf<ReaderBlock>()
                val innerPlain = StringBuilder()
                for (c in node.children) convert(c, inner, innerPlain, depth + 1, classColors)
                out.add(ReaderBlock.Quote(inner))
                plain.append(' ').append(innerPlain)
            }
            "table" -> {
                val rows = mutableListOf<ReaderBlock.TableRow>()
                var tbodySeen = false
                for (tr in node.children.filter { it.tag == "tr" || it.tag == "tbody" }) {
                    if (tr.tag == "tbody") {
                        tbodySeen = true
                        for (row in tr.children.filter { it.tag == "tr" }) {
                            rows.add(tableRow(row, classColors))
                        }
                    } else {
                        rows.add(tableRow(tr, classColors))
                    }
                }
                if (rows.isNotEmpty()) {
                    out.add(ReaderBlock.Table(rows))
                    for (row in rows) {
                        for (cell in row.cells) {
                            plain.append(' ').append(cell.joinToString("") { it.text })
                        }
                    }
                }
            }
            "img" -> {
                val src = node.attr("src").orEmpty()
                if (src.isNotBlank()) {
                    out.add(ReaderBlock.Image(src, node.attr("alt").orEmpty()))
                    plain.append(' ').append(node.attr("alt").orEmpty())
                }
            }
            "br" -> plain.append(' ')
            "hr" -> plain.append(' ')
            "details" -> {
                val summary = node.children.firstOrNull { it.tag == "summary" }?.textAll.orEmpty()
                val inner = mutableListOf<ReaderBlock>()
                val innerPlain = StringBuilder()
                for (c in node.children.filter { it.tag != "summary" }) {
                    convert(c, inner, innerPlain, depth + 1, classColors)
                }
                out.add(ReaderBlock.Quote(inner, title = summary))
                plain.append(' ').append(summary).append(' ').append(innerPlain)
            }
            "a", "b", "i", "span", "strong", "em", "u", "font", "del", "s", "sub", "sup", "code", "pre" ->
                convertChildren(node, out, plain, depth, classColors)
            "ul", "ol", "li" -> convertChildren(node, out, plain, depth, classColors)
            "script", "style", "head", "iframe", "object", "embed", "base", "form", "meta" -> Unit
            "#text" -> {
                if (node.text.isNotBlank()) {
                    out.add(ReaderBlock.Paragraph(listOf(ReaderSpan(node.text))))
                    plain.append(' ').append(node.text)
                }
            }
            else -> convertChildren(node, out, plain, depth, classColors)
        }
    }

    private fun convertChildren(
        node: Node,
        out: MutableList<ReaderBlock>,
        plain: StringBuilder,
        depth: Int,
        classColors: Map<String, String>,
    ) {
        for (c in node.children) convert(c, out, plain, depth + 1, classColors)
    }

    private fun tableRow(tr: Node, classColors: Map<String, String>): ReaderBlock.TableRow {
        val cells = tr.children.filter { it.tag == "td" || it.tag == "th" }
            .map { spansOf(it.children, mutableListOf(), classColors = classColors) }
        return ReaderBlock.TableRow(cells)
    }

    /** 内联内容 → span 列表（继承粗/斜/颜色）。 */
    private fun spansOf(
        nodes: List<Node>,
        out: MutableList<ReaderSpan>,
        bold: Boolean = false,
        italic: Boolean = false,
        color: String? = null,
        link: String? = null,
        classColors: Map<String, String> = emptyMap(),
    ): MutableList<ReaderSpan> {
        for (n in nodes) {
            when (n.tag) {
                "br" -> out.add(ReaderSpan("\n", bold = bold, italic = italic, color = color, link = link))
                "img" -> {
                    val alt = n.attr("alt").orEmpty()
                    if (alt.isNotBlank()) {
                        out.add(ReaderSpan(alt, bold = bold, italic = italic, color = color, link = link))
                    }
                }
                "b", "strong" -> spansOf(n.children, out, bold = true, italic = italic, color = color, link = link, classColors = classColors)
                "i", "em" -> spansOf(n.children, out, bold = bold, italic = true, color = color, link = link, classColors = classColors)
                "u" -> spansOf(n.children, out, bold = bold, italic = italic, color = color, link = link, classColors = classColors)
                "del", "s" -> spansOf(n.children, out, bold = bold, italic = italic, color = color, link = link, classColors = classColors)
                "a" -> spansOf(n.children, out, bold = bold, italic = italic, color = color, link = n.attr("href"), classColors = classColors)
                "span", "font" -> {
                    val cls = n.attr("class")?.trim()
                    val c = parseColor(n.attr("style"))
                        ?: cls?.let { classColors[it] }
                        ?: n.attr("color")
                        ?: color
                    spansOf(n.children, out, bold = bold, italic = italic, color = c, link = link, classColors = classColors)
                }
                "code", "pre", "sub", "sup" -> spansOf(n.children, out, bold = bold, italic = italic, color = color, link = link, classColors = classColors)
                "#text" -> {
                    if (n.text.isNotEmpty()) {
                        out.add(ReaderSpan(n.text, color = color, bold = bold, italic = italic, link = link))
                    }
                }
                else -> spansOf(n.children, out, bold = bold, italic = italic, color = color, link = link, classColors = classColors)
            }
        }
        return out
    }

    /** 从内联 style 里取 color：#xxx / rgb() / 命名色。 */
    private fun parseColor(style: String?): String? {
        if (style.isNullOrBlank()) return null
        val m = Regex("color\\s*:\\s*([^;]+)").find(style) ?: return null
        return m.groupValues[1].trim()
    }

    private class Node(
        val tag: String,
        val attrs: MutableMap<String, String> = mutableMapOf(),
        val children: MutableList<Node> = mutableListOf(),
        var text: String = "",
    ) {
        val className: String
            get() = attrs["class"].orEmpty()

        /** class 按空白拆词匹配（容忍 "nga-floor "、多类名等）。 */
        fun hasClass(cls: String): Boolean =
            attrs["class"].orEmpty().split(Regex("\\s+")).any { it == cls }

        fun attr(name: String): String? = attrs[name]

        fun findClass(cls: String): Node? {
            if (hasClass(cls)) return this
            for (c in children) {
                c.findClass(cls)?.let { return it }
            }
            return null
        }

        /** 后代文本（含自身）。 */
        val textAll: String
            get() {
                if (children.isEmpty()) return text
                val sb = StringBuilder()
                fun walk(n: Node) {
                    if (n.children.isEmpty()) sb.append(n.text)
                    else n.children.forEach { walk(it) }
                }
                walk(this)
                return sb.toString()
            }
    }

    private val VOID_TAGS = setOf("br", "img", "hr", "meta", "link", "input", "source", "col")
    private val AUTO_CLOSE = setOf("p", "li", "tr", "td", "th", "h1", "h2", "h3", "h4", "dt", "dd")

    /** 轻量 HTML 分词器 → DOM 树（容忍 NGA/EPUB 常见脏标签）。 */
    private class Tokenizer(private val html: String) {
        fun parse(): Node {
            val root = Node("#root")
            val stack = ArrayDeque<Node>()
            stack.addLast(root)
            var i = 0
            val n = html.length
            val text = StringBuilder()
            fun flushText() {
                if (text.isNotEmpty()) {
                    stack.last().children.add(Node("#text").apply { this.text = text.toString() })
                    text.setLength(0)
                }
            }
            while (i < n) {
                val lt = html.indexOf('<', i)
                if (lt < 0) {
                    text.append(html.substring(i))
                    break
                }
                if (lt > i) text.append(html.substring(i, lt))
                val gt = html.indexOf('>', lt + 1)
                if (gt < 0) {
                    text.append(html.substring(lt))
                    break
                }
                val raw = html.substring(lt + 1, gt).trim()
                i = gt + 1
                if (raw.startsWith("!--")) {
                    val end = html.indexOf("-->", gt + 1)
                    i = if (end < 0) n else end + 3
                    continue
                }
                if (raw.startsWith("!")) continue
                if (raw.startsWith("/")) {
                    val name = raw.substring(1).substringBefore(' ').substringBefore('\t').lowercase()
                    flushText()
                    if (stack.size > 1) {
                        // 从栈里找到同名节点并弹出（容忍未闭合标签）。
                        var idx = stack.size - 1
                        while (idx > 0 && stack[idx].tag != name) idx--
                        if (idx > 0) {
                            while (stack.size > idx) stack.removeLast()
                        }
                    }
                    continue
                }
                val tagName = raw.substringBefore(' ').substringBefore('\t').lowercase()
                if (tagName.isEmpty()) continue
                val attrs = mutableMapOf<String, String>()
                val attrRe = Regex("""([a-zA-Z_:][a-zA-Z0-9_.:-]*)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""")
                for (m in attrRe.findAll(raw)) {
                    attrs[m.groupValues[1].lowercase()] =
                        m.groupValues[2].trim('"', '\'')
                }
                if (raw.endsWith("/") || tagName in VOID_TAGS) {
                    flushText()
                    stack.last().children.add(Node(tagName, attrs))
                    continue
                }
                flushText()
                val node = Node(tagName, attrs)
                stack.last().children.add(node)
                if (tagName in AUTO_CLOSE && stack.last().tag == tagName) {
                    // 不自动闭合：保持嵌套，由结束标签处理。
                }
                stack.addLast(node)
            }
            flushText()
            return root
        }
    }
}
