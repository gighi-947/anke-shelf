"""骨碌碌 EPUB 后台导入服务：单飞、进度、取消、原子落盘与自动入架。"""
import logging
import threading
import uuid
from pathlib import Path
from typing import Callable, Optional

from . import dialogs
from .gululu_comment_service import GululuCommentService
from .gululu_epub import (
    GululuBuildResult,
    GululuCancelled,
    GululuClient,
    build_epub,
)
from .gululu_images import normalize_image_mode
from .gululu_source import extract_book_id
from .gululu_update import book_id_for_target, execute_update, replace_and_register, write_baseline
from .logutil import log_event
from .paths import gululu_library_dir
from .tasks import TaskCancelled, TaskManager, TaskProgress, TaskStatus

log = logging.getLogger("gululu_service")

class GululuService:
    """把公开骨碌碌书籍转换为标准 EPUB 并注册到现有 Windows 书架。"""

    LANE = "network:gululu"

    def __init__(
        self,
        book_register: Callable[[str], str],
        task_manager: Optional[TaskManager] = None,
        folder_picker: Optional[Callable[[], str]] = None,
        shelf=None,
        books=None,
        on_book_updated: Optional[Callable[[str], None]] = None,
    ) -> None:
        self._book_register = book_register
        self._tasks = task_manager or TaskManager(lanes={self.LANE: 1})
        self._folder_picker = folder_picker or dialogs.pick_folder
        self._shelf = shelf
        self._books = books
        self._on_book_updated = on_book_updated
        self._comments = GululuCommentService(
            lambda: gululu_library_dir(),
            lambda: GululuClient(),
        )
        self._lock = threading.Lock()
        self._current_task: Optional[str] = None
        self._status = {
            "running": False,
            "stage": "idle",
            "current": 0,
            "total": 0,
            "detail": "",
            "error": "",
            "book_id": "",
            "source_id": 0,
            "task_id": "",
            "action": "",
            "files": [],
            "dest": "",
            "image_mode": "online",
            "image_total": 0,
            "image_embedded": 0,
            "image_failed": 0,
            "new_count": 0,
            "baseline_initialized": False,
        }

    def status(self) -> dict:
        with self._lock:
            return dict(self._status)

    def start(self, source: str | int, image_mode: str = "online") -> dict:
        try:
            source_id = extract_book_id(source)
            normalized_image_mode = normalize_image_mode(image_mode)
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}
        task_id = f"gululu-{uuid.uuid4().hex[:12]}"
        if not self._tasks.start(self.LANE, task_id):
            return {"ok": False, "error": "已有骨碌碌导入任务在运行"}
        with self._lock:
            self._current_task = task_id
            self._status.update(
                running=True,
                stage="metadata",
                current=0,
                total=0,
                detail="正在读取书籍信息",
                error="",
                book_id="",
                source_id=source_id,
                task_id=task_id,
                action="import",
                files=[],
                dest="",
                image_mode=normalized_image_mode,
                image_total=0,
                image_embedded=0,
                image_failed=0,
                new_count=0,
                baseline_initialized=False,
            )
        thread = threading.Thread(
            target=self._run_task,
            args=(source_id, normalized_image_mode, task_id),
            daemon=True,
            name="gululu-import",
        )
        thread.start()
        return {"ok": True, "task_id": task_id}

    def start_export(self, source: str | int, image_mode: str = "online") -> dict:
        """生成一份包含当前公开评论的独立 EPUB，不修改书架副本。"""
        try:
            source_id = extract_book_id(source)
            normalized_image_mode = normalize_image_mode(image_mode)
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}
        dest = self._folder_picker()
        if not dest:
            return {"ok": False, "cancelled": True, "error": "已取消导出"}
        task_id = f"gululu-export-{uuid.uuid4().hex[:12]}"
        if not self._tasks.start(self.LANE, task_id):
            return {"ok": False, "error": "已有骨碌碌任务在运行"}
        with self._lock:
            self._current_task = task_id
            self._status.update(
                running=True,
                stage="metadata",
                current=0,
                total=0,
                detail="正在读取书籍信息",
                error="",
                book_id="",
                source_id=source_id,
                task_id=task_id,
                action="export",
                files=[],
                dest=str(dest),
                image_mode=normalized_image_mode,
                image_total=0,
                image_embedded=0,
                image_failed=0,
                new_count=0,
                baseline_initialized=False,
            )
        thread = threading.Thread(
            target=self._run_export_task,
            args=(source_id, Path(dest), normalized_image_mode, task_id),
            daemon=True,
            name="gululu-export",
        )
        thread.start()
        return {"ok": True, "task_id": task_id}

    def start_update(self, source: str | int, image_mode: str = "online") -> dict:
        try:
            source_id = extract_book_id(source)
            normalized_image_mode = normalize_image_mode(image_mode)
        except ValueError as exc:
            return {"ok": False, "error": str(exc)}
        target = gululu_library_dir() / str(source_id) / "post.epub"
        if not target.is_file():
            return {"ok": False, "error": "本机没有可更新的骨碌碌 EPUB，请先完成导入"}
        task_id = f"gululu-update-{uuid.uuid4().hex[:12]}"
        if not self._tasks.start(self.LANE, task_id):
            return {"ok": False, "error": "已有骨碌碌任务在运行"}
        with self._lock:
            self._current_task = task_id
            self._status.update(
                running=True,
                stage="update",
                current=0,
                total=0,
                detail="正在检查更新",
                error="",
                book_id=book_id_for_target(self._shelf, target),
                source_id=source_id,
                task_id=task_id,
                action="update",
                files=[],
                dest="",
                image_mode=normalized_image_mode,
                image_total=0,
                image_embedded=0,
                image_failed=0,
                new_count=0,
                baseline_initialized=False,
            )
        thread = threading.Thread(
            target=self._run_update_task,
            args=(source_id, normalized_image_mode, task_id),
            daemon=True,
            name="gululu-update",
        )
        thread.start()
        return {"ok": True, "task_id": task_id}

    def cancel(self) -> dict:
        with self._lock:
            task_id = self._current_task
        if task_id is None:
            return {"ok": False, "error": "没有进行中的骨碌碌任务"}
        self._tasks.cancel(task_id)
        return {"ok": True}

    def _run_task(self, source_id: int, image_mode: str, task_id: str) -> None:
        self._run_managed_task(
            task_id,
            lambda report: self._run(source_id, image_mode, task_id, report),
        )

    def _run_export_task(
        self,
        source_id: int,
        dest: Path,
        image_mode: str,
        task_id: str,
    ) -> None:
        self._run_managed_task(
            task_id,
            lambda report: self._run_export(
                source_id, dest, image_mode, task_id, report
            ),
        )

    def _run_update_task(self, source_id: int, image_mode: str, task_id: str) -> None:
        self._run_managed_task(
            task_id,
            lambda report: self._run_update(source_id, image_mode, task_id, report),
        )

    def _run_managed_task(self, task_id: str, worker: Callable) -> None:
        def on_progress(item: TaskProgress) -> None:
            self._set(
                stage=item.stage,
                current=item.current,
                total=item.total,
                detail=item.message,
            )

        try:
            status = self._tasks.run(
                self.LANE,
                task_id,
                worker,
                on_progress=on_progress,
            )
            if status == TaskStatus.CANCELLED:
                self._set(running=False, stage="cancelled", detail="已取消", error="")
            elif status == TaskStatus.FAILED and self.status()["stage"] != "error":
                self._set(running=False, stage="error", detail="")
        finally:
            with self._lock:
                if self._current_task == task_id:
                    self._current_task = None

    def _run(self, source_id: int, image_mode: str, task_id: str, report) -> None:
        folder = gululu_library_dir() / str(source_id)
        target = folder / "post.epub"
        partial = folder / "post.epub.part"

        def cancelled() -> bool:
            return self._tasks.is_cancelled(task_id)

        def update(stage: str, current: int, total: int, detail: str) -> None:
            report(TaskProgress(current=current, total=total, stage=stage, message=detail))

        try:
            folder.mkdir(parents=True, exist_ok=True)
            partial.unlink(missing_ok=True)
            with GululuClient() as client:
                snapshot = client.fetch_snapshot(
                    source_id,
                    progress=update,
                    cancel=cancelled,
                    include_comments=False,
                )
            update("epub", 0, 0, "正在生成 EPUB")
            result = build_epub(
                detail=snapshot.detail,
                floor_index=snapshot.floor_index,
                chapter_index=snapshot.chapter_index,
                floors=snapshot.floors,
                comments_by_floor=snapshot.comments_by_floor,
                output_path=partial,
                image_mode=image_mode,
                progress=update,
                cancel=cancelled,
            )
            if cancelled():
                raise GululuCancelled("骨碌碌导入已取消")
            book_id = replace_and_register(
                target,
                partial,
                task_id,
                book_register=self._book_register,
                shelf=self._shelf,
                books=self._books,
            )
            update("register", 0, 0, "正在加入书架")
            write_baseline(folder / "snapshot.json", source_id, snapshot, image_mode)
            detail = self._completion_detail("导入完成", result)
            self._set(
                running=False,
                stage="done",
                current=1,
                total=1,
                detail=detail,
                error="",
                book_id=book_id,
                image_total=result.image_total,
                image_embedded=result.image_embedded,
                image_failed=len(result.image_failures),
            )
            self._log_image_failures(source_id, result)
            self._notify_book_updated(book_id)
            log_event(log, "gululu", "import_done", book_id=book_id, source_id=source_id)
        except (GululuCancelled, TaskCancelled):
            partial.unlink(missing_ok=True)
            self._set(running=False, stage="cancelled", detail="已取消", error="")
            raise TaskCancelled(task_id)
        except Exception as exc:  # noqa: BLE001
            partial.unlink(missing_ok=True)
            log.exception("骨碌碌 EPUB 导入失败")
            self._set(running=False, stage="error", detail="", error=str(exc), book_id="")
            raise

    def _run_update(
        self,
        source_id: int,
        image_mode: str,
        task_id: str,
        report,
    ) -> None:
        folder = gululu_library_dir() / str(source_id)
        partial = folder / "post.epub.part"

        def cancelled() -> bool:
            return self._tasks.is_cancelled(task_id)

        def update(stage: str, current: int, total: int, detail: str) -> None:
            report(TaskProgress(current=current, total=total, stage=stage, message=detail))

        try:
            partial.unlink(missing_ok=True)
            executed = execute_update(
                source_id=source_id,
                image_mode=image_mode,
                task_id=task_id,
                folder=folder,
                client_factory=GululuClient,
                build_epub=build_epub,
                book_register=self._book_register,
                shelf=self._shelf,
                books=self._books,
                progress=update,
                cancel=cancelled,
            )
            result = executed.build_result
            self._set(
                running=False,
                stage="done",
                current=1,
                total=1,
                detail=executed.detail,
                error="",
                book_id=executed.book_id,
                new_count=executed.new_count,
                baseline_initialized=executed.baseline_initialized,
                image_total=result.image_total if result else 0,
                image_embedded=result.image_embedded if result else 0,
                image_failed=len(result.image_failures) if result else 0,
            )
            if result is not None:
                self._log_image_failures(source_id, result)
                self._notify_book_updated(executed.book_id)
                log_event(
                    log,
                    "gululu",
                    "update_done",
                    book_id=executed.book_id,
                    source_id=source_id,
                    new_count=executed.new_count,
                )
        except (GululuCancelled, TaskCancelled):
            partial.unlink(missing_ok=True)
            self._set(running=False, stage="cancelled", detail="已取消", error="")
            raise TaskCancelled(task_id)
        except Exception as exc:  # noqa: BLE001 - converted to explicit task failure
            partial.unlink(missing_ok=True)
            log.exception("骨碌碌 EPUB 更新失败")
            self._set(running=False, stage="error", detail="", error=str(exc))
            raise

    def _run_export(
        self,
        source_id: int,
        dest: Path,
        image_mode: str,
        task_id: str,
        report,
    ) -> None:
        target = dest / f"gululu-{source_id}-comments.epub"
        partial = dest / f"gululu-{source_id}-comments.epub.part"

        def cancelled() -> bool:
            return self._tasks.is_cancelled(task_id)

        def update(stage: str, current: int, total: int, detail: str) -> None:
            report(TaskProgress(current=current, total=total, stage=stage, message=detail))

        try:
            dest.mkdir(parents=True, exist_ok=True)
            partial.unlink(missing_ok=True)
            with GululuClient() as client:
                snapshot = client.fetch_snapshot(
                    source_id,
                    progress=update,
                    cancel=cancelled,
                    include_comments=True,
                )
            update("epub", 0, 0, "正在生成含评论 EPUB")
            result = build_epub(
                detail=snapshot.detail,
                floor_index=snapshot.floor_index,
                chapter_index=snapshot.chapter_index,
                floors=snapshot.floors,
                comments_by_floor=snapshot.comments_by_floor,
                output_path=partial,
                image_mode=image_mode,
                progress=update,
                cancel=cancelled,
            )
            if cancelled():
                raise GululuCancelled("骨碌碌导出已取消")
            partial.replace(target)
            self._set(
                running=False,
                stage="done",
                current=1,
                total=1,
                detail=self._completion_detail("导出完成", result),
                error="",
                files=[target.name],
                dest=str(dest),
                action="export",
                image_total=result.image_total,
                image_embedded=result.image_embedded,
                image_failed=len(result.image_failures),
            )
            self._log_image_failures(source_id, result)
            log_event(log, "gululu", "export_done", source_id=source_id)
        except (GululuCancelled, TaskCancelled):
            partial.unlink(missing_ok=True)
            self._set(running=False, stage="cancelled", detail="已取消", error="")
            raise TaskCancelled(task_id)
        except Exception as exc:  # noqa: BLE001
            partial.unlink(missing_ok=True)
            log.exception("骨碌碌 EPUB 导出失败")
            self._set(running=False, stage="error", detail="", error=str(exc), files=[])
            raise

    def get_comments(
        self,
        source: str | int,
        floor_ids: list[int],
        *,
        refresh: bool = False,
    ) -> dict:
        return self._comments.get_comments(source, floor_ids, refresh=refresh)

    @staticmethod
    def _completion_detail(prefix: str, result: GululuBuildResult) -> str:
        if result.image_mode != "embedded":
            return prefix
        failed = len(result.image_failures)
        return (
            f"{prefix}；已内嵌图片 {result.image_embedded}/{result.image_total}"
            + (f"，失败 {failed} 张已显示占位" if failed else "")
        )

    @staticmethod
    def _log_image_failures(source_id: int, result: GululuBuildResult) -> None:
        if result.image_failures:
            log.warning(
                "骨碌碌图片内嵌部分失败 source_id=%s failed=%s first=%s",
                source_id,
                len(result.image_failures),
                result.image_failures[0],
            )

    def _set(self, **values) -> None:
        with self._lock:
            self._status.update(values)

    def _notify_book_updated(self, book_id: str) -> None:
        """导入/更新完成后通知宿主刷新缓存（原 EventBus 单一订阅点）。"""
        if self._on_book_updated is not None:
            self._on_book_updated(book_id)
