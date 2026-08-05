"""全文搜索：惰性内存索引 + 子串查询。

- 中文无需分词：子串匹配天然支持（str.find 对 Unicode 按字符序扫描）
- 索引按需构建（打开书后 daemon 线程），不落盘 —— 避免陈旧索引，
  重建成本约 0.2s
- 书籍被 LRU 逐出时索引随之清理

索引文本与 web/js/textpos.js 的 buildPlainText 逐字符对齐（同一折叠规则），
因此搜索命中的 offset 可直接在 JS 端定位（进度/标注共用同一坐标系）。
"""
import threading
from typing import Optional

from .epub import EpubBook
from .text import extract_dom_text


class SearchService:
    """每本书一个内存索引。线程安全。"""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._indexes: dict[str, Optional[dict]] = {}

    def ensure_index(self, book: EpubBook) -> None:
        """daemon 线程调用：逐章提取纯文本建索引。重复调用安全。"""
        with self._lock:
            if book.id in self._indexes:
                return
            self._indexes[book.id] = None  # 占位：正在构建
        chapters = []
        total = 0
        for i in range(len(book.chapters)):
            raw = book.chapter_text(i)
            text = extract_dom_text(raw) if raw else ""
            chapters.append({"index": i, "title": book.chapter_title(i), "text": text})
            total += len(text)
        with self._lock:
            self._indexes[book.id] = {
                "chapters": chapters,
                "total": total,
                "lens": {ch["index"]: len(ch["text"]) for ch in chapters},
            }

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

    def search(
        self, book_id: str, query: str, max_hits: int = 100, snippet_len: int = 30
    ) -> Optional[list[dict]]:
        """查询全书。索引未就绪返回 None；无命中返回空列表。

        命中结构: [{"chapter_index", "chapter_title", "hits": [{"offset", "snippet"}]}]
        """
        with self._lock:
            idx = self._indexes.get(book_id)
            if idx is None or idx.get("chapters") is None:
                return None
            chapters = idx["chapters"]
        q = (query or "").strip()
        if not q:
            return []
        q_low = q.lower()
        results = []
        count = 0
        for ch in chapters:
            low = ch["text"].lower()
            hits = []
            start = 0
            while count < max_hits:
                pos = low.find(q_low, start)
                if pos == -1:
                    break
                s = max(0, pos - snippet_len)
                e = min(len(ch["text"]), pos + len(q) + snippet_len)
                hits.append({"offset": pos, "snippet": ch["text"][s:e]})
                count += 1
                start = pos + 1
            if hits:
                results.append(
                    {
                        "chapter_index": ch["index"],
                        "chapter_title": ch["title"],
                        "text_len": len(ch["text"]),
                        "hits": hits,
                    }
                )
        return results
