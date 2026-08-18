"""用户设置持久化（主题/字号/窗口尺寸等）。"""
import copy
import json
import logging
import threading
from pathlib import Path
from typing import Any

from .migrations import run_migrations
from .storage import atomic_write_json, load_json_file

log = logging.getLogger("settings")

DEFAULTS: dict[str, Any] = {
    "settings_version": 3,
    "theme": "dark",  # 对齐 Readest：深色为主
    "theme_mode": "",  # 主题模式：""=跟随 theme；system=跟随系统；light/sepia/dark=固定模式
    "font_size": 18,
    "line_height": 1.8,
    "font_family": "reader",
    "custom_font": "sys:weidqczfkyxk.ttf",  # 内置默认字体
    "book_fonts": {},
    "custom_bg": "",        # 自定义背景色（空=跟随主题）
    "custom_primary": "",   # 自定义主题色（空=跟随主题）
    "custom_accent": "",    # 自定义强调色（空=跟随主题/主题色）
    "custom_text": "",      # 自定义文字色（空=跟随主题；仅作用于默认黑/白文字）
    "page_width": 1.0,
    "bars_pinned": False,
    "pagination": False,  # 翻页方式（False=整章滚动，默认滚动阅读）
    "dual_page": False,   # 横屏双页：分页模式下左右两页并排，按整页跨翻页
    "auto_dual": True,    # 自动双页：分页模式下横屏宽窗自动双页（flow/epub.js Auto spread）
    "shelf_view": "grid",  # 书架视图：grid | list
    "shelf_sort": "recent",  # 书架排序：recent | title | author | added
    "hide_title_brackets": False,  # 隐藏书名首个【…】前缀（仅显示层剥离，存储原名不变）
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
        "toggle_fullscreen": "F11",
    },
    "window_size": [1024, 720],
    "last_open_book": None,
}

# 老版本设置一次性迁移：settings_version < 3 时切到新默认值
_LEGACY_DEFAULTS = {
    "custom_font": "sys:weidqczfkyxk.ttf",
    "pagination": False,  # 旧版默认误为分页，迁移到滚动阅读
    "settings_version": 3,
}

_SETTINGS_MIGRATIONS = {
    1: lambda data: {**data, **_LEGACY_DEFAULTS},
    2: lambda data: {**data, **_LEGACY_DEFAULTS},
    3: lambda data: {**data, **_LEGACY_DEFAULTS},
}


class Settings:
    """扁平键值设置，update 接受部分补丁，原子写盘。"""

    def __init__(self, file: Path):
        self._file = file
        self._lock = threading.RLock()
        self._data: dict[str, Any] = copy.deepcopy(DEFAULTS)

    def load(self) -> None:
        data = load_json_file(self._file)
        if data:
            migrated = int(data.get("settings_version", 0) or 0) < 3
            if migrated:
                # 旧版设置文件：一次性切到新默认值（滚动阅读 + 内置默认字体）
                data = run_migrations(data, _SETTINGS_MIGRATIONS, 3, "settings_version")
            for k in DEFAULTS:
                if k not in data:
                    continue
                if isinstance(data[k], type(DEFAULTS[k])):
                    self._data[k] = data[k]
                else:
                    log.warning(
                        "settings.json 字段 %s 类型异常（%s），已忽略并使用默认值",
                        k,
                        type(data[k]).__name__,
                    )
            if migrated:
                self.save()

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
                if k not in DEFAULTS:
                    continue
                if isinstance(v, type(DEFAULTS[k])):
                    self._data[k] = v
                else:
                    log.warning(
                        "设置更新字段 %s 类型异常（%s），已忽略",
                        k,
                        type(v).__name__,
                    )
            self.save()
