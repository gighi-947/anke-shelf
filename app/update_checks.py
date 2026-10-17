"""进书更新检查状态存储（端私有 sidecar，不入双端数据契约）。

update_checks.json：{"version":1,"auto_check":{bookId:bool},"available":{bookId:int}}。

- auto_check：每本书「自动检查更新」开关，缺省 True（未登记=开）；
- available：已检出的待更新楼数（>0 才有条目；书架徽标与阅读器红点共用）；
- 容错读：文件缺失/损坏一律回空（load_json_file 会把损坏文件隔离为
  .corrupt-*；更新检查是可选增强，不值得打断用户）；
- 写走 atomic_write_json 原子替换；revision 每次实际写盘递增，供前端
  轮询书架徽标；无变化的写入会跳过（不写盘也不递增）；
- mark_checked / checked_this_session 是会话内节流（内存 set，不持久化），
  防止书架轮询对同一本书反复发网络检查。

语义对齐 Android data/UpdateCheckStore.kt（feat10 同步）。
"""
import threading
from pathlib import Path
from typing import Optional

from .paths import update_checks_path
from .storage import atomic_write_json, load_json_file


class UpdateCheckStore:
    """每本书的更新检查开关 + 待更新楼数（线程安全）。"""

    def __init__(self, path: Optional[Path] = None):
        self._path = Path(path) if path is not None else update_checks_path()
        self._lock = threading.RLock()
        self._revision = 0
        self._auto: dict[str, bool] = {}
        self._available: dict[str, int] = {}
        self._checked: set[str] = set()
        self._load()

    def _load(self) -> None:
        data = load_json_file(self._path)
        if not isinstance(data, dict):
            return
        try:
            auto = data.get("auto_check")
            available = data.get("available")
            if isinstance(auto, dict):
                self._auto = {
                    str(k): v for k, v in auto.items()
                    if isinstance(k, str) and isinstance(v, bool)
                }
            if isinstance(available, dict):
                self._available = {
                    str(k): v for k, v in available.items()
                    if isinstance(k, str) and isinstance(v, int) and not isinstance(v, bool)
                }
        except (TypeError, ValueError):
            # 结构意外（手改/半写）：整体回空，与损坏文件同一语义
            self._auto = {}
            self._available = {}

    def _persist(self) -> None:
        atomic_write_json(self._path, {
            "version": 1,
            "auto_check": self._auto,
            "available": self._available,
        })
        self._revision += 1

    @property
    def revision(self) -> int:
        with self._lock:
            return self._revision

    def is_auto_check_enabled(self, book_id: str) -> bool:
        """未登记的书默认开。"""
        with self._lock:
            return self._auto.get(book_id, True)

    def set_auto_check(self, book_id: str, enabled: bool) -> None:
        """设置自动检查开关；关闭时同时清除该书 available 条目（不再显示徽标）。"""
        if not book_id:
            return
        with self._lock:
            current = self._auto.get(book_id, True)
            if enabled:
                changed = current is not True
            else:
                changed = current is not False or book_id in self._available
            if not changed:
                return
            self._auto[book_id] = bool(enabled)
            if not enabled:
                self._available.pop(book_id, None)
            self._persist()

    def set_available(self, book_id: str, floors: int) -> None:
        """记录待更新楼数；<=0 删除条目（值未变化不写盘）。"""
        if not book_id:
            return
        with self._lock:
            if floors <= 0:
                if book_id not in self._available:
                    return
                del self._available[book_id]
            else:
                if self._available.get(book_id) == floors:
                    return
                self._available[book_id] = int(floors)
            self._persist()

    def available_snapshot(self) -> dict[str, int]:
        with self._lock:
            return dict(self._available)

    def auto_check_snapshot(self) -> dict[str, bool]:
        """显式登记的开关（缺省语义 true 由调用方兜底）。"""
        with self._lock:
            return dict(self._auto)

    def mark_checked(self, book_id: str) -> None:
        """会话内节流标记（内存，不持久化）。"""
        with self._lock:
            self._checked.add(book_id)

    def checked_this_session(self, book_id: str) -> bool:
        with self._lock:
            return book_id in self._checked
