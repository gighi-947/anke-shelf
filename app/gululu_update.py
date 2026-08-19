"""Persistent Gululu update baselines and append-only merge validation."""
from __future__ import annotations

import json
import shutil
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from .gululu_client import GululuCancelled, GululuClient, GululuIndex, GululuSnapshot
from .gululu_epub import GululuBuildResult
from .gululu_images import normalize_image_mode
from .storage import atomic_write_json


class GululuUpdateConflict(Exception):
    """Remote history is not an append-only continuation of the local baseline."""


@dataclass(frozen=True)
class BaselineMissing:
    pass


@dataclass(frozen=True)
class BaselineInvalid:
    error: str


@dataclass(frozen=True)
class BaselineOk:
    snapshot: GululuSnapshot
    image_mode: str


@dataclass(frozen=True)
class IncrementalPlan:
    new_floor_ids: tuple[int, ...]


@dataclass(frozen=True)
class PreparedUpdate:
    snapshot: GululuSnapshot
    image_mode: str
    new_count: int
    rebuild: bool
    baseline_initialized: bool


@dataclass(frozen=True)
class ExecutedUpdate:
    book_id: str
    detail: str
    new_count: int
    baseline_initialized: bool
    build_result: Optional[GululuBuildResult]


BaselineLoadResult = BaselineMissing | BaselineInvalid | BaselineOk


def execute_update(
    *,
    source_id: int,
    image_mode: str,
    task_id: str,
    folder: Path,
    client_factory: Callable[[], GululuClient],
    build_epub: Callable[..., GululuBuildResult],
    book_register: Callable[[str], str],
    shelf,
    books,
    progress: Callable[[str, int, int, str], None],
    cancel: Callable[[], bool],
) -> ExecutedUpdate:
    target = folder / "post.epub"
    partial = folder / "post.epub.part"
    baseline_path = folder / "snapshot.json"
    with client_factory() as client:
        prepared = prepare_update(
            client,
            source_id,
            target,
            baseline_path,
            image_mode,
            progress=progress,
            cancel=cancel,
        )
    if cancel():
        raise GululuCancelled("骨碌碌更新已取消")
    if not prepared.rebuild:
        write_baseline(
            baseline_path,
            source_id,
            prepared.snapshot,
            prepared.image_mode,
        )
        detail = "已是最新"
        if prepared.baseline_initialized:
            detail += "；已建立增量基线"
        return ExecutedUpdate(
            book_id_for_target(shelf, target),
            detail,
            0,
            prepared.baseline_initialized,
            None,
        )

    progress("epub", 0, 0, "正在生成更新后的 EPUB")
    snapshot = prepared.snapshot
    result = build_epub(
        detail=snapshot.detail,
        floor_index=snapshot.floor_index,
        chapter_index=snapshot.chapter_index,
        floors=snapshot.floors,
        comments_by_floor={},
        output_path=partial,
        image_mode=prepared.image_mode,
        progress=progress,
        cancel=cancel,
        fetch_cover=True,
    )
    if cancel():
        raise GululuCancelled("骨碌碌更新已取消")
    book_id = replace_and_register(
        target,
        partial,
        task_id,
        book_register=book_register,
        shelf=shelf,
        books=books,
    )
    write_baseline(
        baseline_path,
        source_id,
        snapshot,
        prepared.image_mode,
    )
    detail = (
        f"已更新 {prepared.new_count} 楼"
        if prepared.new_count
        else "已更新图片模式"
    )
    return ExecutedUpdate(
        book_id,
        detail,
        prepared.new_count,
        prepared.baseline_initialized,
        result,
    )


def book_id_for_target(shelf, target: Path) -> str:
    if shelf is None:
        return ""
    wanted = str(target.resolve()).casefold()
    for record in shelf.list_books():
        try:
            if str(Path(record.path).resolve()).casefold() == wanted:
                return record.id
        except OSError:
            continue
    return ""


def replace_and_register(
    target: Path,
    partial: Path,
    task_id: str,
    *,
    book_register: Callable[[str], str],
    shelf,
    books,
) -> str:
    existing_id = book_id_for_target(shelf, target)
    backup = target.with_name(f"{target.name}.backup-{task_id}")
    backup.unlink(missing_ok=True)
    if target.is_file():
        shutil.copy2(target, backup)
    if existing_id and books is not None:
        books.close(existing_id)
    try:
        partial.replace(target)
        book_id = book_register(str(target))
        if existing_id and book_id != existing_id:
            raise RuntimeError("骨碌碌更新后书籍 ID 发生变化，已拒绝替换")
        return book_id
    except Exception as replace_exc:  # noqa: BLE001 - restore the previous EPUB
        if existing_id and books is not None:
            books.close(existing_id)
        if backup.is_file():
            shutil.copy2(backup, target)
        if existing_id and target.is_file():
            try:
                book_register(str(target))
            except Exception as restore_exc:  # noqa: BLE001 - report both failures
                raise RuntimeError(
                    f"替换新 EPUB 失败：{replace_exc}；"
                    f"已恢复旧 EPUB，但重新注册失败：{restore_exc}"
                ) from replace_exc
        raise
    finally:
        backup.unlink(missing_ok=True)


def prepare_update(
    client: GululuClient,
    source_id: int,
    target: Path,
    baseline_path: Path,
    image_mode: str,
    *,
    progress: Optional[Callable[[str, int, int, str], None]] = None,
    cancel: Optional[Callable[[], bool]] = None,
) -> PreparedUpdate:
    mode = normalize_image_mode(image_mode)
    loaded = load_baseline(baseline_path, source_id)
    if isinstance(loaded, BaselineInvalid):
        raise GululuUpdateConflict(loaded.error + "；请完整重新导入")
    if isinstance(loaded, BaselineMissing):
        if not target.is_file():
            raise GululuUpdateConflict("本机没有可更新的骨碌碌 EPUB，请先完成导入")
        snapshot = client.fetch_snapshot(
            source_id,
            progress=progress,
            cancel=cancel,
            include_comments=False,
        )
        local_ids = list(read_epub_floor_ids(target))
        remote_ids = _floor_index_ids(snapshot.floor_index)
        if not local_ids:
            raise GululuUpdateConflict("现有 EPUB 未包含可识别楼层，请完整重新导入")
        if remote_ids[:len(local_ids)] != local_ids:
            raise GululuUpdateConflict(
                "现有 EPUB 与远端楼层历史不一致，请完整重新导入后建立增量基线"
            )
        new_count = len(remote_ids) - len(local_ids)
        return PreparedUpdate(snapshot, mode, new_count, new_count > 0, True)

    remote = client.fetch_index(source_id, progress=progress, cancel=cancel)
    plan = plan_incremental_update(loaded, remote)
    new_floors = client.fetch_floors(
        source_id,
        list(plan.new_floor_ids),
        progress=progress,
        cancel=cancel,
    ) if plan.new_floor_ids else []
    snapshot = merge_incremental_snapshot(loaded, remote, new_floors)
    rebuild = bool(plan.new_floor_ids) or mode != loaded.image_mode
    return PreparedUpdate(
        snapshot,
        mode,
        len(plan.new_floor_ids),
        rebuild,
        False,
    )


def load_baseline(path: Path, source_id: int) -> BaselineLoadResult:
    try:
        with path.open(encoding="utf-8") as handle:
            payload = json.load(handle)
    except FileNotFoundError:
        return BaselineMissing()
    except json.JSONDecodeError as exc:
        return BaselineInvalid(f"增量基线 JSON 损坏：{exc}")
    except OSError as exc:
        return BaselineInvalid(f"增量基线无法读取：{exc}")
    if not isinstance(payload, dict) or payload.get("version") != 1:
        return BaselineInvalid("增量基线版本无效")
    if payload.get("source_id") != source_id:
        return BaselineInvalid("增量基线来源与当前书籍不一致")
    try:
        image_mode = normalize_image_mode(payload.get("image_mode"))
    except ValueError as exc:
        return BaselineInvalid(str(exc))
    fields = ("detail", "floor_index", "chapter_index", "floors")
    if not isinstance(payload.get("detail"), dict) or any(
        not isinstance(payload.get(name), list) for name in fields[1:]
    ):
        return BaselineInvalid("增量基线字段格式错误")
    snapshot = GululuSnapshot(
        detail=payload["detail"],
        floor_index=payload["floor_index"],
        chapter_index=payload["chapter_index"],
        floors=payload["floors"],
    )
    error = _snapshot_error(snapshot, source_id)
    if error:
        return BaselineInvalid(error)
    return BaselineOk(snapshot, image_mode)


def write_baseline(
    path: Path,
    source_id: int,
    snapshot: GululuSnapshot,
    image_mode: str,
) -> None:
    mode = normalize_image_mode(image_mode)
    error = _snapshot_error(snapshot, source_id)
    if error:
        raise ValueError(error)
    path.parent.mkdir(parents=True, exist_ok=True)
    atomic_write_json(path, {
        "version": 1,
        "source_id": source_id,
        "image_mode": mode,
        "detail": snapshot.detail,
        "floor_index": snapshot.floor_index,
        "chapter_index": snapshot.chapter_index,
        "floors": snapshot.floors,
    })


def plan_incremental_update(
    baseline: BaselineOk,
    remote: GululuIndex,
) -> IncrementalPlan:
    old_ids = _floor_index_ids(baseline.snapshot.floor_index)
    remote_ids = _floor_index_ids(remote.floor_index)
    if remote_ids[:len(old_ids)] != old_ids:
        raise GululuUpdateConflict(
            "远端旧楼层已删除、重排或替换，请完整重新导入后再建立增量基线"
        )
    return IncrementalPlan(tuple(remote_ids[len(old_ids):]))


def merge_incremental_snapshot(
    baseline: BaselineOk,
    remote: GululuIndex,
    new_floors: list[dict],
) -> GululuSnapshot:
    plan = plan_incremental_update(baseline, remote)
    new_by_id = {
        item.get("id"): item
        for item in new_floors
        if isinstance(item, dict) and isinstance(item.get("id"), int)
    }
    if set(new_by_id) != set(plan.new_floor_ids):
        raise GululuUpdateConflict("新增楼层正文与远端索引不一致，请稍后重试")
    old_by_id = {
        item.get("id"): item
        for item in baseline.snapshot.floors
        if isinstance(item, dict) and isinstance(item.get("id"), int)
    }
    merged_by_id = {**old_by_id, **new_by_id}
    remote_ids = _floor_index_ids(remote.floor_index)
    missing = [floor_id for floor_id in remote_ids if floor_id not in merged_by_id]
    if missing:
        raise GululuUpdateConflict(f"本地增量基线缺少楼层正文：{missing[0]}")
    return GululuSnapshot(
        detail=remote.detail,
        floor_index=remote.floor_index,
        chapter_index=remote.chapter_index,
        floors=[merged_by_id[floor_id] for floor_id in remote_ids],
    )


def read_epub_floor_ids(path: Path) -> tuple[int, ...]:
    """Read generated floor anchors for the one-time legacy baseline migration."""
    try:
        with zipfile.ZipFile(path) as archive:
            names = sorted(
                name for name in archive.namelist()
                if name.startswith("EPUB/chapters/") and name.endswith(".xhtml")
            )
            floor_ids = []
            for name in names:
                root = ET.fromstring(archive.read(name))
                for element in root.iter():
                    anchor = str(element.attrib.get("id") or "")
                    if anchor.startswith("floor-") and anchor[6:].isdigit():
                        floor_ids.append(int(anchor[6:]))
            return tuple(floor_ids)
    except (OSError, zipfile.BadZipFile, ET.ParseError) as exc:
        raise GululuUpdateConflict(f"无法读取现有 EPUB 楼层基线：{exc}") from exc


def _floor_index_ids(floor_index: list[dict]) -> list[int]:
    ids = []
    for item in floor_index:
        if not isinstance(item, dict) or not isinstance(item.get("floorId"), int):
            raise GululuUpdateConflict("楼层索引格式错误")
        ids.append(item["floorId"])
    if len(ids) != len(set(ids)):
        raise GululuUpdateConflict("楼层索引包含重复 ID")
    return ids


def _snapshot_error(snapshot: GululuSnapshot, source_id: int) -> str:
    if snapshot.detail.get("bookId") != source_id:
        return "增量基线书籍 ID 不一致"
    try:
        floor_ids = _floor_index_ids(snapshot.floor_index)
    except GululuUpdateConflict as exc:
        return str(exc)
    body_ids = [
        item.get("id")
        for item in snapshot.floors
        if isinstance(item, dict) and isinstance(item.get("id"), int)
    ]
    if body_ids != floor_ids:
        return "增量基线楼层索引与正文顺序不一致"
    return ""
