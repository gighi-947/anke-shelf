"""NGA 帖子导出服务：单飞后台任务，状态可轮询（配合下载/导出整合页）。"""
import logging
import shutil
import threading
from pathlib import Path
from typing import Callable, Optional

from . import dialogs
from .native_book import is_native_dir, rebuild_epub_for_native
from .shelf import Shelf

log = logging.getLogger("export_service")


class ExportService:
    """一次只允许一个导出任务；文件夹选择在后台线程触发。"""

    def __init__(self, shelf: Shelf, folder_picker: Optional[Callable[[], str]] = None):
        self._shelf = shelf
        self._folder_picker = folder_picker or dialogs.pick_folder
        self._lock = threading.Lock()
        self._status = {
            "running": False,
            "stage": "idle",
            "current": 0,
            "total": 0,
            "detail": "",
            "files": [],
            "dest": "",
            "error": "",
        }

    def status(self) -> dict:
        with self._lock:
            return dict(self._status)

    def start(self, book_id: str, fmt: str = "both") -> dict:
        """启动后台导出。fmt: epub / md / both。"""
        if not self._begin(stage="prepare", detail="正在准备导出…",
                           current=0, total=0, files=[], dest="", error=""):
            return {"ok": False, "error": "已有导出任务在运行"}
        rec = self._shelf.get(book_id)
        if rec is None or not rec.nga_tid:
            with self._lock:
                self._status.update(running=False, stage="idle", detail="")
            return {"ok": False, "error": "仅支持导出 NGA 下载的帖子"}
        t = threading.Thread(
            target=self._run,
            args=(rec, fmt),
            daemon=True,
            name="nga-export",
        )
        t.start()
        return {"ok": True}

    def open_dest(self) -> dict:
        """在资源管理器中打开最近一次导出的目标文件夹。"""
        import os

        dest = self.status().get("dest") or ""
        if not dest or not Path(dest).is_dir():
            return {"ok": False, "error": "没有可打开的导出文件夹"}
        try:
            os.startfile(dest)
            return {"ok": True}
        except OSError as e:
            return {"ok": False, "error": str(e)}

    # ---------- 后台执行 ----------

    def _run(self, rec, fmt: str) -> None:
        try:
            self._set(running=True, stage="prepare", current=0, total=0,
                      detail="正在选择导出文件夹…", files=[], dest="", error="")
            src_dir = Path(rec.path).parent
            if not src_dir.is_dir():
                raise RuntimeError("找不到帖子源文件夹，可能已被移动")
            sources = []
            native = is_native_dir(Path(rec.path))
            if fmt in ("epub", "both"):
                if native:
                    self._set(stage="copy", detail="正在生成 EPUB…")
                    folder = Path(rec.path).parent.name
                    epub = rebuild_epub_for_native(folder)
                    sources.append(epub)
                else:
                    epub = Path(rec.path)
                    if epub.is_file():
                        sources.append(epub)
            if fmt in ("md", "both"):
                md_dir = src_dir
                sources.extend(sorted(md_dir.glob("*.md")))
            if not sources:
                raise RuntimeError("没有可导出的 EPUB / Markdown 文件")
            dest = self._folder_picker()
            if not dest:
                self._set(running=False, stage="cancelled", detail="已取消")
                return
            out_dir = Path(dest)
            out_dir.mkdir(parents=True, exist_ok=True)
            copied = []
            total = len(sources)
            for i, src in enumerate(sources, 1):
                shutil.copy2(str(src), str(out_dir / src.name))
                copied.append(src.name)
                self._set(stage="copy", current=i, total=total,
                          detail=f"正在导出 {i}/{total}：{src.name}")
            self._set(running=False, stage="done", detail="导出完成",
                      files=copied, dest=str(out_dir))
        except Exception as e:  # noqa: BLE001
            log.exception("NGA 导出失败")
            self._set(running=False, stage="error", detail="", error=str(e),
                      files=[], dest="")

    def _set(self, **kw) -> None:
        with self._lock:
            self._status.update(kw)

    def _begin(self, **kw) -> bool:
        """原子占位：任务启动瞬间即标记 running，避免并发重复启动。"""
        with self._lock:
            if self._status["running"]:
                return False
            self._status.update(running=True, **kw)
        return True
