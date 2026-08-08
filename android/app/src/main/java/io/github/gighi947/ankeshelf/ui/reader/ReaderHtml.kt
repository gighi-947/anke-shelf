package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.SettingsData
import io.github.gighi947.ankeshelf.ui.theme.ReaderThemeColors

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
    return ReaderHtmlParts(body = body, headStyles = styles)
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
    val css = buildString {
        append(
            ":root{" +
                "--reader-bg:${theme.background};--reader-fg:${theme.text};" +
                "--reader-primary:${theme.accent};" +
                "--reader-font-size:${settings.font_size}px;" +
                "--reader-line-height:${settings.line_height};" +
                "--reader-margin:${settings.margin_px}px;--reader-gap:${settings.gap_px}px;" +
                "}",
        )
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
