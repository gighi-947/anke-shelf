"""阅读统计。"""
from typing import Optional

from .common import ApiContext


def record_reading(ctx: ApiContext, book_id: str, seconds: int, pages_flipped: int = 0) -> None:
    ctx.stats.record_reading(book_id, seconds, pages_flipped)


def get_stats(ctx: ApiContext, book_id: Optional[str] = None) -> dict:
    if book_id:
        return {"book": ctx.stats.get_book(book_id), "global": ctx.stats.get_global()}
    books = []
    for rec in ctx.shelf.list_books():
        st = ctx.stats.get_book(rec.id)
        if not st.get("total_seconds") and not st.get("sessions"):
            continue
        books.append({
            "id": rec.id,
            "title": rec.title,
            "author": rec.author,
            "stats": st,
        })
    return {"books": books, "global": ctx.stats.get_global()}
