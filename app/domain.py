"""轻量领域模型（B2/B5）：跨层共享的值对象与协议。

只做类型/语义收敛，不改磁盘格式与 /api/<name> 外部协议：
- Position：阅读位置（chapter_index + text_offset，UTF-16 code unit）；
- Book：BookManager 可注册书籍的统一接口（EpubBook / NativeBook 均满足）。
- BookRevision：书籍内容版本标识（热更新/缓存失效用）；
- ProgressRepository / ShelfRepository：持久化接口（未来同步/替换实现用）。
"""
import os
from dataclasses import dataclass
from typing import Optional, Protocol, runtime_checkable


@dataclass(frozen=True)
class Position:
    """阅读位置：章内折叠纯文本偏移（UTF-16 code unit，见 DATA_CONTRACT）。"""

    chapter_index: int
    text_offset: int

    def to_dict(self) -> dict:
        return {"chapter_index": self.chapter_index, "text_offset": self.text_offset}


@runtime_checkable
class Book(Protocol):
    """BookManager 可注册书籍的统一接口（EpubBook / NativeBook 均满足）。"""

    id: str
    title: str
    author: str

    def open(self) -> "Book": ...
    def close(self) -> None: ...
    def read_file(self, name: str) -> Optional[bytes]: ...
    def chapter_text(self, index: int) -> Optional[str]: ...
    def chapter_title(self, index: int) -> str: ...
    def get_cover_bytes(self) -> Optional[bytes]: ...


def book_revision(book) -> str:
    """书籍内容版本标识。

    NativeBook：`native:<tid>:<last_lou>:<updated_time>`（热更新后必变）；
    EPUB：`epub:<size>:<mtime>`（文件被替换后必变）。
    """
    meta = getattr(book, "meta", None)
    m = meta() if callable(meta) else meta
    if isinstance(m, dict) and m.get("format") == "ank-native/1":
        return "native:{}:{}:{}".format(
            m.get("tid", ""), m.get("last_lou", 0), m.get("updated_time", "")
        )
    path = getattr(book, "path", "")
    try:
        st = os.stat(path)
        return "epub:{}:{}".format(st.st_size, int(st.st_mtime))
    except OSError:
        return "epub:unknown"


@runtime_checkable
class ProgressRepository(Protocol):
    """阅读进度持久化接口（ProgressStore 满足）。"""

    def get(self, book_id: str) -> Optional[dict]: ...
    def set(self, book_id: str, chapter_index: int, text_offset: int) -> None: ...


@runtime_checkable
class ShelfRepository(Protocol):
    """书架持久化接口（Shelf 满足）。"""

    def list_books(self): ...
    def get(self, book_id: str): ...
    def upsert(self, rec) -> None: ...
    def remove(self, book_id: str) -> None: ...
