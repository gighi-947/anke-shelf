"""全文检索。"""
from .common import ApiContext, spawn_index


def search(
    ctx: ApiContext,
    book_id: str,
    query: str,
    case_sensitive: bool = False,
    whole_word: bool = False,
    per_chapter: int = 50,
) -> dict:
    """全文检索：按章限量返回命中，并附全书统计（总命中数/命中章节数）。"""
    if not ctx.search.is_ready(book_id):
        try:
            book = ctx.books.open(book_id)
        except KeyError:
            return {"ready": False, "results": [], "total_hits": 0, "hit_chapters": 0}
        # 全文索引按需构建：只有真正搜索时才建，避免打开书就吃掉大量内存。
        spawn_index(ctx, book)
        return {"ready": False, "results": [], "total_hits": 0, "hit_chapters": 0}
    data = ctx.search.search(
        book_id,
        query,
        case_sensitive=bool(case_sensitive),
        whole_word=bool(whole_word),
        per_chapter=max(1, int(per_chapter)),
    )
    if data is None:
        return {"ready": False, "results": [], "total_hits": 0, "hit_chapters": 0}
    return {"ready": True, **data}


def search_more(
    ctx: ApiContext,
    book_id: str,
    query: str,
    chapter_index: int,
    after_offset: int,
    case_sensitive: bool = False,
    whole_word: bool = False,
    per_chapter: int = 50,
) -> dict:
    """在指定章节续取更多命中（“加载更多”用）。"""
    if not ctx.search.is_ready(book_id):
        return {"hits": [], "more": False}
    data = ctx.search.search_more(
        book_id,
        query,
        int(chapter_index),
        int(after_offset),
        case_sensitive=bool(case_sensitive),
        whole_word=bool(whole_word),
        per_chapter=max(1, int(per_chapter)),
    )
    if data is None:
        return {"hits": [], "more": False}
    return data


def is_index_ready(ctx: ApiContext, book_id: str) -> bool:
    return ctx.search.is_ready(book_id)
