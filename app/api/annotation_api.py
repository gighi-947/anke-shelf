"""标注：高亮 / 书签 / 笔记 / 导出。"""
from .common import ApiContext


def get_annotations(ctx: ApiContext, book_id: str) -> dict:
    if ctx.annotations is None:
        return {"highlights": [], "bookmarks": []}
    return ctx.annotations.get_all(book_id)


def save_annotation(
    ctx: ApiContext,
    book_id: str,
    chapter_index: int,
    start_offset: int,
    end_offset: int,
    text: str,
    color: str = "yellow",
    note: str = "",
) -> dict:
    if ctx.annotations is None:
        return {"error": "标注服务不可用"}
    try:
        return ctx.annotations.add_highlight(
            book_id, chapter_index, start_offset, end_offset, text, color, note
        )
    except ValueError as e:
        return {"error": str(e)}


def update_annotation(ctx: ApiContext, book_id: str, ann_id: str, patch: dict) -> dict:
    if ctx.annotations is None:
        return {"error": "标注服务不可用"}
    r = ctx.annotations.update_annotation(book_id, ann_id, patch or {})
    return r if r else {"error": "标注不存在"}


def delete_annotation(ctx: ApiContext, book_id: str, ann_id: str) -> bool:
    if ctx.annotations is None:
        return False
    return ctx.annotations.delete_annotation(book_id, ann_id)


def add_bookmark(ctx: ApiContext, book_id: str, chapter_index: int, offset: int, text: str) -> dict:
    if ctx.annotations is None:
        return {"error": "标注服务不可用"}
    return ctx.annotations.add_bookmark(book_id, chapter_index, offset, text)


def delete_bookmark(ctx: ApiContext, book_id: str, bm_id: str) -> bool:
    if ctx.annotations is None:
        return False
    return ctx.annotations.delete_bookmark(book_id, bm_id)


def export_annotations(ctx: ApiContext, book_id: str, fmt: str = "markdown") -> str:
    if ctx.annotations is None:
        return ""
    try:
        book = ctx.books.open(book_id)
    except KeyError:
        return ""
    return ctx.annotations.export(book_id, fmt, book.title, book.chapter_title)
