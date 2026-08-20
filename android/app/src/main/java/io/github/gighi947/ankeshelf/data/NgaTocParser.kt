package io.github.gighi947.ankeshelf.data

/**
 * NGA 目录楼解析（Kotlin 版 `ngapost2md/toc.py`，逐规则对照）。
 *
 * 目录楼正文是 NGA BBCode/HTML 混合，结构：
 * ```
 * <div class="foldBox no"><div class="collapse_btn">…<a>+</a>章节标题...</div>
 *   <span class="collapse_content"><h4>Day XX</h4>条目…</span></div>
 * ```
 * 条目形如 `标题[url=https://bbs.nga.cn/read.php?pid=N&opt=128]#楼号[/url]`。
 *
 * 输出直接是 `meta.json` 落盘用的扁平结构（title + entries），与桌面
 * `_serialize_toc`（lead 在前、随后按 Day 顺序展开）一致；`toc_mode=split`
 * 时由 [NativeBookWriter] 按各章首个可定位条目所在楼层切章。
 */
object NgaTocParser {

    private val RE_FOLD = Regex(
        "<div class=\"foldBox no\"><div class=\"collapse_btn\">.*?<a[^>]*>\\+</a>(.*?)\\.\\.\\.</div>" +
            "<span class=\"collapse_content\"[^>]*>(.*?)</span></div>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val RE_H4 = Regex("<h4[^>]*>(.*?)</h4>", RegexOption.DOT_MATCHES_ALL)
    private val RE_ENTRY = Regex(
        "(.+?)\\[url=https://bbs\\.nga\\.cn/read\\.php\\?pid=(\\d+)[^\\]]*\\](?:#\\d+)?\\[/url\\]",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val RE_TAG = Regex("<[^>]+>")
    private val RE_QUOTE_BBCODE = Regex("\\[/?quote\\]", RegexOption.IGNORE_CASE)
    private val RE_NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
    private val RE_NAMED_ENTITY = Regex("&([a-zA-Z][a-zA-Z0-9]{1,31});")

    /** 去 HTML 标签、解实体、去 quote BBCode 残留、压缩空白（对齐 `clean_html`）。 */
    fun cleanHtml(text: String): String {
        var out = RE_TAG.replace(text, "")
        out = unescapeEntities(out)
        // 只移除 [quote]/[/quote] 残留，保留 [昴星团行动] 这类合法标题内容
        out = RE_QUOTE_BBCODE.replace(out, "")
        return out.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun unescapeEntities(text: String): String {
        if (!text.contains('&')) return text
        var out = RE_NUMERIC_ENTITY.replace(text) { m ->
            val raw = m.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) {
                raw.drop(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            if (code != null && code in 0..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        out = RE_NAMED_ENTITY.replace(out) { m ->
            HTML5_ENTITIES[m.groupValues[1]] ?: HTML5_ENTITIES[m.groupValues[1] + ";"] ?: m.value
        }
        return out
    }

    private fun extractEntries(body: String): List<NativeTocEntry> =
        RE_ENTRY.findAll(body).mapNotNull { m ->
            val title = cleanHtml(m.groupValues[1])
            val pid = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            if (title.isEmpty()) null else NativeTocEntry(title, pid)
        }.toList()

    /**
     * 解析目录楼正文 → 章节列表（无折叠块或无条目时返回空表，调用方据此回退按楼分章）。
     *
     * 与桌面 `parse_toc` + `_serialize_toc` 的组合等价：先取 `<h4>` 之前的 lead 条目，
     * 再按 Day 顺序拼接各段条目，Day 标题本身不进 `meta.toc`（只用于站点展示）。
     */
    fun parseToc(content: String): List<NativeTocChapter> =
        RE_FOLD.findAll(content).map { fold ->
            val title = cleanHtml(fold.groupValues[1])
            val inner = fold.groupValues[2]
            val heads = RE_H4.findAll(inner).toList()
            val leadEnd = heads.firstOrNull()?.range?.first ?: inner.length
            val entries = mutableListOf<NativeTocEntry>()
            entries += extractEntries(inner.substring(0, leadEnd))
            heads.forEachIndexed { i, head ->
                val bodyStart = head.range.last + 1
                val bodyEnd = if (i + 1 < heads.size) heads[i + 1].range.first else inner.length
                if (bodyStart <= bodyEnd) entries += extractEntries(inner.substring(bodyStart, bodyEnd))
            }
            NativeTocChapter(title = title, entries = entries)
        }.filter { it.entries.isNotEmpty() }.toList()
}
