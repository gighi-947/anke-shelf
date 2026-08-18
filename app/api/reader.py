"""阅读：进度上报与章节纯文本。"""
from ..domain import Position
from .common import ApiContext


def save_progress(ctx: ApiContext, book_id: str, chapter_index: int, text_offset: int) -> None:
    """JS 翻页/切章/退出时上报（text_offset 为章内 UTF-16 code unit 偏移）。

    入参类型错误由 HTTP 边界转为 400，不静默丢弃进度。
    """
    idx = max(0, int(chapter_index))
    to = max(0, int(text_offset))
    ctx.progress.set_position(book_id, Position(idx, to))
    ctx.shelf.touch(book_id, 60.0)


def get_chapter_plaintext(ctx: ApiContext, book_id: str, chapter_index: int) -> str:
    """章节折叠纯文本（差分测试/RSVP 兜底用；与 JS buildPlainText 对齐）。"""
    try:
        book = ctx.books.open(book_id)
    except KeyError:
        return ""
    raw = book.chapter_text(max(0, int(chapter_index)))
    if raw is None:
        return ""
    from ..text import extract_dom_text

    return extract_dom_text(raw)
