"""HTTP API 服务层 —— 前后端分离后 JS 侧的唯一调用入口。

所有方法由本地 HTTP 服务器 /api/<name> 路由分发：
- 方法名即接口名，入参为位置参数列表 args，返回 JSON 可序列化数据
- 文件对话框由 dialogs 模块在服务端触发（原生对话框）
- 耗时操作（解析、索引构建）放后台线程，不阻塞 HTTP 线程
"""
import logging
import threading
from pathlib import Path
from typing import Callable, Optional

from .book_manager import BookManager
from . import dialogs
from .epub import EpubError
from .export_service import ExportService
from .nga_config import clear_nga_config, load_nga_config, save_nga_config
from .nga_service import NgaService
from .paths import file_mtime
from .search import SearchService
from .settings import Settings
from .shelf import BookRecord, ProgressStore, Shelf

from . import __version__


def _progress_pct(progress: Optional[dict], chapter_count: int, chapter_len: Optional[int] = None) -> float:
    """进度 → 百分比：(章节索引 + 章内比例) / 总章节数。

    text_offset / chapter_len 得章内比例；旧 scroll_ratio 或索引未就绪时退化。
    """
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


class Api:
    """本地 HTTP API 服务。"""

    def __init__(
        self,
        books: BookManager,
        shelf: Shelf,
        progress: ProgressStore,
        settings: Settings,
        search: SearchService,
        annotations: Optional["AnnotationStore"] = None,
        stats: Optional["StatsStore"] = None,
        nga_service: Optional[NgaService] = None,
        export_service: Optional[ExportService] = None,
        frontend_ready: Optional[threading.Event] = None,
        file_dialog: Optional[Callable[[str], list[str]]] = None,
        window_toggle: Optional[Callable[[], None]] = None,
    ):
        self._books = books
        self._shelf = shelf
        self._progress = progress
        self._settings = settings
        self._search = search
        self._annotations = annotations
        self._stats = stats
        self._nga = nga_service
        self._export = export_service
        self._frontend_ready = frontend_ready
        self._file_dialog = file_dialog
        self._window_toggle = window_toggle

    def _pick_paths(self, kind: str) -> list[str]:
        if self._file_dialog is not None:
            return self._file_dialog(kind) or []
        return dialogs.pick_paths(kind)

    def on_frontend_ready(self) -> None:
        """前端初始化完成后由 JS 调用；主程序据此显示隐藏中的窗口。"""
        if self._frontend_ready is not None:
            self._frontend_ready.set()

    def toggle_fullscreen(self) -> dict:
        """沉浸式阅读：切换宿主窗口全屏。"""
        if self._window_toggle is None:
            return {"ok": False, "error": "全屏控制不可用"}
        try:
            self._window_toggle()
            self._fullscreen = not getattr(self, "_fullscreen", False)
            return {"ok": True}
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": str(e)}

    def log_frontend(self, message: str) -> None:
        """前端把启动阶段的关键节点写进启动日志，便于定位卡死/慢启动。"""
        logging.getLogger("app.frontend").info("JS: %s", message)

    def get_version(self) -> str:
        """当前应用版本号（设置页展示用）。"""
        return __version__

    # ---------- 书架 ----------

    def get_shelf(self) -> list[dict]:
        out = []
        for rec in self._shelf.list_books():
            d = self._record_to_dict(rec)
            p = self._progress.get(rec.id)
            clen = self._search.chapter_len(rec.id, p.get("chapter_index", 0)) if p else None
            d["progress_pct"] = _progress_pct(p, rec.chapter_count, clen)
            out.append(d)
        return out

    def import_books(self) -> list[dict]:
        """服务端弹出文件对话框，逐本导入。返回每本的结果列表。"""
        paths = self._pick_paths("epub")
        if not paths:  # 用户取消
            return []
        results = []
        for p in paths:
            try:
                book = self._books.register(str(p))
                rec = BookRecord(
                    id=book.id,
                    path=book.path,
                    title=book.title,
                    author=book.author,
                    language=book.language,
                    chapter_count=len(book.chapters),
                    file_size=_file_size(p),
                    file_mtime=file_mtime(p),
                    cover_rel=self._shelf.extract_cover(book),
                )
                self._shelf.upsert(rec)
                self._shelf.save()
                results.append({"ok": True, "record": self._record_to_dict(rec)})
            except EpubError as e:
                results.append({"ok": False, "file": Path(p).name, "error": str(e)})
            except OSError as e:
                results.append({"ok": False, "file": Path(p).name, "error": str(e)})
        return results

    def remove_book(self, book_id: str) -> bool:
        self._books.close(book_id)
        self._shelf.remove(book_id)
        self._shelf.save()
        self._progress.remove(book_id)
        self._search.drop(book_id)
        if self._annotations is not None:
            self._annotations.remove_book(book_id)
        return True

    # ---------- 标注（高亮/书签/笔记） ----------

    def get_annotations(self, book_id: str) -> dict:
        if self._annotations is None:
            return {"highlights": [], "bookmarks": []}
        return self._annotations.get_all(book_id)

    def save_annotation(
        self,
        book_id: str,
        chapter_index: int,
        start_offset: int,
        end_offset: int,
        text: str,
        color: str = "yellow",
        note: str = "",
    ) -> dict:
        if self._annotations is None:
            return {"error": "标注服务不可用"}
        try:
            return self._annotations.add_highlight(
                book_id, chapter_index, start_offset, end_offset, text, color, note
            )
        except ValueError as e:
            return {"error": str(e)}

    def update_annotation(self, book_id: str, ann_id: str, patch: dict) -> dict:
        if self._annotations is None:
            return {"error": "标注服务不可用"}
        r = self._annotations.update_annotation(book_id, ann_id, patch or {})
        return r if r else {"error": "标注不存在"}

    def delete_annotation(self, book_id: str, ann_id: str) -> bool:
        if self._annotations is None:
            return False
        return self._annotations.delete_annotation(book_id, ann_id)

    def add_bookmark(self, book_id: str, chapter_index: int, offset: int, text: str) -> dict:
        if self._annotations is None:
            return {"error": "标注服务不可用"}
        return self._annotations.add_bookmark(book_id, chapter_index, offset, text)

    def delete_bookmark(self, book_id: str, bm_id: str) -> bool:
        if self._annotations is None:
            return False
        return self._annotations.delete_bookmark(book_id, bm_id)

    def export_annotations(self, book_id: str, fmt: str = "markdown") -> str:
        if self._annotations is None:
            return ""
        try:
            book = self._books.open(book_id)
        except KeyError:
            return ""
        return self._annotations.export(book_id, fmt, book.title, book.chapter_title)

    # ---------- 阅读统计 ----------

    def record_reading(self, book_id: str, seconds: int, pages_flipped: int = 0) -> None:
        if self._stats is not None:
            self._stats.record_reading(book_id, seconds, pages_flipped)

    def get_stats(self, book_id: Optional[str] = None) -> dict:
        if self._stats is None:
            return {"book": {}, "global": {}}
        if book_id:
            return {"book": self._stats.get_book(book_id), "global": self._stats.get_global()}
        books = []
        if self._shelf is not None:
            for rec in self._shelf.list_books():
                st = self._stats.get_book(rec.id)
                if not st.get("total_seconds") and not st.get("sessions"):
                    continue
                books.append({
                    "id": rec.id,
                    "title": rec.title,
                    "author": rec.author,
                    "stats": st,
                })
        return {"books": books, "global": self._stats.get_global()}

    # ---------- 阅读 ----------

    def open_book(self, book_id: str) -> dict:
        try:
            book = self._books.open(book_id)
        except KeyError:
            # 重启后 BookManager 为空：按书架记录里的原路径重新注册。
            rec = self._shelf.get(book_id)
            if rec is None:
                return {"error": "书籍未加载，请重新导入"}
            try:
                book = self._books.register(rec.path)
            except (EpubError, OSError) as e:
                return {"error": f"书籍文件无法读取：{e}"}

        # mtime 变化 → 重解析（用户可能原地替换了文件）
        rec = self._shelf.get(book_id)
        if rec is not None:
            cur_mtime = file_mtime(book.path)
            if cur_mtime and rec.file_mtime != cur_mtime:
                try:
                    self._books.close(book_id)
                    book = self._books.register(book.path)
                    rec.chapter_count = len(book.chapters)
                    rec.file_mtime = cur_mtime
                    rec.file_size = _file_size(book.path)
                    rec.cover_rel = self._shelf.extract_cover(book)
                    rec.title = book.title
                    rec.author = book.author
                    rec.language = book.language
                    self._shelf.upsert(rec)
                    self._shelf.save()
                except EpubError:
                    pass  # 解析失败则沿用旧记录
            rec = self._shelf.get(book_id)

        progress = self._progress.get(book_id)
        if progress:
            idx = max(0, int(progress.get("chapter_index", 0)))
            progress = ProgressStore.migrate(progress, self._search.chapter_len(book_id, idx))
        else:
            progress = {"chapter_index": 0, "text_offset": 0}
        self._settings.update({"last_open_book": book_id})
        chapters = [
            {"index": c.index, "href": c.href, "title": book.chapter_title(c.index)}
            for c in book.chapters
        ]
        return {
            "id": book_id,
            "title": book.title,
            "author": book.author,
            "nga": bool(rec and rec.nga_tid),
            "chapters": chapters,
            "toc": self._toc_to_dict(book),
            "progress": progress,
            "cover_url": f"/cover/{book_id}",
        }

    # ---------- NGA 下载 ----------

    def nga_get_config(self) -> dict:
        return load_nga_config()

    def nga_save_config(self, patch: dict) -> dict:
        return save_nga_config(patch or {})

    def nga_clear_config(self) -> dict:
        """清除已保存的 NGA 登录配置（Cookie/UA），重置为占位模板。"""
        return clear_nga_config()

    def nga_update_book(self, book_id: str, params: dict) -> dict:
        """对已下载的 NGA 帖子做增量热更新。"""
        if self._nga is None:
            return {"ok": False, "error": "NGA 下载服务不可用"}
        return self._nga.update_book(book_id, params or {})

    def nga_update_defaults(self, book_id: str) -> dict:
        """返回热更新表单的默认参数（最近一次下载/更新设置）。"""
        if self._nga is None:
            return {"ok": False, "error": "NGA 下载服务不可用"}
        return self._nga.update_defaults(book_id)

    def export_start(self, book_id: str, fmt: str = "both") -> dict:
        """把 NGA 下载的帖子导出为用户自选格式（epub/md/both）+ 自选文件夹。"""
        if self._export is None:
            return {"ok": False, "error": "导出服务不可用"}
        return self._export.start(book_id, fmt)

    def export_status(self) -> dict:
        if self._export is None:
            return {"running": False, "stage": "idle", "detail": "", "files": [], "dest": "", "error": ""}
        return self._export.status()

    def export_open_dest(self) -> dict:
        if self._export is None:
            return {"ok": False, "error": "导出服务不可用"}
        return self._export.open_dest()

    def nga_start_download(self, params: dict) -> dict:
        if self._nga is None:
            return {"ok": False, "error": "NGA 下载服务不可用"}
        return self._nga.start(params or {})

    def nga_download_status(self) -> dict:
        if self._nga is None:
            return {"running": False, "stage": "idle", "detail": ""}
        return self._nga.status()

    def nga_cancel(self) -> None:
        if self._nga is not None:
            self._nga.cancel()

    def save_progress(self, book_id: str, chapter_index: int, text_offset: int) -> None:
        """JS 翻页/切章/退出时上报（text_offset 为章内纯文本字符偏移）。"""
        try:
            idx = max(0, int(chapter_index))
            to = max(0, int(text_offset))
        except (TypeError, ValueError):
            return
        self._progress.set(book_id, idx, to)
        self._shelf.touch(book_id, 60.0)

    def get_chapter_plaintext(self, book_id: str, chapter_index: int) -> str:
        """章节折叠纯文本（差分测试/RSVP 兜底用；与 JS buildPlainText 对齐）。"""
        try:
            book = self._books.open(book_id)
        except KeyError:
            return ""
        raw = book.chapter_text(max(0, int(chapter_index)))
        if raw is None:
            return ""
        from .text import extract_dom_text

        return extract_dom_text(raw)

    # ---------- 搜索 ----------

    def search(
        self,
        book_id: str,
        query: str,
        case_sensitive: bool = False,
        whole_word: bool = False,
        per_chapter: int = 50,
    ) -> dict:
        """全文检索：按章限量返回命中，并附全书统计（总命中数/命中章节数）。"""
        if not self._search.is_ready(book_id):
            try:
                book = self._books.open(book_id)
            except KeyError:
                return {"ready": False, "results": [], "total_hits": 0, "hit_chapters": 0}
            # 全文索引按需构建：只有真正搜索时才建，避免打开书就吃掉大量内存。
            self._spawn_index(book)
            return {"ready": False, "results": [], "total_hits": 0, "hit_chapters": 0}
        data = self._search.search(
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
        self,
        book_id: str,
        query: str,
        chapter_index: int,
        after_offset: int,
        case_sensitive: bool = False,
        whole_word: bool = False,
        per_chapter: int = 50,
    ) -> dict:
        """在指定章节续取更多命中（“加载更多”用）。"""
        if not self._search.is_ready(book_id):
            return {"hits": [], "more": False}
        data = self._search.search_more(
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

    def is_index_ready(self, book_id: str) -> bool:
        return self._search.is_ready(book_id)

    # ---------- 设置 ----------

    def get_fonts(self) -> dict:
        from .fonts import list_fonts

        return {
            "fonts": list_fonts(),
            "global_font": self._settings.get("custom_font") or "",
            "book_fonts": self._settings.get("book_fonts") or {},
        }

    def pick_font_file(self) -> dict:
        paths = self._pick_paths("font")
        if not paths:
            return {}
        from .fonts import register_font

        try:
            return register_font(paths[0])
        except Exception as e:  # noqa: BLE001
            return {"error": str(e)}

    def get_settings(self) -> dict:
        return self._settings.get_all()

    def save_settings(self, patch: dict) -> None:
        self._settings.update(patch or {})

    # ---------- 数据目录 / 卸载 ----------

    def open_data_dir(self) -> dict:
        """在资源管理器中打开用户数据目录。"""
        import os

        from .paths import data_dir

        try:
            os.startfile(str(data_dir()))
            return {"ok": True}
        except OSError as e:
            return {"ok": False, "error": str(e)}

    def uninstall_and_quit(self) -> dict:
        """清除全部用户数据后退出程序（卸载流程的一部分）。"""
        import os
        import shutil
        import subprocess
        import time

        from .instance_guard import release_instance_lock
        from .paths import data_dir

        self._books.close_all()
        release_instance_lock()
        target = data_dir()
        trash = target.with_name(target.name + f".trash-{int(time.time())}")
        try:
            if target.exists():
                shutil.move(str(target), str(trash))
        except OSError:
            trash = target
        # 进程退出、文件句柄释放后，由分离的 PowerShell 清理改名后的目录。
        script = (
            "Start-Sleep -Seconds 2; "
            f"Remove-Item -LiteralPath '{trash}' -Recurse -Force -ErrorAction SilentlyContinue"
        )
        try:
            subprocess.Popen(
                ["powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script],
                creationflags=subprocess.CREATE_NO_WINDOW,
            )
        except Exception:  # noqa: BLE001
            pass
        os._exit(0)
        return {"ok": True}

    # ---------- 内部 ----------

    def _record_to_dict(self, rec: BookRecord) -> dict:
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
            "cover_url": f"/cover/{rec.id}",
            "progress_pct": 0.0,
        }

    def _toc_to_dict(self, book) -> list[dict]:
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

    def _spawn_index(self, book) -> None:
        def run() -> None:
            try:
                self._search.ensure_index(book)
            except Exception:
                pass

        threading.Thread(target=run, daemon=True).start()


def _file_size(path: str) -> int:
    try:
        return Path(path).stat().st_size
    except OSError:
        return 0
