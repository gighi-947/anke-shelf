package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors
import java.net.URLEncoder
import org.jsoup.Jsoup

private val RE_IMG_TAG = Regex("""(?is)<img\b[^>]*>""")

/** HTML5 中 <script> 不是 void 元素；自闭合写法 <script .../> 会被解析为未闭合开始标签，
 *  吞掉后续正文。清洗前先归一化为显式闭合，避免 jsoup 升级后误删其后内容。 */
private val RE_SELF_CLOSING_SCRIPT = Regex("""(?is)<script\b[^>]*/>""")

/** 保留的白名单标签：NGA 楼层卡片/引用/骰子/表格/媒体/彩色字排版所需。 */
private val ALLOWED_TAGS = setOf(
    "a", "b", "strong", "i", "em", "u", "s", "strike", "del", "ins", "small", "big",
    "sub", "sup", "code", "pre", "kbd", "mark", "wbr",
    "span", "div", "p", "br", "hr",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "ul", "ol", "li", "dl", "dt", "dd",
    "blockquote", "details", "summary",
    "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "colgroup", "col",
    "img", "picture", "video", "audio", "source",
    "font", "center", "ruby", "rt", "rp", "figure", "figcaption",
    "style",
)

/** 直接移除的标签：脚本/嵌入/表单/元数据/矢量容器，畸形 HTML 也由解析器兜底。 */
private val BLOCKED_TAGS = setOf(
    "script", "iframe", "object", "embed", "form", "base", "meta", "link",
    "input", "button", "select", "textarea", "template", "noscript",
    "applet", "frame", "frameset", "svg", "math",
)

private val GLOBAL_ATTRS = setOf("class", "style", "title", "lang", "dir")

private val TAG_ATTRS: Map<String, Set<String>> = mapOf(
    "a" to setOf("href", "target", "rel", "name"),
    "img" to setOf("src", "alt", "width", "height", "loading", "decoding", "referrerpolicy"),
    "video" to setOf("src", "poster", "controls", "autoplay", "loop", "muted", "preload", "width", "height"),
    "audio" to setOf("src", "controls", "autoplay", "loop", "muted", "preload"),
    "source" to setOf("src", "srcset", "type", "media"),
    "td" to setOf("colspan", "rowspan", "headers"),
    "th" to setOf("colspan", "rowspan", "headers", "scope"),
    "col" to setOf("span", "width"),
    "ol" to setOf("start", "type", "reversed"),
    "font" to setOf("color", "face", "size"),
)

private fun isUnsafeUrl(value: String): Boolean {
    val v = value.trim().lowercase()
    return v.startsWith("javascript:") ||
        v.startsWith("vbscript:") ||
        v.startsWith("data:text/html")
}

/** 章节 HTML 的可渲染部分：<body> 内容 + <head> 里的样式块。 */
data class ReaderHtmlParts(
    val body: String,
    val headStyles: String,
)

/**
 * 从整章 XHTML 中提取可渲染内容。
 *
 * 桌面端直接把 XHTML 塞进 iframe；安卓 WebView 对以 XML 声明开头的文档
 * 经 loadData 加载时可能整页空白，所以这里剥离文档外壳，只取 body 与样式。
 */
fun extractReaderParts(htmlText: String): ReaderHtmlParts {
    val bodyStart = Regex("(?is)<body[^>]*>").find(htmlText)
    val bodyEnd = Regex("(?is)</body\\s*>").find(htmlText)
    val body = if (bodyStart != null && bodyEnd != null && bodyEnd.range.first > bodyStart.range.last) {
        htmlText.substring(bodyStart.range.last + 1, bodyEnd.range.first)
    } else {
        htmlText
            .replace(Regex("(?is)^\\s*<\\?xml[^>]*\\?>\\s*"), "")
            .replace(Regex("(?is)^\\s*<!DOCTYPE[^>]*>\\s*"), "")
    }
    val styles = Regex("(?is)<style[^>]*>.*?</style>")
        .findAll(htmlText)
        .joinToString("\n") { it.value }
    return ReaderHtmlParts(body = deferContentImages(sanitizeReaderBody(body)), headStyles = styles)
}

/** 正文图片统一补 `loading="lazy" decoding="async"`（已有 loading 的保持原样）。
 *  渲染期注入，覆盖已下载书籍；避免长帖打开时一次性加载/解码上千张图。 */
fun deferContentImages(body: String): String =
    RE_IMG_TAG.replace(body) { m ->
        val tag = m.value
        if (tag.contains("loading=", ignoreCase = true)) {
            tag
        } else {
            val core = tag.removeSuffix("/>").removeSuffix(">")
            "$core loading=\"lazy\" decoding=\"async\">"
        }
    }

/** 清洗章节 body：jsoup DOM 级白名单清洗，保留 NGA 排版样式。 */
fun sanitizeReaderBody(body: String): String {
    val normalized = RE_SELF_CLOSING_SCRIPT.replace(body, "<script></script>")
    val doc = Jsoup.parseBodyFragment(normalized)
    doc.outputSettings().prettyPrint(false)
    val root = doc.body()

    // 1) 移除脚本/嵌入/表单等危险元素（HTML5 解析器按 DOM 判定，不受畸形写法影响）。
    root.select(BLOCKED_TAGS.joinToString(",")).forEach { it.remove() }

    // 2) 非白名单元素解包（保留子内容），白名单元素清理属性。
    for (element in root.allElements.toList()) {
        if (element === root) continue
        val tag = element.tagName().lowercase()
        if (tag !in ALLOWED_TAGS) {
            if (element.parent() != null) element.unwrap()
            continue
        }
        val allowed = GLOBAL_ATTRS + (TAG_ATTRS[tag] ?: emptySet())
        for (attr in element.attributes().asList().toList()) {
            val key = attr.key.lowercase()
            val isUrl = key == "href" || key == "src" || key == "poster" || key == "srcset"
            val drop = key !in allowed || key.startsWith("on") || (isUrl && isUnsafeUrl(attr.value))
            if (drop) element.removeAttr(attr.key)
        }
    }
    return root.html()
}

/**
 * 组装阅读器页面（自建 HTML 壳，引用 assets/reader/reader.css + reader-lite.js）。
 *
 * 主题/字号/行距/边距先以内联 CSS 变量给出初始值（避免首帧白闪），
 * 之后由 Kotlin 经 JS 桥 applyTheme / applyTypography 实时更新，不重载页面。
 * 正文统一包在 #paged-scroll 中：滚动模式普通文档流，分页模式由
 * body.paged + reader.css 切换为 CSS multi-column。
 */
fun buildReaderHtml(
    parts: ReaderHtmlParts,
    theme: ReaderThemeColors,
    settings: SettingsData,
    bookId: String = "",
): String {
    // 正文字体："" / "sys:*" = 内置 LXGW（reader.css 默认栈）；
    // "system" = 系统默认；其余 = 已导入字体文件名（经 shouldInterceptRequest 提供 file:///android_fonts/）。
    // 按书字体优先于全局（对齐桌面 reader-utils.js resolveFamily 的 book_fonts 覆盖）。
    val customFont = settings.book_fonts[bookId]?.takeIf { it.isNotBlank() } ?: settings.custom_font
    val fontFace = if (
        customFont.isNotBlank() &&
        !customFont.startsWith("sys:") &&
        customFont != "system"
    ) {
        val encoded = URLEncoder.encode(customFont, "UTF-8").replace("+", "%20")
        "@font-face{font-family:'AnkeCustom';src:url('file:///android_fonts/$encoded');}"
    } else {
        ""
    }
    val fontVar = when {
        customFont == "system" ->
            "--reader-font-family:system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
        fontFace.isNotEmpty() ->
            "--reader-font-family:'AnkeCustom','LXGW WenKai','Noto Sans CJK SC','PingFang SC','Microsoft YaHei',sans-serif;"
        else -> ""
    }
    val css = buildString {
        append(
            ":root{" +
                "--reader-bg:${theme.background};--reader-fg:${theme.text};" +
                "--reader-primary:${theme.accent};" +
                "--reader-font-size:${settings.font_size}px;" +
                "--reader-line-height:${settings.line_height};" +
                "--reader-margin:${settings.margin_px}px;--reader-gap:${settings.gap_px}px;" +
                fontVar +
                "}",
        )
        if (fontFace.isNotEmpty()) append(fontFace)
        if (parts.headStyles.isNotBlank()) {
            append("\n").append(parts.headStyles)
        }
    }
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>" +
        // 绝对路径引用外壳资源：章节 base 可能换成 file:///android_epub/...（EPUB 图片）。
        "<link rel=\"stylesheet\" href=\"file:///android_asset/reader/reader.css\"/>" +
        "<style>$css</style></head><body>" +
        "<div id=\"paged-scroll\">${parts.body}</div>" +
        "<div class=\"chapter-nav-row\">" +
        "<button type=\"button\" id=\"android-prev-chapter\" class=\"chapter-nav-btn\">← 上一章</button>" +
        "<button type=\"button\" id=\"android-next-chapter\" class=\"chapter-nav-btn\">下一章 →</button>" +
        "</div>" +
        "<script src=\"file:///android_asset/reader/reader-lite.js\"></script></body></html>"
}
