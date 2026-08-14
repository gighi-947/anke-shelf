"""NGA 帖子下载服务：对接 ngapost2md-python 包，提供 UI 进度/取消/单飞。"""
import logging
import re
import threading
import json
import hashlib
from pathlib import Path
from typing import Callable, Optional

from .events import bus
from .logutil import log_event
from .nga_config import ensure_nga_config, load_nga_config
from .native_book import (
    append_container,
    is_native_dir,
    load_meta,
    native_dir_for,
    write_container,
)
from .paths import dir_mtime, nga_library_dir

log = logging.getLogger("nga_service")

SETTINGS_NAME = "download_settings.json"


def _load_download_settings(folder: str) -> dict:
    """读取该帖最近一次下载/更新时的参数（供热更新默认表单）。"""
    p = Path(nga_library_dir()) / folder / SETTINGS_NAME
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def _save_download_settings(folder: str, settings: dict) -> None:
    p = Path(nga_library_dir()) / folder / SETTINGS_NAME
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(settings, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError as e:
        log.warning("保存下载设置失败 %s: %s", p, e)


def _sniff_epub_theme(path: str) -> str:
    """旧版 EPUB 书没有设置记录时，从样式表推断深浅色主题。"""
    try:
        import zipfile

        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if not name.lower().endswith((".css", ".xhtml", ".html")):
                    continue
                data = z.read(name).decode("utf-8", "ignore")
                if "background:#1e1e1e" in data or "background-color:#1e1e1e" in data:
                    return "dark"
                if "background:#ffffff" in data or "background-color:#ffffff" in data:
                    return "light"
    except (OSError, zipfile.BadZipFile):
        pass
    return ""


def _int_setting(params: dict, key: str, default: int) -> int:
    raw = params.get(key, default)
    if raw is None or raw == "":
        return default
    try:
        return max(0, int(raw))
    except (TypeError, ValueError):
        return default


def _local_max_floor(folder: str) -> int:
    """读取 ngapost2md 本地状态里的最大已下载楼号（无记录返回 -1）。"""
    p = Path(nga_library_dir()) / folder / "process.ini"
    try:
        import configparser

        ini = configparser.ConfigParser()
        ini.optionxform = str
        ini.read(p, encoding="utf-8")
        return int(ini.get("local", "max_floor", fallback="-1"))
    except Exception:  # noqa: BLE001
        return -1


def _nga_root() -> Path:
    """ngapost2md 包根目录（源码开发模式）。"""
    return Path(__file__).resolve().parent.parent / "ngapost2md-python"


def _import_nga():
    """惰性导入 ngapost2md（依赖 httpx 等，未安装时给出明确报错）。"""
    root = _nga_root()
    sys_path = root.as_posix()
    import sys

    if sys_path not in sys.path:
        sys.path.insert(0, sys_path)
    try:
        import ngapost2md.client as client_mod
        import ngapost2md.config as config_mod
        import ngapost2md.nga as nga_mod
        from ngapost2md.models import Tiezi
    except ImportError as e:  # pragma: no cover - 依赖缺失
        raise RuntimeError(f"ngapost2md 依赖未安装：{e}") from e
    return client_mod, config_mod, nga_mod, Tiezi


class NgaService:
    """下载服务：一次只允许一个任务，状态可轮询，支持取消。"""

    def __init__(
        self,
        book_register: Callable[[str], object],
        shelf=None,
        books=None,
    ):
        self._book_register = book_register
        self._shelf = shelf
        self._books = books
        self._lock = threading.Lock()
        self._cancel = threading.Event()
        self._status = {
            "running": False,
            "stage": "idle",
            "current": 0,
            "total": 0,
            "detail": "",
            "error": "",
            "book_id": "",
            "action": "download",
        }

    # ---------- 状态 ----------

    def status(self) -> dict:
        with self._lock:
            return dict(self._status)

    def _set(self, **kw) -> None:
        with self._lock:
            self._status.update(kw)

    def _begin(self, **kw) -> bool:
        """原子占位：任务启动瞬间即标记 running，避免并发重复启动。"""
        with self._lock:
            if self._status["running"]:
                return False
            self._status.update(running=True, current=0, total=0, error="", **kw)
        return True

    # ---------- 启动/取消 ----------

    def start(self, params: dict) -> dict:
        """启动后台下载。params: tid/url, authorid, max_floors, image_mode,
        theme, toc_pid, per_chapter, no_images, full_redownload, open_after."""
        with self._lock:
            if self._status["running"]:
                return {"ok": False, "error": "已有下载任务在运行"}
        tid, error = _parse_tid(params.get("tid", ""))
        if error:
            return {"ok": False, "error": error}
        ensure_nga_config()
        cfg = load_nga_config()
        if not cfg["configured"]:
            return {"ok": False, "error": "请先在下载面板中填写 NGA Cookie（ngaPassportUid / ngaPassportCid）"}
        self._cancel.clear()
        if not self._begin(stage="pages", detail="正在初始化…",
                           book_id="", action="download"):
            return {"ok": False, "error": "已有下载任务在运行"}
        t = threading.Thread(
            target=self._run,
            args=(tid, dict(params)),
            daemon=True,
            name="nga-download",
        )
        t.start()
        return {"ok": True}

    def cancel(self) -> None:
        self._cancel.set()

    def update_book(self, book_id: str, params: dict) -> dict:
        """对已下载的 NGA 帖子做增量热更新（只拉新页、只追加新楼层）。"""
        with self._lock:
            if self._status["running"]:
                return {"ok": False, "error": "已有下载/更新任务在运行"}
        if self._shelf is None:
            return {"ok": False, "error": "书架服务不可用"}
        rec = self._shelf.get(book_id)
        if rec is None or not rec.nga_tid:
            return {"ok": False, "error": "仅支持更新 NGA 下载的帖子"}
        folder = Path(rec.path).parent.name
        author_id = 0
        m = re.match(r"^\d+\((\d+)\)", folder)
        if m:
            author_id = int(m.group(1))
        defaults = self.update_defaults(book_id)
        if not defaults.get("ok"):
            return defaults
        field_map = {
            "authorid": "author_id",
            "theme": "theme",
            "image_mode": "image_mode",
            "per_chapter": "per_chapter",
            "toc_pid": "toc_pid",
        }
        effective = dict(params or {})
        for param_key, default_key in field_map.items():
            if param_key not in effective or effective[param_key] is None:
                effective[param_key] = defaults.get(default_key)
        self._cancel.clear()
        if not self._begin(stage="update", detail="正在检查更新…",
                           book_id=book_id, action="update"):
            return {"ok": False, "error": "已有下载/更新任务在运行"}
        t = threading.Thread(
            target=self._run_update,
            args=(rec, folder, int(rec.nga_tid), author_id, effective),
            daemon=True,
            name="nga-update",
        )
        t.start()
        return {"ok": True}

    def update_defaults(self, book_id: str) -> dict:
        """返回热更新表单的默认参数。

        优先级：最近一次下载/更新记录 > 原生书元数据 > 目录名/EPUB 样式推断。
        author_id 始终是“最近一次使用的抓取过滤”，可能与目录名中的原始作者不同。
        """
        rec = None
        if self._shelf is not None:
            rec = self._shelf.get(book_id)
        if rec is None or not rec.nga_tid:
            return {"ok": False, "error": "仅支持更新 NGA 下载的帖子"}
        folder = Path(rec.path).parent.name
        author_id = 0
        m = re.match(r"^\d+\((\d+)\)", folder)
        if m:
            author_id = int(m.group(1))
        defaults = {
            "ok": True,
            "tid": int(rec.nga_tid),
            "author_id": author_id,
            "theme": "light",
            "image_mode": "online",
            "per_chapter": 20,
            "toc_pid": 0,
            "toc_mode": "index",
        }
        meta = {}
        if is_native_dir(Path(rec.path)):
            try:
                meta = load_meta(native_dir_for(folder))
            except Exception:  # noqa: BLE001
                meta = {}
        if meta:
            defaults.update(
                author_id=int(meta.get("author_id", author_id) or 0),
                theme=str(meta.get("theme", "light")),
                image_mode=str(meta.get("image_mode", "online")),
                per_chapter=max(1, int(meta.get("per_chapter", 20) or 20)),
                toc_mode=str(meta.get("toc_mode", "index")),
            )
        stored = _load_download_settings(folder)
        if stored:
            defaults.update(
                author_id=_int_setting(stored, "author_id", defaults["author_id"]),
                theme=str(stored.get("theme", defaults["theme"])),
                image_mode=str(stored.get("image_mode", defaults["image_mode"])),
                per_chapter=_int_setting(stored, "per_chapter", defaults["per_chapter"]),
                toc_pid=_int_setting(stored, "toc_pid", defaults["toc_pid"]),
                toc_mode=str(stored.get("toc_mode", defaults["toc_mode"])),
            )
        elif not meta:
            sniffed = _sniff_epub_theme(rec.path)
            if sniffed:
                defaults["theme"] = sniffed
        if defaults["image_mode"] not in ("online", "embedded", "none"):
            defaults["image_mode"] = "online"
        if defaults["theme"] not in ("light", "dark"):
            defaults["theme"] = "light"
        if defaults["toc_mode"] not in ("index", "split"):
            defaults["toc_mode"] = "index"
        return defaults

    # ---------- 后台执行 ----------

    def _run(self, tid: int, params: dict) -> None:
        try:
            book_id = self._download(tid, params)
            self._set(running=False, stage="done", detail="下载完成",
                      book_id=book_id or "")
        except Exception as e:  # noqa: BLE001
            if self._cancel.is_set():
                self._set(running=False, stage="cancelled", detail="已取消", error="")
            else:
                log.exception("NGA 下载失败")
                self._set(running=False, stage="error", detail="",
                          error=str(e))

    def _download(self, tid: int, params: dict) -> Optional[str]:
        client_mod, config_mod, nga_mod, Tiezi = _import_nga()
        nga_mod.set_cancel_cb(self._cancel.is_set)
        try:
            cfg = _build_cfg(config_mod, params, epub_enabled=True)

            author_id = max(0, int(params.get("authorid", 0) or 0))
            full = bool(params.get("full_redownload", False))
            folder = nga_mod.find_folder_name_by_tid(tid, author_id)
            if full and folder:
                _remove_tree(Path(nga_library_dir()) / folder)
                folder = ""

            nga_client = client_mod.NgaClient(cfg)
            try:
                nga_mod.init_nga(nga_client, cfg)
                tiezi = Tiezi(tid=tid, author_id=author_id)
                if folder:
                    try:
                        nga_mod.init_from_local(tiezi)
                    except RuntimeError:
                        folder = ""
                if not folder:
                    nga_mod.init_from_web(tiezi)

                def progress(stage: str, detail: dict) -> None:
                    if stage == "pages":
                        self._set(stage="pages", current=int(detail.get("current", 0)),
                                  total=int(detail.get("total", 0)),
                                  detail=f"正在下载第 {detail.get('current', '?')}/{detail.get('total', '?')} 页")
                    elif stage == "format":
                        self._set(stage="format", detail="正在格式化楼层内容…")
                    elif stage == "markdown":
                        self._set(stage="markdown", detail="正在生成 Markdown…")
                    elif stage == "epub" and detail.get("total"):
                        cur = int(detail.get("current", 0))
                        total = int(detail.get("total", 0))
                        ok = int(detail.get("ok", 0))
                        fail = int(detail.get("fail", 0))
                        self._set(stage="epub", current=cur, total=total,
                                  detail=f"正在处理图片 {cur}/{total}（成功 {ok}，失败 {fail}）")
                    elif stage == "epub":
                        self._set(stage="epub", detail="正在生成 EPUB…")

                no_images = str(params.get("image_mode", "embedded")) == "none"

                folder_name = tiezi.folder_name or f"{tid}({author_id})"
                target_dir = Path(nga_library_dir()) / folder_name
                # 记录该目录是否本次新建：取消时只清理新建目录，
                # 避免误删此前已完成下载的同帖文件。
                was_new_dir = not target_dir.exists()
                try:
                    nga_mod.download(tiezi, progress=progress, cancel=self._cancel.is_set,
                                     no_images=no_images)
                finally:
                    if self._cancel.is_set() and was_new_dir:
                        _remove_tree(target_dir)
            finally:
                nga_client.close()

            epub_path = Path(nga_library_dir()) / folder_name / "post.epub"
            if not epub_path.exists():
                raise RuntimeError("下载完成但未生成 EPUB（请查看详细日志）")
            # 首次下载直接构建原生书容器并注册原生目录：
            # 之后“检查更新”无需整帖重下，只拉新页、只追加新楼层。
            valid = [
                f for f in tiezi.floors
                if f.lou != -1 and (tiezi.max_lou < 0 or f.lou <= tiezi.max_lou)
            ]
            per_chapter = max(1, int(params.get("per_chapter", 20) or 20))
            image_mode = str(params.get("image_mode", "online"))
            if image_mode not in ("online", "embedded", "none"):
                image_mode = "online"
            theme = "dark" if str(params.get("theme", "light")) == "dark" else "light"
            toc_mode = str(params.get("toc_mode", "index"))
            if toc_mode not in ("index", "split"):
                toc_mode = "index"
            toc_chapters = getattr(tiezi, "toc_chapters", None)
            native_dir = native_dir_for(folder_name)
            book_id = hashlib.md5(str(native_dir).encode("utf-8")).hexdigest()
            if valid:
                write_container(folder_name, tiezi, valid, per_chapter,
                                image_mode, theme, book_id,
                                toc_chapters=toc_chapters, toc_mode=toc_mode)
            _save_download_settings(folder_name, {
                "tid": tid,
                "author_id": author_id,
                "theme": theme,
                "image_mode": image_mode,
                "per_chapter": per_chapter,
                "toc_pid": max(0, int(params.get("toc_pid", 0) or 0)),
                "toc_mode": toc_mode,
                "max_floors": max(0, int(params.get("max_floors", 0) or 0)),
            })
            # 注册到书架（BookManager + Shelf 由调用方回调完成）
            book_id = self._book_register(str(native_dir) if valid else str(epub_path))
            bus.emit("book_updated", book_id=book_id)
            log_event(log, "nga", "download_done", book_id=book_id)
            return book_id
        finally:
            nga_mod.set_cancel_cb(None)

    # ---------- 热更新 ----------

    def _run_update(self, rec, folder: str, tid: int, author_id: int, params: dict) -> None:
        native = is_native_dir(Path(rec.path))
        md_path = Path(nga_library_dir()) / folder / "post.md"
        md_size = md_path.stat().st_size if md_path.is_file() else 0
        try:
            new_count = self._update_core(rec, folder, tid, author_id, params, native)
            detail = f"已更新 {new_count} 楼" if new_count else "已是最新"
            self._set(running=False, stage="done", detail=detail,
                      book_id=rec.id, action="update")
            log_event(log, "nga", "update_done", book_id=rec.id, new_floors=new_count)
            _save_download_settings(folder, self._current_settings(folder, tid, params))
        except Exception as e:  # noqa: BLE001
            if self._cancel.is_set():
                self._truncate_md(md_path, md_size)
                self._set(running=False, stage="cancelled", detail="已取消",
                          error="", book_id=rec.id, action="update")
            else:
                log.exception("NGA 热更新失败")
                self._set(running=False, stage="error", detail="",
                          error=str(e), book_id=rec.id, action="update")

    def _current_settings(self, folder: str, tid: int, params: dict) -> dict:
        """把本次更新使用的参数落盘为“最近一次设置”。"""
        old = _load_download_settings(folder)
        return {
            "tid": int(tid),
            "author_id": _int_setting(params, "authorid", int(old.get("author_id", 0) or 0)),
            "theme": str(params.get("theme", old.get("theme", "light"))),
            "image_mode": str(params.get("image_mode", old.get("image_mode", "online"))),
            "per_chapter": _int_setting(params, "per_chapter", int(old.get("per_chapter", 20) or 20)),
            "toc_pid": _int_setting(params, "toc_pid", int(old.get("toc_pid", 0) or 0)),
            "toc_mode": str(params.get("toc_mode", old.get("toc_mode", "index"))),
            "max_floors": int(old.get("max_floors", 0) or 0),
        }

    def _update_core(self, rec, folder, tid, author_id, params, native) -> int:
        client_mod, config_mod, nga_mod, Tiezi = _import_nga()
        nga_mod.set_cancel_cb(self._cancel.is_set)
        cfg = _build_cfg(config_mod, params, epub_enabled=False)
        nga_client = client_mod.NgaClient(cfg)
        try:
            nga_mod.init_nga(nga_client, cfg)
            fetch_author_id = _int_setting(params, "authorid", author_id)
            tiezi = Tiezi(tid=tid, author_id=author_id)
            if native:
                nga_mod.init_from_local(tiezi)
            else:
                nga_mod.init_from_web(tiezi)
            # 文件夹按原始 author_id 定位；抓取过滤可单独切换（仅影响新楼层）
            tiezi.author_id = fetch_author_id
            nga_mod.download(tiezi, progress=self._progress_cb,
                             cancel=self._cancel.is_set, no_images=False)
        finally:
            nga_client.close()
            nga_mod.set_cancel_cb(None)

        valid = [
            f for f in tiezi.floors
            if f.lou != -1 and (tiezi.max_lou < 0 or f.lou <= tiezi.max_lou)
        ]
        if not valid:
            return 0
        theme = "dark" if str(params.get("theme", "light")) == "dark" else "light"
        image_mode = str(params.get("image_mode", "online"))
        if image_mode not in ("online", "none"):
            image_mode = "online"
        per_chapter = max(1, int(params.get("per_chapter", 20) or 20))

        if not native:
            old_max_floor = _local_max_floor(folder)
            write_container(folder, tiezi, valid, per_chapter,
                            image_mode, theme, rec.id,
                            toc_chapters=getattr(tiezi, "toc_chapters", None),
                            toc_mode=str(params.get("toc_mode", "index")))
            new_count = len([f for f in valid if f.lou > old_max_floor])
        else:
            meta = load_meta(native_dir_for(folder))
            last_lou = int(meta.get("last_lou", 0))
            per_chapter = max(1, int(meta.get("per_chapter", per_chapter)))
            new_floors = [f for f in valid if f.lou > last_lou]
            if new_floors:
                append_container(folder, new_floors, per_chapter,
                                 image_mode, theme, rec.id)
            new_count = len(new_floors)

        # 书架记录切换到原生书，并重载 BookManager 缓存
        native_dir = native_dir_for(folder)
        meta = load_meta(native_dir)
        rec.path = str(native_dir)
        rec.chapter_count = len(meta.get("chapters", []))
        rec.title = meta.get("title", rec.title)
        rec.author = meta.get("author", rec.author)
        rec.file_mtime = dir_mtime(native_dir)
        rec.file_size = 0
        self._shelf.upsert(rec)
        self._shelf.save()
        if self._books is not None:
            self._books.close(rec.id)
            try:
                self._books.register(str(native_dir))
            except Exception as e:  # noqa: BLE001
                log.warning("热更新后重新注册书籍失败（打开时将兜底重解析）：%s", e)
        bus.emit("book_updated", book_id=rec.id)
        return new_count

    def _progress_cb(self, stage: str, detail: dict) -> None:
        if stage == "pages":
            self._set(stage="pages", current=int(detail.get("current", 0)),
                      total=int(detail.get("total", 0)),
                      detail=f"正在下载第 {detail.get('current', '?')}/{detail.get('total', '?')} 页")
        elif stage == "format":
            self._set(stage="format", detail="正在格式化新楼层…")
        elif stage == "markdown":
            self._set(stage="markdown", detail="正在更新 Markdown…")

    def _truncate_md(self, md_path: Path, size: int) -> None:
        try:
            if md_path.is_file():
                with open(md_path, "r+b") as f:
                    f.truncate(size)
        except OSError:
            pass


def _parse_tid(text: str) -> tuple[int, Optional[str]]:
    text = str(text or "").strip()
    if not text:
        return 0, "请输入帖子 tid 或链接"
    m = re.search(r"tid=(\d+)", text)
    if m:
        return int(m.group(1)), None
    m = re.search(r"\b(\d{3,})\b", text)
    if m:
        return int(m.group(1)), None
    return 0, f"无法识别帖子编号：{text}"


def _build_cfg(config_mod, params: dict, *, epub_enabled: bool):
    """构造 ngapost2md 下载配置：公共字段 + EPUB 专属字段。"""
    cfg = config_mod.load_config(str(ensure_nga_config()))
    cfg.thread = 2
    cfg.page_download_limit = int(params.get("page_limit", 0) or 0)  # 0=不限制
    cfg.max_floors = max(0, int(params.get("max_floors", 0) or 0))
    cfg.no_images = False  # 图片开关由 EPUB 渲染层控制
    cfg.no_media = True    # Markdown 不下载媒体（EPUB 自行处理，避免双重下载）
    cfg.epub_enabled = epub_enabled
    cfg.output_path = str(nga_library_dir())
    if epub_enabled:
        image_mode = str(params.get("image_mode", "online"))
        cfg.epub_image_mode = "online" if image_mode == "online" else "embedded"
        cfg.epub_per_chapter = max(1, int(params.get("per_chapter", 20) or 20))
        cfg.epub_image_quality = max(1, min(100, int(params.get("quality", 85) or 85)))
        cfg.epub_image_max_size = max(0, int(params.get("max_size", 1280) or 1280))
        cfg.epub_theme = "dark" if str(params.get("theme", "light")) == "dark" else "light"
        cfg.epub_toc_pid = max(0, int(params.get("toc_pid", 0) or 0))
    return cfg


def _remove_tree(path: Path) -> None:
    """删除帖子输出目录（全量重下用）。"""
    if not path.exists():
        return
    for p in sorted(path.rglob("*"), reverse=True):
        try:
            if p.is_dir():
                p.rmdir()
            else:
                p.unlink(missing_ok=True)
        except OSError as e:
            log.warning("清理 %s 失败：%s", p, e)
    try:
        path.rmdir()
    except OSError:
        pass
