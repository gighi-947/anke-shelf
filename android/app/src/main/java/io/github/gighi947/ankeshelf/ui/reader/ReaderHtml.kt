package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.SettingsData

/** 章节 HTML 的可用部分：<body> 内容 + <head> 里的样式块。 */
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

/** 组装阅读器页面（自建 HTML 壳，主题/字号/行距全部内联）。 */
fun buildReaderHtml(
    parts: ReaderHtmlParts,
    theme: ReaderThemeColors,
    settings: SettingsData,
): String {
    val css = buildString {
        append(
            "html,body{margin:0;padding:0}" +
                "body{background:${theme.background};color:${theme.text};" +
                "font-size:${settings.font_size}px;line-height:${settings.line_height};" +
                "font-family:sans-serif;padding:16px 14px;overflow-wrap:break-word;}" +
                "a{color:${theme.accent}}img{max-width:100%;height:auto}" +
                ".nga-floor{max-width:100%}blockquote{margin:8px 0}" +
                "table{max-width:100%;display:block;overflow-x:auto}",
        )
        if (parts.headStyles.isNotBlank()) {
            append("\n").append(parts.headStyles)
        }
    }
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>" +
        "<style>$css</style></head><body>${parts.body}</body></html>"
}
