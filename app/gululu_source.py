"""骨碌碌公开书籍 URL 与 EPUB 来源标识解析。"""
import re
from typing import Optional
from urllib.parse import urlparse


_BOOK_PATH = re.compile(r"^/book/(\d+)/?$")
_GULULU_IDENTIFIER = re.compile(r"^gululu-([1-9]\d*)$")


def parse_book_id(value: str | int) -> int:
    """接受正整数 bookId 或 gululu.world/book/<id> 公共链接。"""
    if isinstance(value, int):
        if value > 0:
            return value
        raise ValueError("骨碌碌书籍 ID 必须为正整数")
    raw = str(value).strip()
    if raw.isdigit() and int(raw) > 0:
        return int(raw)
    parsed = urlparse(raw)
    if parsed.scheme != "https" or parsed.hostname not in {"gululu.world", "www.gululu.world"}:
        raise ValueError("请输入骨碌碌书籍 ID 或 https://www.gululu.world/book/<id> 链接")
    match = _BOOK_PATH.fullmatch(parsed.path)
    if match is None:
        raise ValueError("无法从链接中识别骨碌碌书籍 ID")
    return int(match.group(1))


def parse_gululu_identifier(value: str) -> Optional[int]:
    """从 EPUB 的 dc:identifier 识别骨碌碌公开书籍。"""
    match = _GULULU_IDENTIFIER.fullmatch(str(value or "").strip())
    return int(match.group(1)) if match is not None else None
