"""解析 NGA 目录楼（如 tid 41989465 的 25671 楼）为结构化 TOC。

目录内容为 NGA BBCode/HTML 混合，结构：
  <div class="foldBox no">+<章节标题>...<span class="collapse_content">
      <h4>Day XX</h4><条目>...
  </div>
条目形如：<标题>[url=https://bbs.nga.cn/read.php?pid=N&opt=128]#楼号[/url]
"""
import html as html_mod
import re

RE_FOLD = re.compile(
    r'<div class="foldBox no"><div class="collapse_btn">.*?<a[^>]*>\+</a>(.*?)\.\.\.</div>'
    r'<span class="collapse_content"[^>]*>(.*?)</span></div>',
    re.DOTALL,
)
RE_H4 = re.compile(r"<h4[^>]*>(.*?)</h4>", re.DOTALL)
RE_ENTRY = re.compile(
    r"(.+?)\[url=https://bbs\.nga\.cn/read\.php\?pid=(\d+)[^\]]*\](?:#\d+)?\[/url\]",
    re.DOTALL,
)


def clean_html(text: str) -> str:
    """去除 HTML 标签、quote BBCode 残留与实体，压缩空白。"""
    text = re.sub(r"<[^>]+>", "", text)
    text = html_mod.unescape(text)
    # 仅移除 [quote]/[/quote] 残留（保留 [昴星团行动] 这类合法标题内容）
    text = re.sub(r"\[/?quote\]", "", text, flags=re.IGNORECASE)
    return " ".join(text.split())


def _extract_entries(body: str) -> list[tuple[str, int]]:
    entries = []
    for m in RE_ENTRY.finditer(body):
        title = clean_html(m.group(1))
        pid = int(m.group(2))
        if title:
            entries.append((title, pid))
    return entries


def parse_toc(content: str) -> list[dict]:
    """解析目录内容，返回章节列表。

    每章：{"title": str, "lead": [(title, pid)], "days": [{"day": str, "entries": [(title, pid)]}]}
    lead 为章节内、无 Day 分组的条目。
    """
    chapters = []
    for fold in RE_FOLD.finditer(content):
        title = clean_html(fold.group(1))
        inner = fold.group(2)
        parts = RE_H4.split(inner)  # [前缀, day1, 段1, day2, 段2, ...]
        lead = _extract_entries(parts[0]) if parts and parts[0].strip() else []
        days = []
        for i in range(1, len(parts), 2):
            day_title = clean_html(parts[i])
            body = parts[i + 1] if i + 1 < len(parts) else ""
            days.append({"day": day_title, "entries": _extract_entries(body)})
        chapters.append({"title": title, "lead": lead, "days": days})
    return chapters
