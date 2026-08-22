"""Custom font registry: system fonts plus user-imported font files."""
import hashlib
import os
import sys
from pathlib import Path
from typing import Optional

from .paths import data_dir, web_dir

FONT_EXTS = {".ttf", ".otf", ".ttc", ".woff", ".woff2"}

# 旧逻辑名别名：内置字体由 TTF 换为 WOFF2 后，已保存设置里的
# sys:weidqczfkyxk.ttf 继续解析到新文件，老用户升级不丢字体。
_FONT_ALIASES = {"weidqczfkyxk.ttf": "weidqczfkyxk.woff2"}

# (label, filename) pairs; only entries that exist on the current machine are listed.
_SYSTEM_FONTS = [
    # 内置默认字体（WOFF2 无损压缩，canonical 源 assets/fonts）
    ("weidqczfkyxk", "weidqczfkyxk.woff2"),
    ("Microsoft YaHei", "msyh.ttc"),
    ("Microsoft YaHei Light", "msyhl.ttc"),
    ("SimSun", "simsun.ttc"),
    ("SimHei", "simhei.ttf"),
    ("KaiTi", "simkai.ttf"),
    ("FangSong", "simfang.ttf"),
    ("DengXian", "Deng.ttf"),
    ("DengXian Light", "DengL.ttf"),
    ("Consolas", "consola.ttf"),
    ("Times New Roman", "times.ttf"),
    ("Arial", "arial.ttf"),
    ("Georgia", "georgia.ttf"),
    ("Segoe UI", "segoeui.ttf"),
]


def fonts_dir() -> Path:
    d = data_dir() / "fonts"
    try:
        d.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    return d


def _system_root() -> Path:
    return Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"


def _assets_fonts_dir() -> Path:
    """内置字体 canonical 源：仓库根 assets/fonts（开发）或打包后 _MEIPASS/assets/fonts。"""
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        return Path(meipass) / "assets" / "fonts"
    return Path(__file__).resolve().parents[1] / "assets" / "fonts"


def _bundled_font(fname: str) -> Optional[Path]:
    """内置字体查找：web/fonts（旧布局）→ canonical 源（assets/fonts，双端单一副本）。"""
    p = web_dir() / "fonts" / fname
    if p.is_file():
        return p
    if fname == "weidqczfkyxk.woff2":
        c = _assets_fonts_dir() / "LXGWWenKai-Regular.woff2"
        if c.is_file():
            return c
    return None


def list_fonts() -> list[dict]:
    out: list[dict] = []
    root = _system_root()
    for label, fname in _SYSTEM_FONTS:
        p = root / fname
        if _bundled_font(fname) is not None:
            out.append({
                "key": "sys:" + fname,
                "label": label + "（内置）",
                "url": "/font/system/" + fname,
                "scope": "system",
            })
        elif p.is_file():
            out.append({
                "key": "sys:" + fname,
                "label": label,
                "url": "/font/system/" + fname,
                "scope": "system",
            })
    try:
        custom_fonts = sorted(fonts_dir().iterdir())
    except OSError:
        custom_fonts = []
    for p in custom_fonts:
        if p.suffix.lower() in FONT_EXTS:
            out.append({
                "key": "custom:" + p.name,
                "label": p.stem,
                "url": "/font/custom/" + p.name,
                "scope": "custom",
            })
    return out


def register_font(src: str) -> dict:
    src_path = Path(src)
    if src_path.suffix.lower() not in FONT_EXTS:
        raise ValueError("unsupported font file type")
    data = src_path.read_bytes()
    digest = hashlib.sha1(data).hexdigest()[:12]
    dest = fonts_dir() / (digest + src_path.suffix.lower())
    if not dest.exists():
        dest.write_bytes(data)
    return {
        "key": "custom:" + dest.name,
        "label": dest.stem,
        "url": "/font/custom/" + dest.name,
        "scope": "custom",
    }


def resolve_font_file(kind: str, name: str) -> Optional[Path]:
    """Resolve a font request to a real file, using a strict allowlist."""
    safe_name = _FONT_ALIASES.get(Path(name).name, Path(name).name)
    if kind == "system":
        for _, fname in _SYSTEM_FONTS:
            if fname.lower() == safe_name.lower():
                bundled = _bundled_font(fname)
                if bundled is not None:
                    return bundled
                p = _system_root() / fname
                return p if p.is_file() else None
        return None
    if kind == "custom":
        p = fonts_dir() / safe_name
        if p.is_file() and p.suffix.lower() in FONT_EXTS:
            return p
    return None
