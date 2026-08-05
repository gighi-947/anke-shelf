"""原生增量书容器：NGA 连载热更新的运行时格式。

结构（nga_library/<帖子目录>/book/）：
  meta.json     元数据（tid/章节列表/分组参数/book_id）
  floors.json   全部楼层原始数据（含评论），供增量追加与导出重建
  chapters/*.xhtml  章节文件，只追加、不重写（保证 text_offset 稳定）

阅读器通过 NativeBook 读取；该目录本身可被 BookManager 注册为“书”。
"""
import hashlib
import json
import posixpath
import threading
from datetime import datetime
from pathlib import Path
from typing import Optional

from .epub import SpineItem, TocEntry, decode_text, EpubError
from .paths import nga_library_dir
from .storage import atomic_write_text

META_NAME = "meta.json"
FLOORS_NAME = "floors.json"
CHAPTERS_DIR = "chapters"
FORMAT = "ank-native/1"


def native_dir_for(folder_name: str) -> Path:
    return nga_library_dir() / folder_name / "book"


def is_native_dir(path) -> bool:
    p = Path(path)
    return p.is_dir() and (p / META_NAME).is_file()


def _safe_rel(name: str) -> Optional[str]:
    if not name or "\\" in name or name.startswith("/"):
        return None
    norm = posixpath.normpath(name)
    if norm.startswith("..") or "/.." in norm or ".." in norm.split("/"):
        return None
    return norm


# ---------------- 阅读器侧 ----------------


class NativeBook:
    """一本原生目录书。接口与 EpubBook 对齐，供 BookManager/Server 共用。"""

    def __init__(self, path: str):
        self.path = str(path)
        self.id = hashlib.md5(self.path.encode("utf-8")).hexdigest()
        self.title = ""
        self.author = ""
        self.language = "zh"
        self.chapters: list[SpineItem] = []
        self.toc: list[TocEntry] = []
        self.toc_map: dict[str, str] = {}
        self._root: Optional[Path] = None
        self._meta: dict = {}

    def open(self) -> "NativeBook":
        root = Path(self.path).resolve()
        meta_path = root / META_NAME
        if not meta_path.is_file():
            raise EpubError("不是有效的原生书（缺少 meta.json）")
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            raise EpubError(f"原生书元数据损坏：{e}") from e
        self._meta = meta
        self._root = root
        self.id = str(meta.get("book_id") or self.id)
        self.title = meta.get("title", "")
        self.author = meta.get("author", "")
        chapters = meta.get("chapters", [])
        self.chapters = [
            SpineItem(index=i, idref=f"ch{i}", href=c["file"],
                      linear=True, media_type="application/xhtml+xml")
            for i, c in enumerate(chapters)
        ]
        self.toc = [
            TocEntry(label=c.get("title", f"第 {i + 1} 章"),
                     href=c["file"], spine_index=i)
            for i, c in enumerate(chapters)
        ]
        self.toc_map = {c["file"]: c.get("title", f"第 {i + 1} 章")
                        for i, c in enumerate(chapters)}
        return self

    def read_file(self, name: str) -> Optional[bytes]:
        if self._root is None:
            return None
        safe = _safe_rel(name)
        if safe is None:
            return None
        p = (self._root / safe).resolve()
        try:
            p.relative_to(self._root)
        except ValueError:
            return None
        try:
            return p.read_bytes()
        except OSError:
            return None

    def chapter_text(self, index: int) -> Optional[str]:
        if not 0 <= index < len(self.chapters):
            return None
        data = self.read_file(self.chapters[index].href)
        return decode_text(data) if data is not None else None

    def chapter_title(self, index: int) -> str:
        if not 0 <= index < len(self.chapters):
            return ""
        return self.toc_map.get(self.chapters[index].href, f"第 {index + 1} 章")

    def toc_spine_index(self, href: str) -> Optional[int]:
        h = href.split("#", 1)[0]
        for i, c in enumerate(self.chapters):
            if c.href == h:
                return i
        return None

    def get_cover_bytes(self) -> Optional[bytes]:
        return None

    def meta(self) -> dict:
        return dict(self._meta)

    def close(self) -> None:
        pass


# ---------------- 写入侧（热更新） ----------------


def load_meta(native_dir: Path) -> dict:
    return json.loads((native_dir / META_NAME).read_text(encoding="utf-8"))


def load_floors(native_dir: Path) -> list[dict]:
    p = native_dir / FLOORS_NAME
    if not p.is_file():
        return []
    return json.loads(p.read_text(encoding="utf-8"))


def save_floors(native_dir: Path, floors: list[dict]) -> None:
    atomic_write_text(native_dir / FLOORS_NAME, json.dumps(floors, ensure_ascii=False, indent=1))


def save_meta(native_dir: Path, meta: dict) -> None:
    atomic_write_text(native_dir / META_NAME, json.dumps(meta, ensure_ascii=False, indent=2))


def serialize_floor(f) -> dict:
    return {
        "pid": f.pid,
        "lou": f.lou,
        "timestamp": f.timestamp,
        "username": f.username,
        "user_id": f.user_id,
        "like_num": f.like_num,
        "raw_content": f.raw_content,
        "comments": [serialize_floor(c) for c in getattr(f, "comments", [])],
    }


def floor_from_dict(d: dict):
    from ngapost2md.models import Floor

    return Floor(
        lou=int(d.get("lou", -1)),
        pid=int(d.get("pid", 0)),
        timestamp=int(d.get("timestamp", 0)),
        username=d.get("username", ""),
        user_id=int(d.get("user_id", 0)),
        like_num=int(d.get("like_num", 0)),
        raw_content=d.get("raw_content", ""),
        comments=[floor_from_dict(c) for c in d.get("comments", [])],
    )


def _themes():
    from ngapost2md.format_html import NGA_THEME_DARK, NGA_THEME_LIGHT

    return NGA_THEME_LIGHT, NGA_THEME_DARK


def _css(theme: str) -> str:
    from ngapost2md.epub import _build_css

    return _build_css(theme)


def _render_floor_html(f, theme: dict, img_src) -> str:
    import html as html_mod

    from ngapost2md.format import ts2t
    from ngapost2md.format_html import render_content_html, set_no_images

    no_images = img_src is None
    if no_images:
        set_no_images(True)
        img_src = lambda url: url

    floor_style = (
        f'border:1px solid {theme["border"]}; border-left:4px solid {theme["accent"]}; '
        f'padding:12px 14px; margin:14px 0; border-radius:2px;'
    )
    head_style = (
        f'color:{theme["muted"]}; font-size:.82em; border-bottom:1px dotted {theme["border"]}; '
        f'padding-bottom:6px; margin-bottom:8px;'
    )
    comment_style = (
        f'background:{theme["comment_bg"]}; border:1px solid {theme["border"]}; '
        f'padding:8px 10px; margin:6px 0 6px 14px; font-size:.92em;'
    )
    head = (
        f'<span class="lou" style="color:{theme["accent"]}; font-weight:bold;">{f.lou}楼</span> '
        f'· {f.like_num}赞 · {html_mod.escape(f.username)}({f.user_id}) · {ts2t(f.timestamp)}'
        f'<span class="pid"> · pid:{f.pid}</span>'
    )
    try:
        body = render_content_html(f.raw_content, None, img_src)
        out = (
            f'<div class="nga-floor" id="pid{f.pid}" style="{floor_style}">'
            f'<div class="floor-head" style="{head_style}">{head}</div>'
            f'<div class="floor-body">{body}</div></div>'
        )
        for c in getattr(f, "comments", []):
            if c.lou <= 0:
                continue
            c_body = render_content_html(c.raw_content, None, img_src)
            c_head = f'{c.lou}楼 · {html_mod.escape(c.username)}({c.user_id}) · {ts2t(c.timestamp)}'
            out += (
                f'<div class="nga-comment" style="{comment_style}">'
                f'<span class="comment-head" style="color:{theme["muted"]}; font-size:.8em; '
                f'display:block; margin-bottom:4px;">{c_head}</span>{c_body}</div>'
            )
        return out
    finally:
        if no_images:
            set_no_images(False)


def _chapter_html(title: str, body_html: str, theme: str) -> str:
    css = _css(theme)
    return (
        '<html xmlns="http://www.w3.org/1999/xhtml">'
        f'<head><meta charset="utf-8"/><title>{title}</title>'
        f'<style>{css}</style></head><body>{body_html}</body></html>'
    )


def _group_floors(valid: list, per_chapter: int) -> list[list]:
    """与 EPUB 相同的分组：主楼（pid==0）独占首章，其余每 per_chapter 楼一章。"""
    main = valid[0] if valid and valid[0].pid == 0 else None
    rest = valid[1:] if main else valid
    groups = [[main]] if main else []
    for i in range(0, len(rest), max(1, per_chapter)):
        groups.append(rest[i:i + max(1, per_chapter)])
    return [g for g in groups if g]


def _group_title(group: list) -> str:
    if group[0].pid == 0:
        return "序章 · 主楼"
    first, last = group[0].lou, group[-1].lou
    return f"第 {first} 楼" if first == last else f"第 {first}~{last} 楼"


def _img_src(image_mode: str):
    if image_mode == "none":
        return None
    return lambda url: url  # 热更新默认在线图片，图片保持原链接


def write_container(
    folder_name: str,
    tiezi,
    valid_floors: list,
    per_chapter: int,
    image_mode: str,
    theme: str,
    book_id: str,
) -> Path:
    """首次构建原生书容器（全量）。返回 native 目录。"""
    native_dir = native_dir_for(folder_name)
    chapters_dir = native_dir / CHAPTERS_DIR
    chapters_dir.mkdir(parents=True, exist_ok=True)
    light_theme, dark_theme = _themes()
    theme_colors = dark_theme if theme == "dark" else light_theme
    img_src = _img_src(image_mode)

    groups = _group_floors(valid_floors, per_chapter)
    chapters = []
    for gi, group in enumerate(groups):
        title = _group_title(group)
        body = "".join(_render_floor_html(f, theme_colors, img_src) for f in group)
        if group[0].pid == 0:
            body = f'<h1>{tiezi.title or ""}</h1>' + body
        file_name = f"{gi:04d}.xhtml"
        (chapters_dir / file_name).write_text(
            _chapter_html(title, body, theme), encoding="utf-8"
        )
        chapters.append({
            "file": f"chapters/{file_name}",
            "title": title,
            "floor_count": len(group),
            "first_lou": group[0].lou,
            "last_lou": group[-1].lou,
            "main": group[0].pid == 0,
        })

    meta = {
        "format": FORMAT,
        "book_id": book_id,
        "tid": int(getattr(tiezi, "tid", 0)),
        "author_id": int(getattr(tiezi, "author_id", 0)),
        "title": tiezi.title or "",
        "author": getattr(tiezi, "username", "") or "",
        "folder_name": folder_name,
        "per_chapter": max(1, int(per_chapter)),
        "image_mode": image_mode,
        "theme": theme,
        "chapters": chapters,
        "last_lou": valid_floors[-1].lou if valid_floors else 0,
        "created_time": getattr(tiezi, "created_time", ""),
        "updated_time": getattr(tiezi, "updated_time", ""),
    }
    save_meta(native_dir, meta)
    save_floors(native_dir, [serialize_floor(f) for f in valid_floors])
    return native_dir


def append_container(
    folder_name: str,
    new_floors: list,
    per_chapter: int,
    image_mode: str,
    theme: str,
    book_id: str,
) -> int:
    """把新楼层追加进已有原生书（只追加，不重写旧章节）。返回追加楼层数。"""
    native_dir = native_dir_for(folder_name)
    meta = load_meta(native_dir)
    floors = load_floors(native_dir)
    chapters_dir = native_dir / CHAPTERS_DIR
    light_theme, dark_theme = _themes()
    theme_colors = dark_theme if theme == "dark" else light_theme
    img_src = _img_src(image_mode)

    existing_pids = {int(f["pid"]) for f in floors}
    fresh = [f for f in new_floors if int(f.pid) not in existing_pids]
    if not fresh:
        return 0

    chapters = meta.setdefault("chapters", [])
    pending = list(fresh)
    # 优先填满最后一个普通章节（主楼章节不追加）
    if chapters:
        last = chapters[-1]
        if not last.get("main", False) and int(last.get("floor_count", 0)) < per_chapter:
            room = per_chapter - int(last["floor_count"])
            take, pending = pending[:room], pending[room:]
            if take:
                html = "".join(_render_floor_html(f, theme_colors, img_src) for f in take)
                path = native_dir / last["file"]
                text = path.read_text(encoding="utf-8")
                text = text.replace("</body>", html + "</body>")
                path.write_text(text, encoding="utf-8")
                last["floor_count"] = int(last["floor_count"]) + len(take)
                last["last_lou"] = take[-1].lou
                if take[-1].lou != last["first_lou"]:
                    last["title"] = f"第 {last['first_lou']}~{last['last_lou']} 楼"

    # 其余新楼层按 per_chapter 开新章节
    next_index = len(chapters)
    for i in range(0, len(pending), per_chapter):
        group = pending[i:i + per_chapter]
        title = _group_title(group)
        body = "".join(_render_floor_html(f, theme_colors, img_src) for f in group)
        file_name = f"{next_index:04d}.xhtml"
        (chapters_dir / file_name).write_text(
            _chapter_html(title, body, theme), encoding="utf-8"
        )
        chapters.append({
            "file": f"chapters/{file_name}",
            "title": title,
            "floor_count": len(group),
            "first_lou": group[0].lou,
            "last_lou": group[-1].lou,
            "main": False,
        })
        next_index += 1

    floors.extend(serialize_floor(f) for f in fresh)
    meta["last_lou"] = max(int(meta.get("last_lou", 0)), fresh[-1].lou)
    meta["updated_time"] = datetime.now().astimezone().isoformat(timespec="seconds")
    save_floors(native_dir, floors)
    save_meta(native_dir, meta)
    return len(fresh)


def rebuild_epub_for_native(folder_name: str, image_mode_override: str = "") -> Path:
    """导出时用 floors.json 全量重建 post.epub（在线模式下无需联网）。"""
    native_dir = native_dir_for(folder_name)
    meta = load_meta(native_dir)
    floor_dicts = load_floors(native_dir)
    from ngapost2md.models import Tiezi

    tiezi = Tiezi(
        tid=int(meta.get("tid", 0)),
        author_id=int(meta.get("author_id", 0)),
        title=meta.get("title", ""),
        username=meta.get("author", ""),
        folder_name=folder_name,
        floors=[floor_from_dict(d) for d in floor_dicts],
        max_lou=-1,
        assets={},
    )
    image_mode = image_mode_override or meta.get("image_mode", "online")
    if image_mode == "embedded":
        image_mode = "online"  # 热更新容器不维护嵌入资源，导出时回退在线图片

    from ngapost2md import config as config_mod
    from ngapost2md import epub as epub_mod

    from .nga_config import ensure_nga_config

    cfg = config_mod.load_config(str(ensure_nga_config()))
    cfg.output_path = str(nga_library_dir())
    cfg.epub_enabled = True
    cfg.epub_image_mode = "online" if image_mode == "online" else "embedded"
    cfg.epub_per_chapter = max(1, int(meta.get("per_chapter", 20)))
    cfg.epub_theme = "dark" if meta.get("theme") == "dark" else "light"
    cfg.epub_toc_pid = 0
    cfg.no_media = True
    cfg.no_images = image_mode == "none"
    return Path(epub_mod.build_epub(
        tiezi, cfg, per_chapter=cfg.epub_per_chapter,
        image_mode=cfg.epub_image_mode, no_images=cfg.no_images,
    ))
