package io.github.gighi947.ankeshelf.data

/**
 * NGA 原始 content（BBCode + 少量 HTML）→ 还原风格的 XHTML。
 * 移植桌面 ngapost2md-python/ngapost2md/format_html.py，
 * 供原生书章节渲染使用；img_src 用于图片地址（在线/内嵌/无图模式）。
 */
object NgaFormatHtml {

    private val RE_SCRIPT = Regex("(?is)<script.*?</script>")
    private val RE_STYLE = Regex("(?is)<style.*?</style>")
    private val RE_BR = Regex("(?i)<br\\s*/?>")
    private val RE_B = Regex("(?s)\\[b\\](.*?)\\[/b\\]")
    private val RE_I = Regex("(?s)\\[i\\](.*?)\\[/i\\]")
    private val RE_URL = Regex("\\[url=(.+?)\\](.+?)\\[/url\\]")
    private val RE_URL_PLAIN = Regex("\\[url\\](.+?)\\[/url\\]")
    private val RE_IMG = Regex("\\[img\\](.+?)\\[/img\\]")
    private val RE_SMILE = Regex("\\[s:.+?:.+?\\]")
    private val RE_UID = Regex("\\[uid=(\\d+?)\\](.+?)\\[/uid\\]")
    private val RE_PID_REPLY = Regex("\\[pid=(\\d+?),.*?\\]Reply\\[/pid\\]")
    private val RE_COLOR = Regex("(?s)\\[color=([^\\]]+)\\](.*?)\\[/color\\]")
    private val RE_COLOR_OK = Regex("^[a-zA-Z]+$|^#[0-9a-fA-F]{3,8}$")
    private val RE_ANONY = Regex("#anony_.{32}")
    private val RE_DEL_GRAY = Regex("""<del class=['"]gray['"]>""")
    private val RE_DICE =
        Regex("""<div class='dice'><b>ROLL : (.+?)</b>=(.+?)=<b>(.+?)</b></div>""")
    private val RE_COLLAPSE = Regex(
        """<div class="foldBox no"><div class="collapse_btn"><a href="javascript:;" """ +
            """onclick="collapse\(this\);">\+</a>(.+?) ...</div>""" +
            """<span class="collapse_content" id="foldCnt">(.+?)</span></div>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val RE_POSTBY_UID =
        Regex("""<b>Post by \[uid=(\d+?)\](.+?)\[\/uid\][^<]*?\((\d{4}.*?)\):</b>""")
    private val RE_POSTBY_ANONY =
        Regex("""<b>Post by (.+?)<span .*?\((\d{4}.*?)\):</b>""")
    private val RE_SPAN_CLASS = Regex("""<span class="([a-zA-Z]+)">""")
    private val RE_THUMB_SUFFIX =
        Regex("""\.(thumb|medium)\.(?:jpg|jpeg|png|gif|webp)$""", RegexOption.IGNORE_CASE)

    private val NGA_COLORS = mapOf(
        "red" to "#ff0000", "skyblue" to "#87ceeb", "royalblue" to "#4169e1",
        "blue" to "#0000ff", "darkblue" to "#00008b", "orange" to "#ffa500",
        "orangered" to "#ff4500", "crimson" to "#dc143c", "firebrick" to "#b22222",
        "darkred" to "#8b0000", "green" to "#008000", "limegreen" to "#32cd32",
        "seagreen" to "#2e8b57", "teal" to "#008080", "deeppink" to "#ff1493",
        "tomato" to "#ff6347", "coral" to "#ff7f50", "purple" to "#800080",
        "indigo" to "#4b0082", "burlywood" to "#deb887", "sandybrown" to "#f4a460",
        "chocolate" to "#d2691e", "sienna" to "#a0522d", "silver" to "#c0c0c0",
        "gray" to "#808080", "gold" to "#ffd700", "brown" to "#a52a2a", "azure" to "#007fff",
    )

    private val NGA_COLORS_DARK = mapOf(
        "red" to "#ff6b6b", "skyblue" to "#7ec8e3", "royalblue" to "#7ba0ff",
        "blue" to "#6ea8fe", "darkblue" to "#6a8cff", "orange" to "#ffb86b",
        "orangered" to "#ff6a52", "crimson" to "#ff7070", "firebrick" to "#d76a6a",
        "darkred" to "#c0504d", "green" to "#6bc26b", "limegreen" to "#5edc5e",
        "seagreen" to "#5ec488", "teal" to "#3ddad0", "deeppink" to "#ff4fa3",
        "tomato" to "#ff7a6b", "coral" to "#ff9480", "purple" to "#b18bff",
        "indigo" to "#8a7bff", "burlywood" to "#e0c3a0", "sandybrown" to "#f7b774",
        "chocolate" to "#e08a5a", "sienna" to "#d0935f", "silver" to "#bdbdbd",
        "gray" to "#a0a0a0", "gold" to "#ffd766", "brown" to "#c98f5f",
        "azure" to "#5bc8ff",
    )

    private val THEME_LIGHT = QuoteTheme(
        border = "#e0e0e0", quoteBg = "#f7f7f7", accent = "#2e86ab",
        dice = "#b8860b", muted = "#888888",
    )
    private val THEME_DARK = QuoteTheme(
        border = "#3a3a3a", quoteBg = "#2a2a2a", accent = "#5ba3d9",
        dice = "#d9b45b", muted = "#8a8a8a",
    )

    private data class QuoteTheme(
        val border: String,
        val quoteBg: String,
        val accent: String,
        val dice: String,
        val muted: String,
    )

    /** NGA 图片 URL 规范化：缩略图/中图后缀剥除 + 相对/协议相对前缀解析。
     *  下载（embedded 本地化）与渲染共用此函数，保证本地文件名一致。 */
    fun normalizeImageUrl(url: String): String {
        var out = RE_THUMB_SUFFIX.replace(url, "")
        if (out.startsWith("./")) {
            out = "https://img.nga.178.com/attachments/" + out.substring(2)
        } else if (out.startsWith("//")) {
            out = "https:" + out
        }
        return out
    }

    /** 匿名 ID 转中文匿名称谓（对应桌面 format.anony）。 */
    fun anony(it: String): String {
        var i = 6
        val res = StringBuilder()
        for (j in 0 until 6) {
            if (j == 0 || j == 3) {
                val ch = it.getOrNull(i + 1) ?: '0'
                val n = ch.digitToIntOrNull(16) ?: 0
                if (n < NGA_ANONY_1.length) res.append(NGA_ANONY_1[n])
            } else {
                val hex = it.substring(i, minOf(i + 2, it.length))
                val n = hex.toIntOrNull(16) ?: 0
                if (n < NGA_ANONY_2.length) res.append(NGA_ANONY_2[n])
            }
            i += 2
        }
        return res.toString() + "?"
    }

    /**
     * 将 NGA 原始 content 渲染为 XHTML 片段。
     * @param dark 主题（影响内联颜色与引用块配色）
     * @param noImages 无图模式
     * @param imgSrc 图片/表情地址解析（在线模式返回原 URL；内嵌模式返回本地路径）
     */
    fun renderContentHtml(
        raw: String,
        dark: Boolean = false,
        noImages: Boolean = false,
        imgSrc: (String) -> String = { it },
    ): String {
        val colors = if (dark) NGA_COLORS_DARK else NGA_COLORS
        val theme = if (dark) THEME_DARK else THEME_LIGHT

        var c = RE_SCRIPT.replace(raw, "")
        c = RE_STYLE.replace(c, "")

        c = RE_COLLAPSE.replace(c) { m ->
            "<details><summary>${m.groupValues[1]}</summary><div>${m.groupValues[2]}</div></details>"
        }
        c = RE_DICE.replace(c) { m ->
            "<div class=\"nga-dice\" style=\"color:${theme.dice}; font-weight:bold; margin:6px 0;\">" +
                "ROLL : ${m.groupValues[1]}= <b>${m.groupValues[3]}</b></div>"
        }
        c = RE_ANONY.replace(c) { m -> safeAnony(m.value) }
        c = RE_POSTBY_UID.replace(c) { m ->
            "<div class=\"quote-author\">Post by ${m.groupValues[2]}(${m.groupValues[1]})(${m.groupValues[3]}):</div>"
        }
        c = RE_POSTBY_ANONY.replace(c) { m ->
            "<div class=\"quote-author\">Post by ${m.groupValues[1]}(${m.groupValues[2]}):</div>"
        }
        c = RE_PID_REPLY.replace(c) { m ->
            "<a class=\"reply-to\" href=\"#pid${m.groupValues[1]}\">回复</a>"
        }
        c = RE_UID.replace(c) { m -> "<span class=\"uid\">${m.groupValues[2]}</span>" }

        val qstyle = "border-left:3px solid ${theme.border}; background:${theme.quoteBg}; " +
            "padding:8px 12px; margin:10px 0; font-size:.95em;"
        c = c.replace("[quote]", "<blockquote class=\"nga-quote\" style=\"$qstyle\">")
        c = c.replace("[/quote]", "</blockquote>")

        c = RE_DEL_GRAY.replace(c, "<del>")
        c = RE_B.replace(c, "<b>\$1</b>")
        c = RE_I.replace(c, "<i>\$1</i>")
        c = RE_URL.replace(c, "<a href=\"\$1\">\$2</a>")
        c = RE_URL_PLAIN.replace(c, "<a href=\"\$1\">\$1</a>")
        c = RE_COLOR.replace(c) { m ->
            val color = m.groupValues[1].trim()
            if (!RE_COLOR_OK.matches(color)) {
                m.value
            } else {
                "<span style=\"color:${htmlEscape(color)}\">${m.groupValues[2]}</span>"
            }
        }
        c = RE_SMILE.replace(c) { m -> smileHtml(m.value, imgSrc) }
        c = RE_IMG.replace(c) { m -> imgHtml(m.groupValues[1], noImages, imgSrc) }
        c = RE_BR.replace(c, "<br/>")
        return RE_SPAN_CLASS.replace(c) { m ->
            val val_ = colors[m.groupValues[1]]
            if (val_ != null) {
                "<span class=\"${m.groupValues[1]}\" style=\"color:$val_\">"
            } else {
                m.value
            }
        }
    }

    private fun safeAnony(it: String): String = try {
        anony(it)
    } catch (_: Exception) {
        it
    }

    private fun smileHtml(it: String, imgSrc: (String) -> String): String {
        val file = NGA_SMILE_MAP[it] ?: return it
        val alt = it.split(":", limit = 3)[2].trimEnd(']')
        val url = SMILE_BASE + file
        return "<img class=\"smile\" alt=\"$alt\" src=\"${imgSrc(url)}\"/>"
    }

    private fun imgHtml(urlRaw: String, noImages: Boolean, imgSrc: (String) -> String): String {
        if (noImages) return ""
        val url = normalizeImageUrl(urlRaw)
        return "<img class=\"nga-img\" src=\"${imgSrc(url)}\"/>"
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")

    private const val SMILE_BASE = "https://img4.nga.178.com/ngabbs/post/smile/"
}
