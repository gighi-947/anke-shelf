"""书架与书籍：列表 / 导入 / 删除 / 打开。"""
import logging
from pathlib import Path

from ..epub import EpubError
from ..errors import ErrorCode, api_error
from ..gululu_source import parse_gululu_identifier
from ..paths import file_mtime
from ..shelf import BookRecord, ProgressStore
from .common import (
    ApiContext,
    file_size,
    pick_paths,
    progress_pct,
    record_to_dict,
    toc_to_dict,
)

log = logging.getLogger("app.api.library")


def get_shelf(ctx: ApiContext) -> list[dict]:
    out = []
    for rec in ctx.shelf.list_books():
        d = record_to_dict(rec)
        p = ctx.progress.get(rec.id)
        clen = ctx.search.chapter_len(rec.id, p.get("chapter_index", 0)) if p else None
        d["progress_pct"] = progress_pct(p, rec.chapter_count, clen)
        out.append(d)
    return out


def import_books(ctx: ApiContext) -> list[dict]:
    """服务端弹出文件对话框，逐本导入。返回每本的结果列表。"""
    paths = pick_paths(ctx, "epub")
    if not paths:  # 用户取消
        return []
    results = []
    for p in paths:
        try:
            book = ctx.books.register(str(p))
            rec = BookRecord(
                id=book.id,
                path=book.path,
                title=book.title,
                author=book.author,
                language=book.language,
                chapter_count=len(book.chapters),
                file_size=file_size(p),
                file_mtime=file_mtime(p),
                cover_rel=ctx.shelf.extract_cover(book),
            )
            ctx.shelf.upsert(rec)
            ctx.shelf.save()
            d = record_to_dict(rec)
            d["progress_pct"] = 0.0  # 刚导入的书尚无进度（最终值，非占位）
            results.append({"ok": True, "record": d})
        except EpubError as e:
            results.append({"ok": False, "file": Path(p).name, "error": str(e)})
        except OSError as e:
            results.append({"ok": False, "file": Path(p).name, "error": str(e)})
    return results


def remove_book(ctx: ApiContext, book_id: str) -> bool:
    ctx.books.close(book_id)
    ctx.shelf.remove(book_id)
    ctx.shelf.save()
    ctx.progress.remove(book_id)
    ctx.search.drop(book_id)
    if ctx.annotations is not None:
        ctx.annotations.remove_book(book_id)
    return True


def open_book(ctx: ApiContext, book_id: str) -> dict:
    try:
        book = ctx.books.open(book_id)
    except KeyError:
        # 重启后 BookManager 为空：按书架记录里的原路径重新注册。
        rec = ctx.shelf.get(book_id)
        if rec is None:
            return api_error(ErrorCode.BOOK_NOT_FOUND, "书籍未加载，请重新导入")
        try:
            book = ctx.books.register(rec.path)
        except (EpubError, OSError) as e:
            return api_error(ErrorCode.BOOK_INVALID, f"书籍文件无法读取：{e}")

    # mtime 变化 → 重解析（用户可能原地替换了文件）
    rec = ctx.shelf.get(book_id)
    if rec is not None:
        cur_mtime = file_mtime(book.path)
        if cur_mtime and rec.file_mtime != cur_mtime:
            try:
                ctx.books.close(book_id)
                book = ctx.books.register(book.path)
                rec.chapter_count = len(book.chapters)
                rec.file_mtime = cur_mtime
                rec.file_size = file_size(book.path)
                rec.cover_rel = ctx.shelf.extract_cover(book)
                rec.title = book.title
                rec.author = book.author
                rec.language = book.language
                ctx.shelf.upsert(rec)
                ctx.shelf.save()
            except EpubError as e:
                log.warning("书籍重新解析失败，沿用旧记录：%s", e)
        rec = ctx.shelf.get(book_id)

    progress = ctx.progress.get(book_id)
    if progress:
        idx = max(0, int(progress.get("chapter_index", 0)))
        progress = ProgressStore.migrate(progress, ctx.search.chapter_len(book_id, idx))
    else:
        progress = {"chapter_index": 0, "text_offset": 0}
    ctx.settings.update({"last_open_book": book_id})
    chapters = [
        {"index": c.index, "href": c.href, "title": book.chapter_title(c.index)}
        for c in book.chapters
    ]
    gululu_source_id = parse_gululu_identifier(getattr(book, "identifier", ""))
    return {
        "id": book_id,
        "title": book.title,
        "author": book.author,
        "nga": bool(rec and rec.nga_tid),
        "gululu": gululu_source_id is not None,
        "gululu_source_id": gululu_source_id or 0,
        "chapters": chapters,
        "toc": toc_to_dict(book),
        "progress": progress,
        "cover_url": f"/cover/{book_id}",
    }
