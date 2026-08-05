"""用户设置持久化（主题/字号/窗口尺寸等）。"""
import copy
import json
import threading
from pathlib import Path
from typing import Any

from .storage import atomic_write_json

DEFAULTS: dict[str, Any] = {
    "settings_version": 2,
    "theme": "dark",  # 对齐 Readest：深色为主
    "font_size": 18,
    "line_height": 1.8,
    "font_family": "reader",
    "custom_font": "sys:weidqczfkyxk.ttf",  # 内置默认字体
    "book_fonts": {},
    "page_width": 1.0,
    "bars_pinned": False,
    "pagination": True,  # 分页渲染模式（False=整章滚动）
    "dual_page": False,   # 横屏双页：分页模式下左右两页并排，按整页跨翻页
    "auto_dual": True,    # 自动双页：分页模式下横屏宽窗自动双页（flow/epub.js Auto spread）
    "shelf_view": "grid",  # 书架视图：grid | list
    "shelf_sort": "recent",  # 书架排序：recent | title | author | added
    "margin_px": 40,  # 分页阅读边距
    "gap_px": 28,  # 分页列间沟槽
    "brightness": 0.0,  # 亮度遮罩 0~0.7
    "rsvp_rate": 300,  # 速读字/分钟
    "autoscroll_speed": 2.0,  # 自动滚动速率
    "show_ruler": False,
    "show_statusbar": True,
    # 自定义快捷键：动作 → KeyboardEvent.key 字符串
    "shortcuts": {
        "next_page": "ArrowRight",
        "prev_page": "ArrowLeft",
        "next_chapter": "ArrowDown",
        "prev_chapter": "ArrowUp",
        "toggle_theme": "t",
        "toggle_sidebar": "s",
        "toggle_bars": "b",
        "bookmark": "m",
        "help": "?",
    },
    "window_size": [1024, 720],
    "last_open_book": None,
}

# 老版本设置一次性迁移：缺失 settings_version 时启用新默认值
_LEGACY_DEFAULTS = {
    "custom_font": "sys:weidqczfkyxk.ttf",
    "pagination": True,
    "settings_version": 2,
}


class Settings:
    """扁平键值设置，update 接受部分补丁，原子写盘。"""

    def __init__(self, file: Path):
        self._file = file
        self._lock = threading.RLock()
        self._data: dict[str, Any] = copy.deepcopy(DEFAULTS)

    def load(self) -> None:
        try:
            with open(self._file, encoding="utf-8") as f:
                data = json.load(f)
            for k in DEFAULTS:
                if k in data and isinstance(data[k], type(DEFAULTS[k])):
                    self._data[k] = data[k]
            if "settings_version" not in data:
                # 旧版设置文件：一次性切到新默认值（分页翻页 + 内置默认字体）
                self._data.update(_LEGACY_DEFAULTS)
                self.save()
        except (OSError, json.JSONDecodeError, AttributeError):
            pass

    def save(self) -> None:
        atomic_write_json(self._file, self._data)

    def get(self, key: str) -> Any:
        with self._lock:
            return copy.deepcopy(self._data.get(key))

    def get_all(self) -> dict[str, Any]:
        with self._lock:
            return copy.deepcopy(self._data)

    def update(self, patch: dict) -> None:
        with self._lock:
            for k, v in patch.items():
                if k in DEFAULTS and isinstance(v, type(DEFAULTS[k])):
                    self._data[k] = v
            self.save()
