package io.github.gighi947.ankeshelf.data

/**
 * NGA BBcode → Markdown（简化移植桌面 format.py / _fix_most 的常见标签，
 * 用于导出 post.md；媒体下载/楼层修复等后续再对齐）。
 */
object NgaMarkdown {

    private val RE_BR = Regex("(?i)<br\\s*/?>")
    private val RE_B = Regex("(?s)\\[b\\](.*?)\\[/b\\]")
    private val RE_I = Regex("(?s)\\[i\\](.*?)\\[/i\\]")
    private val RE_URL = Regex("\\[url=(.+?)\\](.+?)\\[/url\\]")
    private val RE_URL_PLAIN = Regex("\\[url\\](.+?)\\[/url\\]")
    private val RE_IMG = Regex("\\[img\\](.+?)\\[/img\\]")
    private val RE_SMILE = Regex("\\[s:.+?:.+?\\]")
    private val RE_UID = Regex("\\[uid=(\\d+?)\\](.+?)\\[/uid\\]")
    private val RE_PID_REPLY = Regex("\\[pid=(\\d+?),.*?\\]Reply\\[/pid\\]")
    private val RE_COLOR = Regex("(?s)\\[color=[^\\]]+\\](.*?)\\[/color\\]")
    private val RE_ANONY = Regex("#anony_.{32}")
    private val RE_DICE =
        Regex("""<div class='dice'><b>ROLL : (.+?)</b>=(.+?)=<b>(.+?)</b></div>""")
    private val RE_COLLAPSE = Regex(
        """<div class="foldBox no"><div class="collapse_btn"><a href="javascript:;" """ +
            """onclick="collapse\(this\);">\+</a>(.+?) ...</div>""" +
            """<span class="collapse_content" id="foldCnt">(.+?)</span></div>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 整章 Markdown：标题 + 楼层序列。 */
    fun chapterToMarkdown(title: String, floors: List<NativeFloor>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        for (f in floors) {
            sb.append(floorToMarkdown(f)).append("\n\n")
            for (c in f.comments) {
                if (c.lou <= 0) continue
                sb.append("> ")
                    .append(c.lou).append("楼 · ")
                    .append(c.username).append("：")
                    .append(convert(c.raw_content).replace("\n", "\n> "))
                    .append("\n\n")
            }
        }
        return sb.toString().trim() + "\n"
    }

    fun floorToMarkdown(f: NativeFloor): String {
        val head = "${f.lou}楼 · ${f.like_num}赞 · ${f.username}(${f.user_id}) · " +
            f.timestamp.let { ts ->
                val dt = java.time.Instant.ofEpochSecond(ts)
                    .atZone(java.time.ZoneId.systemDefault())
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(dt)
            }
        return "### $head\n\n${convert(f.raw_content)}"
    }

    fun convert(raw: String): String {
        var c = raw
        c = c.replace("[quote]", "\n> ")
        c = c.replace("[/quote]", "\n")
        c = RE_DICE.replace(c) { m ->
            "**【ROLL】 : ${m.groupValues[1]}= **${m.groupValues[3]}**"
        }
        c = RE_COLLAPSE.replace(c) { m ->
            "<details>\n  <summary>${m.groupValues[1]}</summary>\n  <pre>${m.groupValues[2]}</pre>\n</details>"
        }
        c = RE_ANONY.replace(c) { m -> NgaFormatHtml.anony(m.value) }
        c = RE_BR.replace(c, "\n")
        c = RE_B.replace(c, "**\$1**")
        c = RE_I.replace(c, "*\$1*")
        c = RE_URL.replace(c, "[\$2](\$1)")
        c = RE_URL_PLAIN.replace(c, "[\$1](\$1)")
        c = RE_IMG.replace(c) { m ->
            val url = NgaFormatHtml.normalizeImageUrl(m.groupValues[1])
            "![图片]($url)"
        }
        c = RE_UID.replace(c, "\$2")
        c = RE_PID_REPLY.replace(c, "回复")
        c = RE_COLOR.replace(c, "\$1")
        c = RE_SMILE.replace(c) { m -> smileText(m.value) }
        return c.trim()
    }

    private fun smileText(it: String): String {
        val name = it.split(":", limit = 3).getOrNull(2)?.trimEnd(']').orEmpty()
        return "[表情:$name]".ifBlank { "[表情]" }
    }
}
