"""楼层导出服务：把指定楼层渲染为 PNG/WebP 图片（补档/分享用）。

单飞任务由 TaskManager（lane=floor_export）承载，状态可轮询；
渲染由 Playwright 脚本执行（scripts/floor_export_render.js），
页面走本地 HTTP 服务的 /book/ 章节地址，图片/CSS 复用阅读链路。
"""
import json
import logging
import os
import re
import subprocess
import threading
import uuid
from pathlib import Path
from typing import Callable, Optional

from . import dialogs
from .book_manager import BookManager
from .errors import ApiError, ErrorCode
from .native_book import load_floors, load_meta
from .shelf import Shelf
from .tasks import TaskCancelled, TaskManager, TaskProgress, TaskStatus

log = logging.getLogger("floor_export")

LANE = "floor_export"

_THEME_COLORS = {
    "light": {"bg": "#ffffff", "fg": "#201a15", "accent": "#8b5a2b"},
    "sepia": {"bg": "#f4ecd8", "fg": "#5b4636", "accent": "#8b5a2b"},
    "dark": {"bg": "#1e1e1e", "fg": "#d0d0d0", "accent": "#5ba3d9"},
}

_OVERRIDE_CSS = """
:root {
  --reader-bg: __BG__;
  --reader-fg: __FG__;
  --reader-accent: __ACCENT__;
}
.nga-floor, .gululu-floor {
  border: 1px solid color-mix(in srgb, var(--reader-fg, #222) 18%, transparent) !important;
  border-left: 4px solid var(--reader-accent, #77bbee) !important;
  background: color-mix(in srgb, var(--reader-fg, #222) 6%, transparent) !important;
  color: var(--reader-fg, #222) !important;
  padding: 12px 14px !important;
  margin: 14px 0 !important;
  border-radius: 2px !important;
  box-sizing: border-box !important;
}
.floor-head {
  color: color-mix(in srgb, var(--reader-fg, #222) 62%, transparent) !important;
  border-bottom: 1px dotted color-mix(in srgb, var(--reader-fg, #222) 18%, transparent) !important;
  display: flex !important;
  align-items: baseline !important;
  font-size: 0.82em !important;
  gap: 0.55em !important;
  margin: 0 0 8px !important;
  padding: 0 0 6px !important;
}
.floor-head .lou, .floor-head .floor-number {
  color: var(--reader-accent, #77bbee) !important;
  font-weight: 700 !important;
}
.nga-comment, .gululu-comment {
  background: color-mix(in srgb, var(--reader-fg, #222) 6%, transparent) !important;
  border: 1px solid color-mix(in srgb, var(--reader-fg, #222) 18%, transparent) !important;
}
blockquote.nga-quote, .gululu-assistant-quote {
  border-left: 3px solid var(--reader-accent, #77bbee) !important;
  background: color-mix(in srgb, var(--reader-fg, #222) 6%, transparent) !important;
}
body {
  background: var(--reader-bg, #ffffff) !important;
  color: var(--reader-fg, #222) !important;
}
"""


def _safe_filename(name: str) -> str:
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "", str(name or "").strip())
    name = re.sub(r"\s+", " ", name).strip(" .")
    return name[:80]


def _find_render_script() -> str:
    return str(Path(__file__).resolve().parent.parent / "scripts" / "floor_export_render.js")


def _find_chromium() -> Optional[str]:
    """在常见 Playwright 浏览器目录中寻找可用的 Chromium。"""
    base = os.environ.get("LOCALAPPDATA") or os.environ.get("USERPROFILE") or ""
    roots = [str(Path(base) / "ms-playwright")] if base else []
    if os.environ.get("USERPROFILE"):
        roots.append(str(Path(os.environ["USERPROFILE"]) / "AppData" / "Local" / "ms-playwright"))
    for root in roots:
        if not Path(root).is_dir():
            continue
        try:
            for name in sorted(os.listdir(root), reverse=True):
                p = Path(root) / name
                if name.startswith("chromium_headless_shell-"):
                    exe = p / "chrome-headless-shell-win64" / "chrome-headless-shell.exe"
                elif name.startswith("chromium-"):
                    exe = p / "chrome-win64" / "chrome.exe"
                else:
                    continue
                if exe.is_file():
                    return str(exe)
        except OSError:
            continue
    return None


class FloorExportService:
    """楼层导出：单飞/进度/取消由 TaskManager 承载，状态可轮询。"""

    def __init__(
        self,
        shelf: Shelf,
        books: BookManager,
        folder_picker: Optional[Callable[[str], str]] = None,
        task_manager: Optional[TaskManager] = None,
        server_port: int = 0,
    ):
        self._shelf = shelf
        self._books = books
        self._folder_picker = folder_picker or dialogs.pick_folder
        self._tasks = task_manager or TaskManager(lanes={LANE: 1})
        self._lock = threading.Lock()
        self._current_task: Optional[str] = None
        self.server_port = server_port
        self._status = {
            "running": False,
            "stage": "idle",
            "current": 0,
            "total": 0,
            "detail": "",
            "files": [],
            "dest": "",
            "error": "",
            "image_failed": 0,
            "image_total": 0,
        }

    def status(self) -> dict:
        with self._lock:
            return dict(self._status)

    def start(
        self,
        book_id: str,
        floors: list[int],
        theme: str = "light",
        fmt: str = "png",
        scale: float = 2.0,
        output_dir: str = "",
        no_images: bool = False,
        theme_colors: Optional[dict] = None,
        reader_style: Optional[dict] = None,
    ) -> dict:
        if theme not in _THEME_COLORS:
            raise ApiError(ErrorCode.BOOK_INVALID, "主题仅支持 light / sepia / dark")
        if fmt not in ("png", "webp"):
            raise ApiError(ErrorCode.BOOK_INVALID, "格式仅支持 png / webp")
        if scale not in (1, 1.5, 2, 3):
            raise ApiError(ErrorCode.BOOK_INVALID, "倍率仅支持 1 / 1.5 / 2 / 3")
        if theme_colors is not None:
            for key in ("bg", "fg", "accent"):
                if not theme_colors.get(key):
                    raise ApiError(ErrorCode.BOOK_INVALID, "自定义主题颜色缺少字段")
        if not floors:
            raise ApiError(ErrorCode.BOOK_INVALID, "请选择要导出的楼层")
        rec = self._shelf.get(book_id)
        if rec is None:
            raise ApiError(ErrorCode.BOOK_NOT_FOUND, "书籍不存在")
        task_id = f"floor-{uuid.uuid4().hex[:12]}"
        if not self._tasks.start(LANE, task_id):
            raise ApiError(ErrorCode.SERVICE_UNAVAILABLE, "已有楼层导出任务在运行")
        with self._lock:
            self._current_task = task_id
            self._status.update(
                running=True, stage="prepare", detail="正在准备楼层…",
                current=0, total=len(floors), files=[], dest="", error="",
                image_failed=0, image_total=0,
            )
        t = threading.Thread(
            target=self._run_task,
            args=(rec, floors, theme, fmt, scale, output_dir, no_images, theme_colors, reader_style, task_id),
            daemon=True,
            name="floor-export",
        )
        t.start()
        return {"ok": True}

    def floor_list(self, book_id: str) -> dict:
        """返回可导出楼层列表（供导出页/快速分享使用）。"""
        rec = self._shelf.get(book_id)
        if rec is None:
            raise ApiError(ErrorCode.BOOK_NOT_FOUND, "书籍不存在")
        book = self._open_book(rec)
        if rec.nga_tid:
            floors = load_floors(Path(rec.path))
            return {
                "kind": "nga",
                "floors": [
                    {"num": int(f.get("lou", -1)), "label": "主楼" if int(f.get("lou", -1)) == 0 else f"{f.get('lou')}楼"}
                    for f in floors
                ],
            }
        snapshot_path = Path(rec.path).parent / "snapshot.json"
        if not snapshot_path.is_file():
            return {"kind": "gululu", "floors": []}
        with snapshot_path.open(encoding="utf-8") as fh:
            snapshot = json.load(fh)
        return {
            "kind": "gululu",
            "floors": [
                {"num": int(item["floorNum"]), "label": f"第{item['floorNum']}楼 {item.get('name', '')}".strip()}
                for item in (snapshot.get("floor_index") or [])
                if item.get("floorNum") is not None
            ],
        }

    def cancel(self) -> dict:
        with self._lock:
            task_id = self._current_task
        if task_id is None:
            raise ApiError(ErrorCode.SERVICE_UNAVAILABLE, "没有进行中的楼层导出任务")
        self._tasks.cancel(task_id)
        return {"ok": True}

    def open_dest(self) -> dict:
        import os

        dest = self.status().get("dest") or ""
        if not dest or not Path(dest).is_dir():
            raise ApiError(ErrorCode.EXPORT_FAILED, "没有可打开的导出文件夹")
        try:
            os.startfile(dest)
            return {"ok": True}
        except OSError as e:
            raise ApiError(ErrorCode.EXPORT_FAILED, str(e))

    # ---------- 后台执行 ----------

    def _run_task(self, rec, floors, theme, fmt, scale, output_dir, no_images, theme_colors, reader_style, task_id) -> None:
        def on_progress(p: TaskProgress) -> None:
            self._set(stage=p.stage, current=p.current, total=p.total, detail=p.message)

        try:
            status = self._tasks.run(
                LANE,
                task_id,
                lambda report: self._run(rec, floors, theme, fmt, scale, output_dir, no_images, theme_colors, reader_style, report),
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

    def _run(self, rec, floors, theme, fmt, scale, output_dir, no_images, theme_colors, reader_style, report) -> None:
        def step(stage: str, current: int = 0, total: int = 0, detail: str = "") -> None:
            self._set(stage=stage, current=current, total=total, detail=detail)
            report(TaskProgress(current=current, total=total, stage=stage, message=detail))

        try:
            book = self._open_book(rec)
            kind = "nga" if rec.nga_tid else "gululu"
            step("prepare", detail="正在解析楼层…")
            mapping = self._resolve_floors(rec, book, kind, floors)
            if output_dir:
                out_dir = Path(output_dir)
            else:
                step("pick", detail="请选择导出文件夹…")
                picked = self._folder_picker("选择楼层导出文件夹")
                if not picked:
                    raise TaskCancelled("floor export cancelled by user")
                out_dir = Path(picked)
            out_dir.mkdir(parents=True, exist_ok=True)
            base = _safe_filename(rec.title) or _safe_filename(book.title) or "未命名安科"
            colors = theme_colors or _THEME_COLORS[theme]
            theme_css = _OVERRIDE_CSS.replace("__BG__", colors["bg"]).replace("__FG__", colors["fg"]).replace("__ACCENT__", colors["accent"])
            reader = reader_style or {}
            font_face = str(reader.get("font_face_css") or "")
            font_family = str(reader.get("font_family") or '"Segoe UI", "Microsoft YaHei", serif')
            try:
                font_size = max(12, min(32, int(reader.get("font_size") or 18)))
            except (TypeError, ValueError):
                font_size = 18
            try:
                line_height = max(1.2, min(3.0, float(reader.get("line_height") or 1.8)))
            except (TypeError, ValueError):
                line_height = 1.8
            try:
                page_width = max(0.6, min(2.0, float(reader.get("page_width") or 1.0)))
            except (TypeError, ValueError):
                page_width = 1.0
            typography_css = f"""
body {{
  font-family: {font_family} !important;
  font-size: {font_size}px !important;
  line-height: {line_height} !important;
}}
img {{ max-width: 100% !important; height: auto !important; }}
.nga-floor, .gululu-floor {{
  max-width: {46 * page_width:.1f}em !important;
  margin-left: auto !important;
  margin-right: auto !important;
}}
"""
            theme_css = font_face + theme_css + typography_css
            jobs = []
            for i, floor_num in enumerate(floors, 1):
                info = mapping.get(floor_num)
                if info is None:
                    raise RuntimeError(f"第 {floor_num} 楼不存在或无法定位")
                ext = fmt
                out_name = f"{base}_第{floor_num}楼.{ext}"
                out_path = out_dir / out_name
                if out_path.exists():
                    out_path = out_dir / f"{base}_第{floor_num}楼_{i}.{ext}"
                jobs.append({
                    "url": info["url"],
                    "selector": info["selector"],
                    "out": str(out_path),
                    "noImages": bool(no_images),
                })
                step("prepare", i, len(floors), f"正在准备第 {floor_num} 楼…")
            step("render", detail="正在渲染楼层…")
            results = self._render(jobs, fmt, scale, theme_css, report)
            files = []
            image_failed = 0
            image_total = 0
            ok_count = 0
            for j, r in zip(jobs, results):
                if r.get("ok"):
                    ok_count += 1
                    files.append(Path(r["out"]).name)
                else:
                    log.warning("楼层导出失败：%s -> %s", j["out"], r.get("error", ""))
                image_failed += int(r.get("failedImages") or 0)
                image_total += int(r.get("totalImages") or 0)
            if ok_count == 0:
                raise RuntimeError("楼层渲染失败，请检查日志或重试")
            self._set(
                running=False, stage="done", detail=f"已导出 {ok_count}/{len(jobs)} 层",
                files=files, dest=str(out_dir), image_failed=image_failed,
                image_total=image_total,
            )
        except TaskCancelled:
            self._set(running=False, stage="cancelled", detail="已取消", error="")
            raise
        except Exception as e:  # noqa: BLE001
            log.exception("楼层导出失败")
            self._set(running=False, stage="error", detail="", error=str(e), files=[], dest="")
            raise

    def _open_book(self, rec):
        try:
            return self._books.open(rec.id)
        except KeyError:
            return self._books.register(rec.path)

    def _resolve_floors(self, rec, book, kind, floors) -> dict[int, dict]:
        if kind == "nga":
            return self._resolve_nga_floors(rec, book, floors)
        return self._resolve_gululu_floors(rec, book, floors)

    def _resolve_nga_floors(self, rec, book, floors) -> dict[int, dict]:
        root = Path(rec.path)
        meta = load_meta(root)
        all_floors = load_floors(root)
        chapters = meta.get("chapters", [])
        by_lou = {int(f.get("lou", -1)): f for f in all_floors}
        mapping = {}
        for lou in floors:
            f = by_lou.get(int(lou))
            if f is None:
                continue
            chapter = None
            for i, ch in enumerate(chapters):
                if int(ch.get("first_lou", -1)) <= int(lou) <= int(ch.get("last_lou", -1)):
                    chapter = i
                    break
            if chapter is None:
                continue
            mapping[lou] = {
                "url": f"http://127.0.0.1:{self.server_port}/book/{rec.id}/{chapters[chapter]['file']}",
                "selector": f"#pid{f.get('pid')}",
            }
        return mapping

    def _resolve_gululu_floors(self, rec, book, floors) -> dict[int, dict]:
        snapshot_path = Path(rec.path).parent / "snapshot.json"
        if not snapshot_path.is_file():
            return {}
        with snapshot_path.open(encoding="utf-8") as fh:
            snapshot = json.load(fh)
        chapter_index = snapshot.get("chapter_index") or []
        floor_index = snapshot.get("floor_index") or []
        floor_by_num = {int(item["floorNum"]): int(item["floorId"]) for item in floor_index if item.get("floorId")}
        chapters = book.chapters
        mapping = {}
        for num in floors:
            floor_id = floor_by_num.get(int(num))
            if floor_id is None:
                continue
            chapter = 0
            for i, ch in enumerate(chapter_index):
                if int(ch.get("floor", 1)) <= int(num):
                    chapter = i
                else:
                    break
            if chapter >= len(chapters):
                chapter = len(chapters) - 1
            mapping[num] = {
                "url": f"http://127.0.0.1:{self.server_port}/book/{rec.id}/{chapters[chapter].href}",
                "selector": f"#floor-{floor_id}",
            }
        return mapping

    def _render(self, jobs, fmt, scale, theme_css, report) -> list[dict]:
        config = {
            "format": fmt,
            "deviceScaleFactor": scale,
            "themeCss": theme_css,
            "jobs": jobs,
        }
        script = _find_render_script()
        chromium = _find_chromium()
        env = dict(os.environ)
        if chromium:
            env["ANKESHELF_CHROMIUM"] = chromium
        config_path = Path(os.environ.get("TEMP", ".")) / f"floor_export_{uuid.uuid4().hex}.json"
        config_path.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
        try:
            proc = subprocess.run(
                ["node", script, str(config_path)],
                capture_output=True,
                timeout=1800,
                env=env,
                cwd=str(Path(script).parent.parent),
            )
            if proc.returncode != 0:
                raise RuntimeError(proc.stderr.decode("utf-8", errors="replace")[-2000:])
            data = json.loads(proc.stdout.decode("utf-8", errors="replace"))
            results = data.get("results") or []
            for i, r in enumerate(results):
                report(TaskProgress(
                    current=i + 1, total=len(jobs), stage="render",
                    message=f"正在渲染 {i + 1}/{len(jobs)}",
                ))
            return results
        finally:
            try:
                config_path.unlink()
            except OSError:
                pass

    def _set(self, **kw) -> None:
        with self._lock:
            self._status.update(kw)
