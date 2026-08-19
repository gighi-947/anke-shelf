"""书架与阅读进度持久化（JSON，原子写）。

- shelf.json：书籍元数据（导入时生成，mtime 变化时重解析更新）
- progress.json：阅读进度（独立文件 —— 呼应 Readest 把进度与书架
  分开存储的取舍：高频进度写入不重写书架元数据）

两者均用「临时文件 + os.replace」原子写，线程锁防并发。
"""
import json
import threading
import time
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Optional

from .domain import Position
from .epub import EpubBook
from .storage import atomic_write_json, load_json_file, now_iso


@dataclass
class BookRecord:
    """书架中的一本书。"""

    id: str
    path: str
    title: str
    author: str = ""
    language: str = ""
    chapter_count: int = 0
    cover_rel: Optional[str] = None  # 相对数据目录的封面缓存路径
    file_size: int = 0
    file_mtime: str = ""
    added_at: str = ""
    last_read_at: str = ""
    progress_pct: float = 0.0  # 运行时填充（读取时合成），不落盘
    nga_tid: int = 0           # >0 表示该书记录为 NGA 帖子下载


def _record_from_dict(d: dict) -> BookRecord:
    return BookRecord(
        id=d.get("id", ""),
        path=d.get("path", ""),
        title=d.get("title", ""),
        author=d.get("author", ""),
        language=d.get("language", ""),
        chapter_count=d.get("chapter_count", 0),
        cover_rel=d.get("cover_rel"),
        file_size=d.get("file_size", 0),
        file_mtime=d.get("file_mtime", ""),
        added_at=d.get("added_at", ""),
        last_read_at=d.get("last_read_at", ""),
        nga_tid=int(d.get("nga_tid", 0) or 0),
    )


class Shelf:
    """书籍元数据存储。"""

    def __init__(self, shelf_file: Path, covers_dir: Path):
        self._file = shelf_file
        self._covers_dir = covers_dir
        self._books: dict[str, BookRecord] = {}
        self._lock = threading.RLock()
        self._write_lock = threading.Lock()  # 串行化落盘（下载线程/导入线程可能并发）

    def load(self) -> None:
        data = load_json_file(self._file)
        with self._lock:
            if data:
                self._books = {
                    r.id: r for r in (_record_from_dict(d) for d in data.get("books", []))
                }
            else:
                self._books = {}

    def save(self) -> None:
        with self._lock:
            data = {"version": 1, "books": [asdict(r) for r in self._books.values()]}
        with self._write_lock:
            atomic_write_json(self._file, data)

    def list_books(self) -> list[BookRecord]:
        """按最近阅读时间降序。"""
        with self._lock:
            books = list(self._books.values())
        books.sort(key=lambda r: r.last_read_at or "", reverse=True)
        return books

    def get(self, book_id: str) -> Optional[BookRecord]:
        with self._lock:
            return self._books.get(book_id)

    def upsert(self, rec: BookRecord) -> None:
        """导入或 mtime 变化后重解析更新。mtime 未变时保留 last_read_at。"""
        with self._lock:
            old = self._books.get(rec.id)
            if old is not None and old.file_mtime == rec.file_mtime:
                rec.last_read_at = old.last_read_at
                rec.added_at = old.added_at
            if not rec.added_at:
                rec.added_at = now_iso()
            self._books[rec.id] = rec

    def remove(self, book_id: str) -> None:
        with self._lock:
            rec = self._books.pop(book_id, None)
        if rec is not None and rec.cover_rel:
            try:
                (self._covers_dir / Path(rec.cover_rel).name).unlink(missing_ok=True)
            except OSError:
                pass

    def touch(self, book_id: str, throttle: float = 60.0) -> None:
        """更新 last_read_at 并落盘，60s 节流（高频进度写入不频繁写盘）。"""
        now = now_iso()
        with self._lock:
            rec = self._books.get(book_id)
            if rec is None:
                return
            last = rec.last_read_at or ""
            if last:
                try:
                    dt = datetime.fromisoformat(last)
                    if (time.time() - dt.timestamp()) < throttle:
                        return
                except (ValueError, OSError):
                    pass
            rec.last_read_at = now
            self.save()

    def extract_cover(self, book: EpubBook) -> Optional[str]:
        """把书籍封面提取为 covers/<id>.<ext> 缓存文件，返回相对路径。"""
        data = book.get_cover_bytes()
        if not data:
            return None
        ext = _sniff_image_ext(data)
        rel = f"covers/{book.id}.{ext}"
        try:
            (self._covers_dir / f"{book.id}.{ext}").write_bytes(data)
            return rel
        except OSError:
            return None

    def set_custom_cover(self, book_id: str, source: Path) -> Optional[str]:
        """把用户选择的图片复制为封面缓存，更新 cover_rel 并落盘。"""
        data = source.read_bytes()
        if not data:
            raise OSError("所选封面文件为空")
        ext = _sniff_image_ext(data)
        rel = f"covers/{book_id}.{ext}"
        (self._covers_dir / f"{book_id}.{ext}").write_bytes(data)
        with self._lock:
            rec = self._books.get(book_id)
            if rec is None:
                return None
            rec.cover_rel = rel
            self.save()
        return rel

    def reset_cover(self, book_id: str) -> bool:
        """删除自定义封面，恢复为无封面（cover_rel=None）。"""
        with self._lock:
            rec = self._books.get(book_id)
            if rec is None:
                return False
            old = rec.cover_rel
            rec.cover_rel = None
            if old:
                try:
                    (self._covers_dir / Path(old).name).unlink(missing_ok=True)
                except OSError:
                    pass
            self.save()
            return True


def _sniff_image_ext(data: bytes) -> str:
    """按魔数推断图片扩展名。"""
    if data.startswith(b"\x89PNG"):
        return "png"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if data.startswith(b"GIF8"):
        return "gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "webp"
    if data.startswith(b"<svg"):
        return "svg"
    return "jpg"  # 未知按 jpg 存（浏览器可嗅探显示）


class ProgressStore:
    """阅读进度：{book_id: {chapter_index, text_offset, updated_at}}。

    text_offset = 章节内折叠纯文本字符偏移（见 app/text.py），与布局解耦。
    v2 迁移：旧 scroll_ratio 记录保留，open_book 时按章纯文本长惰性换算。
    """

    def __init__(self, progress_file: Path):
        self._file = progress_file
        self._lock = threading.RLock()
        self._data: dict = {}

    def load(self) -> None:
        data = load_json_file(self._file)
        self._data = data.get("progress", {}) if data else {}

    def save(self) -> None:
        with self._lock:
            atomic_write_json(self._file, {"version": 2, "progress": self._data})

    def get(self, book_id: str) -> Optional[dict]:
        with self._lock:
            p = self._data.get(book_id)
            return dict(p) if p else None

    def set(self, book_id: str, chapter_index: int, text_offset: int) -> None:
        text_offset = max(0, int(text_offset))
        with self._lock:
            self._data[book_id] = {
                "chapter_index": max(0, int(chapter_index)),
                "text_offset": text_offset,
                "updated_at": now_iso(),
            }
        self.save()

    def position(self, book_id: str) -> Optional[Position]:
        """最近进度 → Position（无记录返回 None）。"""
        p = self.get(book_id)
        if not p:
            return None
        return Position(
            chapter_index=int(p.get("chapter_index", 0)),
            text_offset=int(p.get("text_offset", 0)),
        )

    def set_position(self, book_id: str, pos: Position) -> None:
        """以 Position 写入进度（等价 set(chapter_index, text_offset)）。"""
        self.set(book_id, pos.chapter_index, pos.text_offset)

    def remove(self, book_id: str) -> None:
        with self._lock:
            if book_id in self._data:
                del self._data[book_id]
                self.save()

    @staticmethod
    def migrate(old: dict, chapter_len: Optional[int]) -> dict:
        """旧 scroll_ratio 记录 → text_offset。chapter_len 为章纯文本长。"""
        if "text_offset" in old:
            return dict(old)
        ratio = old.get("scroll_ratio", 0.0)
        if chapter_len:
            text_offset = round(max(0.0, min(1.0, ratio)) * chapter_len)
        else:
            text_offset = 0
        out = dict(old)
        out.pop("scroll_ratio", None)
        out["text_offset"] = text_offset
        return out
