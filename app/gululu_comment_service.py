"""Gululu online-comment cache with explicit stale and failure results."""
from __future__ import annotations

import logging
import time
from pathlib import Path
from typing import Callable

from .gululu_client import GululuClient
from .gululu_comments import comment_to_public
from .gululu_source import parse_book_id
from .storage import atomic_write_json, load_json_file, now_iso


log = logging.getLogger("gululu_service")
_COMMENT_CACHE_TTL_SECONDS = 300
_MAX_COMMENT_SCOPES = 64


class GululuCommentService:
    def __init__(
        self,
        library_dir: Callable[[], Path],
        client_factory: Callable[[], GululuClient],
    ) -> None:
        self._library_dir = library_dir
        self._client_factory = client_factory

    def get_comments(
        self,
        source: str | int,
        floor_ids: list[int],
        *,
        refresh: bool = False,
    ) -> dict:
        try:
            source_id = parse_book_id(source)
            scopes = self._validate_scopes(floor_ids)
        except ValueError as exc:
            return {"ok": False, "error": str(exc), "floors": []}

        cached = {floor_id: self._read_cache(source_id, floor_id) for floor_id in scopes}
        pending = [
            floor_id
            for floor_id in scopes
            if refresh or cached[floor_id] is None or not cached[floor_id]["fresh"]
        ]
        network_error = ""
        if pending:
            try:
                with self._client_factory() as client:
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
                    self._write_cache(source_id, floor_id, payload)
                    cached[floor_id] = {"payload": payload, "fresh": True}
            except Exception as exc:  # noqa: BLE001 - converted to explicit API results below
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

    def _cache_path(self, source_id: int, floor_id: int) -> Path:
        return self._library_dir() / str(source_id) / "comments" / f"{floor_id}.json"

    def _read_cache(self, source_id: int, floor_id: int):
        path = self._cache_path(source_id, floor_id)
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

    def _write_cache(self, source_id: int, floor_id: int, payload: dict) -> None:
        path = self._cache_path(source_id, floor_id)
        path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_json(path, payload)
