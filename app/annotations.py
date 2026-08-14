"""标注存储（annotations.json）：高亮 + 书签 CRUD、导出。

数据模型：
  {"version":1, "books": {"<book_id>": {
      "highlights": [{"id","chapter_index","start_offset","end_offset",
                      "text","color","note","created_at","updated_at"}],
      "bookmarks":  [{"id","chapter_index","offset","text","created_at"}]
  }}}

偏移为章节内折叠纯文本字符坐标（见 app/text.py），与前端 TextPos 对齐。
"""
import json
import threading
import uuid
from pathlib import Path
from typing import Optional

from .storage import atomic_write_json, load_json_file, now_iso

HL_COLORS = ("yellow", "green", "blue", "pink", "purple", "cyan")


class AnnotationStore:
    """per-book 高亮与书签。线程安全，原子写。"""

    def __init__(self, file: Path):
        self._file = file
        self._lock = threading.RLock()
        self._books: dict = {}

    def load(self) -> None:
        data = load_json_file(self._file)
        self._books = data.get("books", {}) if data else {}

    def save(self) -> None:
        with self._lock:
            atomic_write_json(self._file, {"version": 1, "books": self._books})

    def _book(self, book_id: str) -> dict:
        b = self._books.setdefault(book_id, {"highlights": [], "bookmarks": []})
        b.setdefault("highlights", [])
        b.setdefault("bookmarks", [])
        return b

    # ---------- 读取 ----------

    def get_highlights(self, book_id: str) -> list[dict]:
        with self._lock:
            return [dict(h) for h in self._book(book_id)["highlights"]]

    def get_bookmarks(self, book_id: str) -> list[dict]:
        with self._lock:
            return [dict(b) for b in self._book(book_id)["bookmarks"]]

    def get_all(self, book_id: str) -> dict:
        return {"highlights": self.get_highlights(book_id), "bookmarks": self.get_bookmarks(book_id)}

    # ---------- 高亮 ----------

    def add_highlight(
        self,
        book_id: str,
        chapter_index: int,
        start_offset: int,
        end_offset: int,
        text: str,
        color: str = "yellow",
        note: str = "",
    ) -> dict:
        start_offset = max(0, int(start_offset))
        end_offset = max(0, int(end_offset))
        if end_offset <= start_offset:
            raise ValueError("高亮区间无效")
        if color not in HL_COLORS:
            color = "yellow"
        now = now_iso()
        ann = {
            "id": uuid.uuid4().hex[:12],
            "chapter_index": max(0, int(chapter_index)),
            "start_offset": start_offset,
            "end_offset": end_offset,
            "text": (text or "")[:2000],
            "color": color,
            "note": (note or "")[:5000],
            "created_at": now,
            "updated_at": now,
        }
        with self._lock:
            self._book(book_id)["highlights"].append(ann)
            self.save()
        return dict(ann)

    def update_annotation(self, book_id: str, ann_id: str, patch: dict) -> Optional[dict]:
        with self._lock:
            for h in self._book(book_id)["highlights"]:
                if h["id"] == ann_id:
                    if "note" in patch:
                        h["note"] = (patch.get("note") or "")[:5000]
                    if "color" in patch and patch.get("color") in HL_COLORS:
                        h["color"] = patch["color"]
                    if "text" in patch:
                        h["text"] = (patch.get("text") or "")[:2000]
                    h["updated_at"] = now_iso()
                    self.save()
                    return dict(h)
            return None

    def delete_annotation(self, book_id: str, ann_id: str) -> bool:
        with self._lock:
            b = self._book(book_id)
            before = len(b["highlights"])
            b["highlights"] = [h for h in b["highlights"] if h["id"] != ann_id]
            if len(b["highlights"]) != before:
                self.save()
                return True
            return False

    # ---------- 书签 ----------

    def add_bookmark(self, book_id: str, chapter_index: int, offset: int, text: str) -> dict:
        bm = {
            "id": uuid.uuid4().hex[:12],
            "chapter_index": max(0, int(chapter_index)),
            "offset": max(0, int(offset)),
            "text": (text or "")[:200],
            "created_at": now_iso(),
        }
        with self._lock:
            self._book(book_id)["bookmarks"].append(bm)
            self.save()
        return dict(bm)

    def delete_bookmark(self, book_id: str, bm_id: str) -> bool:
        with self._lock:
            b = self._book(book_id)
            before = len(b["bookmarks"])
            b["bookmarks"] = [x for x in b["bookmarks"] if x["id"] != bm_id]
            if len(b["bookmarks"]) != before:
                self.save()
                return True
            return False

    def remove_book(self, book_id: str) -> None:
        with self._lock:
            if book_id in self._books:
                del self._books[book_id]
                self.save()

    # ---------- 导出 ----------

    def export(self, book_id: str, fmt: str, book_title: str, chapter_title_fn) -> str:
        """导出为 markdown 或 json。chapter_title_fn(index)->str。"""
        with self._lock:
            b = self._book(book_id)
            highlights = [dict(h) for h in b["highlights"]]
            bookmarks = [dict(x) for x in b["bookmarks"]]
        if fmt == "json":
            return json.dumps(
                {"book": book_title, "highlights": highlights, "bookmarks": bookmarks},
                ensure_ascii=False,
                indent=2,
            )
        # markdown：按章分组
        lines = [f"# {book_title} 标注导出", ""]
        by_chapter: dict[int, list] = {}
        for h in highlights:
            by_chapter.setdefault(h["chapter_index"], []).append(("hl", h))
        for bm in bookmarks:
            by_chapter.setdefault(bm["chapter_index"], []).append(("bm", bm))
        for ci in sorted(by_chapter):
            lines.append(f"## {chapter_title_fn(ci)}")
            for kind, item in by_chapter[ci]:
                if kind == "hl":
                    lines.append(f"> {item['text']}")
                    if item.get("note"):
                        lines.append(f"笔记：{item['note']}")
                else:
                    lines.append(f"🔖 {item['text']}")
                lines.append("")
        return "\n".join(lines)
