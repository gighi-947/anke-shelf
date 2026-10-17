"""版本更新检查：静默拉取 GitHub Releases，网络失败不向用户显式报错。

书籍更新检查（进书轻量检查 / 书架徽标 / 每书自动开关）也在本模块：
handler 只透传给 ctx.update_check_service，业务语义见 app/update_check.py。
"""
from __future__ import annotations

import json
import urllib.request

from .. import __version__
from .common import ApiContext


def _parse_version(value: str) -> tuple[int, ...] | None:
    s = value.strip().lstrip("vV")
    try:
        parts = tuple(int(x) for x in s.split(".")[:3])
    except ValueError:
        return None
    return parts if parts else None


def _current() -> tuple[int, ...] | None:
    return _parse_version(__version__)


def check_update(ctx: ApiContext) -> dict:
    """返回 {has_update, latest_version, html_url}；任何网络/解析失败返回 has_update=False。"""
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/gighi-947/anke-shelf/releases?per_page=20",
            headers={"User-Agent": "AnkeShelf", "Accept": "application/vnd.github+json"},
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            releases = json.loads(resp.read().decode("utf-8"))
    except Exception:
        return {"has_update": False, "latest_version": "", "html_url": ""}

    current = _current()
    if current is None:
        return {"has_update": False, "latest_version": "", "html_url": ""}

    for release in releases:
        if not isinstance(release, dict):
            continue
        if release.get("draft") or release.get("prerelease"):
            continue
        tag = release.get("tag_name") or ""
        # Windows 版本 tag 形如 v1.6.3；android-v* 跳过。
        if tag.startswith("android-"):
            continue
        latest = _parse_version(tag)
        if latest is None:
            continue
        # Releases 按创建时间倒序；取到的第一个 Windows release 即为最新发布。
        has_update = latest > current
        return {
            "has_update": has_update,
            "latest_version": "v" + ".".join(str(x) for x in latest) if has_update else "",
            "html_url": release.get("html_url") or "" if has_update else "",
        }
    return {"has_update": False, "latest_version": "", "html_url": ""}


# ---------- 书籍更新检查（feat10：对齐 Android 进书检查 + 书架徽标） ----------

def check_book_update(ctx: ApiContext, book_id: str) -> dict:
    """单书轻量检查：{"state": "available"|"none"|"unavailable", "new_floors": int, "approximate": bool}。"""
    return ctx.update_check_service.check_book(book_id)


def get_update_badges(ctx: ApiContext) -> dict:
    """书架徽标快照：{"badges": {bookId: n}, "auto_check": {bookId: bool}, "revision": int}。

    auto_check 只含显式登记项（缺省 true 由前端兜底）。
    """
    store = ctx.update_check_service.store
    return {
        "badges": store.available_snapshot(),
        "auto_check": store.auto_check_snapshot(),
        "revision": store.revision,
    }


def set_auto_check(ctx: ApiContext, book_id: str, enabled: bool) -> dict:
    """每书「自动检查更新」开关；关闭时清除该书徽标。"""
    ctx.update_check_service.store.set_auto_check(book_id, bool(enabled))
    return {"ok": True}


def check_all_updates(ctx: ApiContext) -> dict:
    """触发书架批量检查（后台线程，防重入）：{"started": bool}。"""
    return ctx.update_check_service.check_all()
