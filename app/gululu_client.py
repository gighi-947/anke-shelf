"""Gululu public reader API client and explicit domain results."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Optional

import httpx

from .gululu_comments import fetch_comment_scopes, fetch_comments_by_floor
from .gululu_source import parse_book_id


API_BASE = "https://backend.gululu.world"
SITE_BASE = "https://www.gululu.world"

ProgressCallback = Callable[[str, int, int, str], None]
CancelCallback = Callable[[], bool]


class GululuError(Exception):
    """Base error whose message can be shown in the Windows UI."""


class GululuApiError(GululuError):
    """Public reader API network, protocol, or business failure."""


class GululuFormatError(GululuError):
    """Gululu AST or EPUB input does not match a supported structure."""


class GululuCancelled(GululuError):
    """The owning import, export, or update task was cancelled."""


@dataclass(frozen=True)
class GululuIndex:
    detail: dict
    floor_index: list[dict]
    chapter_index: list[dict]


@dataclass(frozen=True)
class GululuSnapshot:
    detail: dict
    floor_index: list[dict]
    chapter_index: list[dict]
    floors: list[dict]
    comments_by_floor: dict[int, list[dict]] = field(default_factory=dict)


class GululuClient:
    """Anonymous reader client with independently fetchable index and floor bodies."""

    def __init__(
        self,
        http: Optional[httpx.Client] = None,
        *,
        floor_batch_size: int = 20,
        timeout: float = 30.0,
    ) -> None:
        if floor_batch_size < 1:
            raise ValueError("floor_batch_size 必须大于 0")
        self._http = http or httpx.Client(
            base_url=API_BASE,
            timeout=timeout,
            follow_redirects=True,
        )
        self._floor_batch_size = floor_batch_size

    def __enter__(self) -> "GululuClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def close(self) -> None:
        self._http.close()

    def _request_data(self, method: str, path: str, **kwargs):
        headers = dict(kwargs.pop("headers", {}) or {})
        headers["platform"] = "1"
        try:
            response = self._http.request(method, path, headers=headers, **kwargs)
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise GululuApiError(f"骨碌碌接口请求失败（{path}）：{exc}") from exc
        if not isinstance(payload, dict):
            raise GululuApiError(f"骨碌碌接口响应格式错误（{path}）")
        if payload.get("code") != 200:
            message = str(payload.get("msg") or "未知业务错误")
            raise GululuApiError(f"骨碌碌接口返回失败（{path}）：{message}")
        if "data" not in payload:
            raise GululuApiError(f"骨碌碌接口响应缺少 data（{path}）")
        return payload["data"]

    @staticmethod
    def _report(
        progress: Optional[ProgressCallback],
        cancel: Optional[CancelCallback],
        stage: str,
        current: int,
        total: int,
        detail: str,
    ) -> None:
        if cancel is not None and cancel():
            raise GululuCancelled("骨碌碌任务已取消")
        if progress is not None:
            progress(stage, current, total, detail)

    def fetch_index(
        self,
        book_id: int,
        *,
        progress: Optional[ProgressCallback] = None,
        cancel: Optional[CancelCallback] = None,
    ) -> GululuIndex:
        book_id = parse_book_id(book_id)
        self._report(progress, cancel, "metadata", 0, 0, "正在读取书籍信息")
        detail = self._request_data("GET", f"/reader/opus/detail/{book_id}")
        self._report(progress, cancel, "index", 0, 0, "正在读取目录")
        floor_index = self._request_data("GET", f"/reader/floor/index-list/{book_id}")
        chapter_data = self._request_data(
            "GET", "/reader/opus/chapter-index", params={"opusId": book_id}
        )
        if not isinstance(detail, dict) or not isinstance(floor_index, list):
            raise GululuApiError("骨碌碌书籍详情或楼层目录格式错误")
        if chapter_data is None:
            chapter_data = {}
        elif not isinstance(chapter_data, dict):
            raise GululuApiError("骨碌碌章节目录格式错误")
        chapter_index = chapter_data.get("chapterIndex") or []
        if not isinstance(chapter_index, list):
            raise GululuApiError("骨碌碌 chapterIndex 格式错误")
        for item in floor_index:
            if not isinstance(item, dict) or not isinstance(item.get("floorId"), int):
                raise GululuApiError("骨碌碌楼层目录条目格式错误")
        return GululuIndex(dict(detail), list(floor_index), list(chapter_index))

    def fetch_floors(
        self,
        book_id: int,
        floor_ids: list[int],
        *,
        progress: Optional[ProgressCallback] = None,
        cancel: Optional[CancelCallback] = None,
    ) -> list[dict]:
        parse_book_id(book_id)
        if any(isinstance(value, bool) or not isinstance(value, int) for value in floor_ids):
            raise GululuApiError("骨碌碌楼层 ID 格式错误")
        by_id: dict[int, dict] = {}
        total = len(floor_ids)
        for start in range(0, total, self._floor_batch_size):
            self._report(progress, cancel, "floors", start, total, "正在获取楼层")
            batch = floor_ids[start:start + self._floor_batch_size]
            data = self._request_data("POST", "/reader/floor/content-by-ids", json=batch)
            if not isinstance(data, list):
                raise GululuApiError("骨碌碌楼层正文格式错误")
            for floor in data:
                if isinstance(floor, dict) and isinstance(floor.get("id"), int):
                    by_id[floor["id"]] = floor
            current = min(start + len(batch), total)
            self._report(
                progress,
                cancel,
                "floors",
                current,
                total,
                f"正在获取楼层 {current}/{total}",
            )
        missing = [floor_id for floor_id in floor_ids if floor_id not in by_id]
        if missing:
            preview = ", ".join(str(value) for value in missing[:5])
            raise GululuApiError(f"骨碌碌楼层正文缺失：{preview}")
        return [by_id[floor_id] for floor_id in floor_ids]

    def fetch_snapshot(
        self,
        book_id: int,
        *,
        progress: Optional[ProgressCallback] = None,
        cancel: Optional[CancelCallback] = None,
        include_comments: bool = True,
    ) -> GululuSnapshot:
        """Read a complete, stably ordered public snapshot."""
        book_id = parse_book_id(book_id)
        index = self.fetch_index(book_id, progress=progress, cancel=cancel)
        floor_ids = [item["floorId"] for item in index.floor_index]
        floors = self.fetch_floors(
            book_id,
            floor_ids,
            progress=progress,
            cancel=cancel,
        )
        comments_by_floor = {}
        if include_comments:
            def report(stage: str, current: int, total: int, detail: str) -> None:
                self._report(progress, cancel, stage, current, total, detail)

            def check_cancelled() -> None:
                self._report(None, cancel, "comments", 0, 0, "")

            try:
                comments_by_floor = fetch_comments_by_floor(
                    self._request_data,
                    book_id,
                    floors,
                    report=report,
                    check_cancelled=check_cancelled,
                )
            except ValueError as exc:
                raise GululuApiError(str(exc)) from exc
        return GululuSnapshot(
            index.detail,
            index.floor_index,
            index.chapter_index,
            floors,
            comments_by_floor,
        )

    def fetch_comments(self, book_id: int, floor_ids: list[int]) -> dict[int, list[dict]]:
        book_id = parse_book_id(book_id)
        try:
            return fetch_comment_scopes(self._request_data, book_id, floor_ids)
        except ValueError as exc:
            raise GululuApiError(str(exc)) from exc
