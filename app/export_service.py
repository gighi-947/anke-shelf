"""NGA 帖子导出服务：单飞后台任务，状态可轮询（配合下载/导出整合页）。"""
import logging
import re
import shutil
import threading
import uuid
from pathlib import Path
from typing import Callable, Optional

from . import dialogs
from .native_book import is_native_dir, rebuild_epub_for_native
from .shelf import Shelf
from .tasks import TaskCancelled, TaskManager, TaskProgress, TaskStatus

log = logging.getLogger("export_service")


def _safe_filename(name: str) -> str:
    """把安科标题清洗为 Windows 可用文件名；空结果返回空串。"""
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "", str(name or "").strip())
    name = re.sub(r"\s+", " ", name).strip(" .")
    return name[:80]


class ExportService:
    """导出任务：单飞/进度/取消由 TaskManager（lane=export）承载，状态可轮询。"""

    def __init__(
        self,
        shelf: Shelf,
        folder_picker: Optional[Callable[[], str]] = None,
        task_manager: Optional[TaskManager] = None,
    ):
        self._shelf = shelf
        self._folder_picker = folder_picker or dialogs.pick_folder
        self._tasks = task_manager or TaskManager(lanes={"export": 1})
        self._lock = threading.Lock()
        self._current_task: Optional[str] = None
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
        rec = self._shelf.get(book_id)
        if rec is None or not rec.nga_tid:
            return {"ok": False, "error": "仅支持导出 NGA 下载的帖子"}
        task_id = f"export-{uuid.uuid4().hex[:12]}"
        if not self._tasks.start("export", task_id):
            return {"ok": False, "error": "已有导出任务在运行"}
        with self._lock:
            self._current_task = task_id
            self._status.update(
                running=True, stage="prepare", detail="正在准备导出…",
                current=0, total=0, files=[], dest="", error="",
            )
        t = threading.Thread(
            target=self._run_task,
            args=(rec, fmt, task_id),
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

    def cancel(self) -> dict:
        """取消当前导出任务（协作出取消，文件夹选择等阻塞点不可中断）。"""
        with self._lock:
            task_id = self._current_task
        if task_id is None:
            return {"ok": False, "error": "没有进行中的导出任务"}
        self._tasks.cancel(task_id)
        return {"ok": True}

    # ---------- 后台执行 ----------

    def _run_task(self, rec, fmt: str, task_id: str) -> None:
        def on_progress(p: TaskProgress) -> None:
            self._set(stage=p.stage, current=p.current, total=p.total, detail=p.message)

        try:
            status = self._tasks.run(
                "export",
                task_id,
                lambda report: self._run(rec, fmt, report),
                on_progress=on_progress,
            )
            if status == TaskStatus.CANCELLED:
                self._set(running=False, stage="cancelled", detail="已取消")
            elif status == TaskStatus.FAILED:
                self._set(running=False, stage="error")
        finally:
            with self._lock:
                if self._current_task == task_id:
                    self._current_task = None

    def _run(self, rec, fmt: str, report) -> None:
        def step(stage: str, current: int = 0, total: int = 0, detail: str = "") -> None:
            self._set(stage=stage, current=current, total=total, detail=detail)
            report(TaskProgress(current=current, total=total, stage=stage, message=detail))

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
                    step("copy", detail="正在生成 EPUB…")
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
                raise TaskCancelled("export cancelled by user")
            out_dir = Path(dest)
            out_dir.mkdir(parents=True, exist_ok=True)
            copied = []
            total = len(sources)
            base = _safe_filename(rec.title)
            if not base:
                base = _safe_filename(f"安科-tid{rec.nga_tid}") or "未命名安科"
            for i, src in enumerate(sources, 1):
                out_name = base + (src.suffix or "")
                out_path = out_dir / out_name
                if out_path.exists():
                    out_path = out_dir / f"{base}-{i}{src.suffix or ''}"
                shutil.copy2(str(src), str(out_path))
                copied.append(out_path.name)
                step("copy", i, total, f"正在导出 {i}/{total}：{out_path.name}")
            self._set(running=False, stage="done", detail="导出完成",
                      files=copied, dest=str(out_dir))
        except TaskCancelled:
            self._set(running=False, stage="cancelled", detail="已取消", error="")
            raise
        except Exception as e:  # noqa: BLE001
            log.exception("NGA 导出失败")
            self._set(running=False, stage="error", detail="", error=str(e),
                      files=[], dest="")
            raise

    def _set(self, **kw) -> None:
        with self._lock:
            self._status.update(kw)
