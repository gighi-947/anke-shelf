"""骨碌碌 EPUB 后台导入服务：单飞、进度、取消、原子落盘与自动入架。"""
import logging
import threading
import time
import uuid
from pathlib import Path
from typing import Callable, Optional

from . import dialogs
from .events import bus
from .gululu_comments import comment_to_public
from .gululu_epub import (
    GululuBuildResult,
    GululuCancelled,
    GululuClient,
    build_epub,
)
from .gululu_images import normalize_image_mode
from .gululu_source import parse_book_id
from .logutil import log_event
from .paths import gululu_library_dir
from .storage import atomic_write_json, load_json_file, now_iso
from .tasks import TaskCancelled, TaskManager, TaskProgress, TaskStatus

log = logging.getLogger("gululu_service")

_COMMENT_CACHE_TTL_SECONDS = 300
_MAX_COMMENT_SCOPES = 64


class GululuService:
    """把公开骨碌碌书籍转换为标准 EPUB 并注册到现有 Windows 书架。"""

    LANE = "network:gululu"

    def __init__(
        self,
        book_register: Callable[[str], str],
        task_manager: Optional[TaskManager] = None,
        folder_picker: Optional[Callable[[], str]] = None,
    ) -> None:
        self._book_register = book_register
        self._tasks = task_manager or TaskManager(lanes={self.LANE: 1})
        self._folder_picker = folder_picker or dialogs.pick_folder
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
        }

    def status(self) -> dict:
        with self._lock:
            return dict(self._status)

    def start(self, source: str | int, image_mode: str = "online") -> dict:
        try:
            source_id = parse_book_id(source)
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
            source_id = parse_book_id(source)
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
            )
        thread = threading.Thread(
            target=self._run_export_task,
            args=(source_id, Path(dest), normalized_image_mode, task_id),
            daemon=True,
            name="gululu-export",
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
                lambda report: self._run(source_id, image_mode, task_id, report),
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

    def _run_export_task(
        self,
        source_id: int,
        dest: Path,
        image_mode: str,
        task_id: str,
    ) -> None:
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
                lambda report: self._run_export(
                    source_id,
                    dest,
                    image_mode,
                    task_id,
                    report,
                ),
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
            partial.replace(target)
            update("register", 0, 0, "正在加入书架")
            book_id = self._book_register(str(target))
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
            bus.emit("book_updated", book_id=book_id)
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
        """按楼层读取在线评论；网络失败时显式返回最近一次有效缓存。"""
        try:
            source_id = parse_book_id(source)
            scopes = self._validate_scopes(floor_ids)
        except ValueError as exc:
            return {"ok": False, "error": str(exc), "floors": []}

        cached = {floor_id: self._read_comment_cache(source_id, floor_id) for floor_id in scopes}
        pending = [
            floor_id
            for floor_id in scopes
            if refresh or cached[floor_id] is None or not cached[floor_id]["fresh"]
        ]
        network_error = ""
        if pending:
            try:
                with GululuClient() as client:
                    fetched = client.fetch_comments(source_id, pending)
                for floor_id in pending:
                    comments = [comment_to_public(item) for item in fetched.get(floor_id, [])]
                    payload = {
                        "version": 1,
                        "source_id": source_id,
                        "floor_id": floor_id,
                        "fetched_at": now_iso(),
                        "comments": comments,
                    }
                    self._write_comment_cache(source_id, floor_id, payload)
                    cached[floor_id] = {"payload": payload, "fresh": True}
            except Exception as exc:  # noqa: BLE001 - converted to explicit API result below
                network_error = str(exc)
                log.warning(
                    "骨碌碌在线评论读取失败 source_id=%s floors=%s: %s",
                    source_id,
                    pending,
                    exc,
                )

        floors = []
        hard_errors = []
        for floor_id in scopes:
            item = cached.get(floor_id)
            if item is None:
                error = network_error or "评论缓存不可用"
                hard_errors.append(f"{floor_id}: {error}")
                floors.append({
                    "floor_id": floor_id,
                    "comments": [],
                    "cached": False,
                    "stale": False,
                    "fetched_at": "",
                    "error": error,
                })
                continue
            payload = item["payload"]
            was_pending = floor_id in pending
            stale = bool(network_error and was_pending)
            floors.append({
                "floor_id": floor_id,
                "comments": payload["comments"],
                "cached": not was_pending or stale,
                "stale": stale,
                "fetched_at": payload["fetched_at"],
                "error": network_error if stale else "",
            })
        return {
            "ok": not hard_errors,
            "source_id": source_id,
            "floors": floors,
            "error": "; ".join(hard_errors),
        }

    @staticmethod
    def _validate_scopes(floor_ids: list[int]) -> list[int]:
        if not isinstance(floor_ids, list):
            raise ValueError("评论楼层列表格式错误")
        scopes = list(dict.fromkeys(floor_ids))
        if not scopes or len(scopes) > _MAX_COMMENT_SCOPES:
            raise ValueError(f"单次评论请求必须包含 1-{_MAX_COMMENT_SCOPES} 个楼层")
        if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in scopes):
            raise ValueError("评论楼层 ID 格式错误")
        return scopes

    @staticmethod
    def _comment_cache_path(source_id: int, floor_id: int) -> Path:
        return gululu_library_dir() / str(source_id) / "comments" / f"{floor_id}.json"

    def _read_comment_cache(self, source_id: int, floor_id: int) -> Optional[dict]:
        path = self._comment_cache_path(source_id, floor_id)
        payload = load_json_file(path)
        if not isinstance(payload, dict):
            return None
        if (
            payload.get("version") != 1
            or payload.get("source_id") != source_id
            or payload.get("floor_id") != floor_id
            or not isinstance(payload.get("fetched_at"), str)
            or not isinstance(payload.get("comments"), list)
        ):
            return None
        try:
            fresh = time.time() - path.stat().st_mtime <= _COMMENT_CACHE_TTL_SECONDS
        except OSError:
            fresh = False
        return {"payload": payload, "fresh": fresh}

    def _write_comment_cache(self, source_id: int, floor_id: int, payload: dict) -> None:
        path = self._comment_cache_path(source_id, floor_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_json(path, payload)

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
