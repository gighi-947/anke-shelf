"""api 包共享：上下文对象与纯辅助函数。"""
import logging
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Callable, Optional

from .. import dialogs
from ..book_manager import BookManager
from ..export_service import ExportService
from ..nga_service import NgaService
from ..search import SearchService
from ..settings import Settings
from ..shelf import BookRecord, ProgressStore, Shelf

if TYPE_CHECKING:
    from ..annotations import AnnotationStore
    from ..gululu_service import GululuService
    from ..nga_login import NgaLoginController
    from ..stats import StatsStore

log = logging.getLogger("app.api")


@dataclass
class ApiContext:
    """Api 构造时的服务依赖集合（只读装配，handler 通过它访问服务）。

    服务依赖一律必填：生产装配与测试/调试入口都完整接线，缺接线属于
    装配 bug，应在构造处暴露，而不是在 handler 里折叠成“服务不可用”
    的假数据（读接口返回 idle/空、写接口 503 的旧分裂行为已删除）。
    仅环境相关的可选项允许缺省：file_dialog（None → 系统 tkinter 对话框）。
    """

    books: BookManager
    shelf: Shelf
    progress: ProgressStore
    settings: Settings
    search: SearchService
    annotations: "AnnotationStore"
    stats: "StatsStore"
    nga_service: NgaService
    export_service: ExportService
    gululu_service: "GululuService"
    frontend_ready: threading.Event
    window_toggle: Callable[[bool], None]
    nga_login: "NgaLoginController"
    file_dialog: Optional[Callable[[str], list[str]]] = None
    fullscreen: bool = False


def bind(ctx: ApiContext, fn: Callable) -> Callable:
    """把 handler 绑定到上下文：/api/<name> 的 *args/**kwargs 原样透传。"""
    return lambda *args, **kwargs: fn(ctx, *args, **kwargs)


def pick_paths(ctx: ApiContext, kind: str) -> list[str]:
    if ctx.file_dialog is not None:
        return ctx.file_dialog(kind) or []
    return dialogs.pick_paths(kind)


def progress_pct(progress: Optional[dict], chapter_count: int, chapter_len: Optional[int] = None) -> float:
    """进度 → 百分比：(章节索引 + 章内比例) / 总章节数。"""
    if not progress or chapter_count <= 0:
        return 0.0
    idx = max(0, min(int(progress.get("chapter_index", 0)), chapter_count - 1))
    to = progress.get("text_offset")
    if to is None:
        ratio = float(progress.get("scroll_ratio", 0.0))
    elif chapter_len:
        ratio = to / chapter_len
    else:
        ratio = 0.0
    ratio = max(0.0, min(1.0, ratio))
    return round((idx + ratio) / chapter_count, 4)


def record_to_dict(rec: BookRecord) -> dict:
    return {
        "id": rec.id,
        "path": rec.path,
        "title": rec.title,
        "author": rec.author,
        "language": rec.language,
        "chapter_count": rec.chapter_count,
        "file_size": rec.file_size,
        "added_at": rec.added_at,
        "last_read_at": rec.last_read_at,
        "nga_tid": rec.nga_tid,
        "tags": rec.tags,
        "cover_rel": rec.cover_rel,
        "cover_url": f"/cover/{rec.id}",
    }


def toc_to_dict(book) -> list[dict]:
    def walk(entries):
        out = []
        for e in entries:
            out.append(
                {
                    "label": e.label,
                    "href": e.href,
                    "spine_index": book.toc_spine_index(e.href),
                    "children": walk(e.children),
                }
            )
        return out

    return walk(book.toc)


def file_size(path: str) -> int:
    try:
        return Path(path).stat().st_size
    except OSError:
        return 0


def spawn_index(ctx: ApiContext, book) -> None:
    """后台线程构建全文索引（索引未就绪时首次搜索触发）。"""
    def run() -> None:
        try:
            ctx.search.ensure_index(book)
        except Exception:
            log.exception("后台全文索引构建失败")

    threading.Thread(target=run, daemon=True).start()
