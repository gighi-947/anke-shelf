"""轻量领域模型（B2）：跨层共享的值对象与协议。

只做类型/语义收敛，不改磁盘格式与 /api/<name> 外部协议：
- Position：阅读位置（chapter_index + text_offset，UTF-16 code unit）；
- Book：BookManager 可注册书籍的统一接口（EpubBook / NativeBook 均满足）。
"""
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
