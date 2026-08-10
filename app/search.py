"""全文搜索：惰性内存索引 + 子串查询。

- 中文无需分词：子串匹配天然支持（str.find 对 Unicode 按字符序扫描）
- 索引按需构建（打开书后 daemon 线程），不落盘 —— 避免陈旧索引，
  重建成本约 0.2s
- 书籍被 LRU 逐出时索引随之清理
- 高频词策略：按“每章限量”返回命中，而不是全书总数截断。
  这样靠后章节的高频词（如 NGA 安科里的角色名）不会被前几章占满，
  全书总命中数与命中章节数单独统计，前端可提示并可“加载更多”。

索引文本与 web/js/textpos.js 的 buildPlainText 逐字符对齐（同一折叠规则），
因此搜索命中的 offset 可直接在 JS 端定位（进度/标注共用同一坐标系）。
offset/text_len 一律按 UTF-16 code unit 计数（与 DOM/JS 字符串索引一致；
星形字符占 2 个 code unit），Python 内部仍用码点索引扫描。
"""
import threading
import re
from typing import Optional

from .domain import book_revision
from .epub import EpubBook
from .text import cp_index_from_utf16, extract_dom_text, utf16_index, utf16_len


def _word_re(q: str) -> re.Pattern:
    """全词匹配正则：只要求两侧不是字母/数字/下划线（ASCII 词边界）。

    中文/日文等没有空格分词，全词开关对其无影响（仍按子串匹配），
    只约束英文/数字类关键词。
    """
    return re.compile(r"(?<![A-Za-z0-9_])" + re.escape(q) + r"(?![A-Za-z0-9_])")


class SearchService:
    """每本书一个内存索引。线程安全。"""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._indexes: dict[str, Optional[dict]] = {}

    def ensure_index(self, book: EpubBook, revision: Optional[str] = None) -> None:
        """逐章提取纯文本建索引；同一 revision 的索引复用，变化时重建。"""
        revision = revision or book_revision(book)
        with self._lock:
            if book.id in self._indexes:
                idx = self._indexes[book.id]
                if idx is None:
                    return  # 正在构建
                if idx.get("revision") == revision:
                    return
                self._indexes[book.id] = None  # 版本变化：重建
            else:
                self._indexes[book.id] = None  # 占位：正在构建
        chapters = []
        total = 0
        for i in range(len(book.chapters)):
            raw = book.chapter_text(i)
            text = extract_dom_text(raw) if raw else ""
            chapters.append({"index": i, "title": book.chapter_title(i), "text": text})
            total += utf16_len(text)
        with self._lock:
            self._indexes[book.id] = {
                "chapters": chapters,
                "total": total,
                "lens": {ch["index"]: len(ch["text"]) for ch in chapters},
                "revision": revision,
            }

    def refresh_if_stale(self, book: EpubBook) -> bool:
        """书籍 revision 与索引不一致时重建；返回是否触发重建。"""
        rev = book_revision(book)
        with self._lock:
            idx = self._indexes.get(book.id)
            if idx is not None and idx.get("revision") == rev:
                return False
        self.ensure_index(book, rev)
        return True

    def is_ready(self, book_id: str) -> bool:
        with self._lock:
            idx = self._indexes.get(book_id)
            return idx is not None and idx.get("chapters") is not None

    def chapter_len(self, book_id: str, index: int) -> Optional[int]:
        """指定章节的纯文本长度（索引未就绪返回 None）。"""
        with self._lock:
            idx = self._indexes.get(book_id)
            if idx is None or idx.get("chapters") is None:
                return None
            return idx.get("lens", {}).get(index)

    def drop(self, book_id: str) -> None:
        with self._lock:
            self._indexes.pop(book_id, None)

    def _iter_hits(self, text: str, q: str, start: int, case_sensitive: bool, whole_word: bool):
        """在单章文本中从 start 起逐个产出命中偏移（可重叠扫描）。"""
        hay = text if case_sensitive else text.lower()
        needle = q if case_sensitive else q.lower()
        if whole_word:
            rx = _word_re(needle)
            pos = start
            while True:
                m = rx.search(hay, pos)
                if m is None:
                    return
                yield m.start()
                pos = m.start() + 1
        else:
            pos = hay.find(needle, start)
            while pos != -1:
                yield pos
                pos = hay.find(needle, pos + 1)

    def _count_hits(self, text: str, q: str, case_sensitive: bool, whole_word: bool) -> int:
        """章节内的全部命中数（C 层快速统计；整词模式按非重叠计数）。"""
        hay = text if case_sensitive else text.lower()
        needle = q if case_sensitive else q.lower()
        if whole_word:
            return len(_word_re(needle).findall(hay))
        return hay.count(needle)

    def _make_snippet(self, text: str, pos: int, q: str, snippet_len: int) -> str:
        s = max(0, pos - snippet_len)
        e = min(len(text), pos + len(q) + snippet_len)
        return text[s:e]

    def search(
        self,
        book_id: str,
        query: str,
        case_sensitive: bool = False,
        whole_word: bool = False,
        per_chapter: int = 50,
        snippet_len: int = 40,
    ) -> Optional[dict]:
        """查询全书。索引未就绪返回 None；无命中返回带空结果的统计。

        返回:
          {
            "total_hits": 全书总命中数,
            "hit_chapters": 有命中的章节数,
            "total_chapters": 总章节数,
            "results": [{
                "chapter_index", "chapter_title", "text_len",
                "chapter_hits": 该章全部命中数,
                "more": 该章是否还有未返回的命中,
                "hits": [{"offset", "snippet"}],  # 最多 per_chapter 条
            }]
          }
        """
        with self._lock:
            idx = self._indexes.get(book_id)
            if idx is None or idx.get("chapters") is None:
                return None
            chapters = idx["chapters"]
        q = (query or "").strip()
        if not q:
            return {
                "total_hits": 0,
                "hit_chapters": 0,
                "total_chapters": len(chapters),
                "results": [],
            }
        results = []
        total_hits = 0
        hit_chapters = 0
        for ch in chapters:
            hits = []
            n = 0
            more = False
            for pos in self._iter_hits(ch["text"], q, 0, case_sensitive, whole_word):
                if n >= per_chapter:
                    more = True
                    break
                hits.append({"offset": utf16_index(ch["text"], pos), "snippet": self._make_snippet(ch["text"], pos, q, snippet_len)})
                n += 1
            ch_total = self._count_hits(ch["text"], q, case_sensitive, whole_word)
            total_hits += ch_total
            if hits:
                hit_chapters += 1
                results.append(
                    {
                        "chapter_index": ch["index"],
                        "chapter_title": ch["title"],
                        "text_len": utf16_len(ch["text"]),
                        "chapter_hits": ch_total,
                        "more": more or ch_total > n,
                        "hits": hits,
                    }
                )
        return {
            "total_hits": total_hits,
            "hit_chapters": hit_chapters,
            "total_chapters": len(chapters),
            "results": results,
        }

    def search_more(
        self,
        book_id: str,
        query: str,
        chapter_index: int,
        after_offset: int,
        case_sensitive: bool = False,
        whole_word: bool = False,
        per_chapter: int = 50,
        snippet_len: int = 40,
    ) -> Optional[dict]:
        """在指定章节中续取 after_offset 之后的下一条命中。

        after_offset 为已返回的最后一条命中的 offset（不含），
        返回 {"hits": [...], "more": bool}；索引未就绪/章节不存在返回 None。
        """
        with self._lock:
            idx = self._indexes.get(book_id)
            if idx is None or idx.get("chapters") is None:
                return None
            chapters = idx["chapters"]
        q = (query or "").strip()
        if not q:
            return {"hits": [], "more": False}
        ch = None
        for c in chapters:
            if c["index"] == int(chapter_index):
                ch = c
                break
        if ch is None:
            return None
        start = cp_index_from_utf16(ch["text"], max(0, int(after_offset) + 1))
        hits = []
        n = 0
        gen = self._iter_hits(ch["text"], q, start, case_sensitive, whole_word)
        while n < per_chapter:
            try:
                pos = next(gen)
            except StopIteration:
                more = False
                break
            hits.append({"offset": utf16_index(ch["text"], pos), "snippet": self._make_snippet(ch["text"], pos, q, snippet_len)})
            n += 1
        else:
            # 已取满 per_chapter，再试探一条判断是否还有更多
            try:
                next(gen)
                more = True
            except StopIteration:
                more = False
        return {"hits": hits, "more": more}
