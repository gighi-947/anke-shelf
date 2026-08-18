"""骨碌碌公开书籍 URL 与 EPUB 来源标识解析。"""
import re
from typing import Optional
from urllib.parse import urlparse


_BOOK_PATH = re.compile(r"^/book/(\d+)/?$")
_GULULU_IDENTIFIER = re.compile(r"^gululu-([1-9]\d*)$")
# 搜索模式：从任意文本中提取骨碌碌链接（非锚定）
_GULULU_URL_SEARCH = re.compile(r"https?://(?:www\.)?gululu\.world/book/(\d+)", re.IGNORECASE)


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


def extract_book_id(text: str | int) -> int:
    """从任意文本中提取首个骨碌碌书籍 ID 或链接。

    与 [parse_book_id] 不同，本函数容忍文本中包含其他内容（如"点击链接阅读：…"）。
    多个链接命中时报 ValueError，要求用户明确选择；零命中也报 ValueError。
    """
    if isinstance(text, int):
        return parse_book_id(text)
    raw = str(text).strip()
    # 纯数字直接走 parse_book_id（含正整数校验）
    if raw.isdigit():
        return parse_book_id(raw)
    urls = list(_GULULU_URL_SEARCH.finditer(raw))
    if len(urls) > 1:
        raise ValueError("文本中包含多个骨碌碌链接，请只保留一个")
    if len(urls) == 1:
        return int(urls[0].group(1))
    raise ValueError("请输入骨碌碌书籍 ID 或 https://www.gululu.world/book/<id> 链接")


def parse_gululu_identifier(value: str) -> Optional[int]:
    """从 EPUB 的 dc:identifier 识别骨碌碌公开书籍。"""
    match = _GULULU_IDENTIFIER.fullmatch(str(value or "").strip())
    return int(match.group(1)) if match is not None else None
