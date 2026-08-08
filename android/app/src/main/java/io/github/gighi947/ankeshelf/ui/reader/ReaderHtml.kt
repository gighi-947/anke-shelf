package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors
import java.net.URLEncoder

private val RE_IMG_TAG = Regex("""(?is)<img\b[^>]*>""")

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

/** 清洗章节 body：删除脚本/危险标签/事件属性/javascript: 链接，保留 NGA 排版样式。 */
fun sanitizeReaderBody(body: String): String {
    var s = body
    s = s.replace(Regex("(?is)<script\\b[^>]*>.*?</script\\s*>"), "")
    s = s.replace(Regex("(?is)<script\\b[^>]*>.*"), "")
    s = s.replace(
        Regex("(?is)<\\s*(?:iframe|object|embed|base|form)\\b[^>]*>.*?</\\s*(?:iframe|object|embed|form)\\s*>"),
        "",
    )
    s = s.replace(Regex("(?is)<\\s*(?:iframe|object|embed|base|form)\\b[^>]*/?>"), "")
    s = s.replace(Regex("(?is)<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[^>]*>"), "")
    s = s.replace(Regex("(?is)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
    s = s.replace(Regex("(?is)\\b(?:href|src)\\s*=\\s*[\"']?\\s*javascript:[^\"'>\\s]*[\"']?"), "")
    return s
}

/**
 * 组装阅读器页面（自建 HTML 壳，引用 assets/reader/reader.css + reader.js）。
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
): String {
    // 正文字体："" / "sys:*" = 内置 LXGW（reader.css 默认栈）；
    // "system" = 系统默认；其余 = 已导入字体文件名（经 shouldInterceptRequest 提供 file:///android_fonts/）。
    val customFont = settings.custom_font
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
        "<link rel=\"stylesheet\" href=\"reader.css\"/>" +
        "<style>$css</style></head><body>" +
        "<div id=\"paged-scroll\">${parts.body}</div>" +
        "<div class=\"chapter-nav-row\">" +
        "<button type=\"button\" id=\"android-prev-chapter\" class=\"chapter-nav-btn\">← 上一章</button>" +
        "<button type=\"button\" id=\"android-next-chapter\" class=\"chapter-nav-btn\">下一章 →</button>" +
        "</div>" +
        "<script src=\"reader.js\"></script></body></html>"
}
