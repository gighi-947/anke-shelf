"""任务基础设施（B7）：按 lane 限流的单飞任务管理器。

设计约束（与既有 NGA 单飞语义一致）：
- 每个 lane 同时只允许一个任务运行（如 network:nga=1）；
- 不同 lane 可并行（如导出与下载）；
- 取消通过 cancel 标志由任务方检查（TaskCancelled）。

注意：NgaService 暂不迁移（保持其自身单飞锁），本模块供新任务
（大文件导入/导出/索引重建）接入。
"""
import threading
from dataclasses import dataclass
from enum import Enum
from typing import Callable, Optional


class TaskStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


@dataclass
class TaskProgress:
    current: int = 0
    total: int = 0
    stage: str = ""
    message: str = ""


class TaskCancelled(Exception):
    """任务方检查到取消标志时抛出。"""


class TaskManager:
    def __init__(self, lanes: Optional[dict[str, int]] = None) -> None:
        self._lanes = lanes or {}
        self._active: dict[str, str] = {}  # lane -> task_id
        self._cancelled: set[str] = set()
        self._lock = threading.RLock()

    def start(self, lane: str, task_id: str) -> bool:
        """尝试占用 lane；空闲则登记并返回 True，否则返回 False。"""
        with self._lock:
            if lane in self._active:
                return False
            self._active[lane] = task_id
            self._cancelled.discard(task_id)
            return True

    def finish(self, lane: str, task_id: str) -> None:
        with self._lock:
            if self._active.get(lane) == task_id:
                del self._active[lane]
            self._cancelled.discard(task_id)

    def cancel(self, task_id: str) -> None:
        with self._lock:
            self._cancelled.add(task_id)

    def is_cancelled(self, task_id: str) -> bool:
        with self._lock:
            return task_id in self._cancelled

    def run(
        self,
        lane: str,
        task_id: str,
        fn: Callable[[Callable[[TaskProgress], None]], None],
        on_progress: Optional[Callable[[TaskProgress], None]] = None,
    ) -> TaskStatus:
        """在 lane 上执行任务；拿不到 lane 返回 PENDING。

        fn 接收 report 回调：任务方周期性上报进度；report 会检查取消标志，
        已取消时抛 TaskCancelled。
        """
        if not self.start(lane, task_id):
            return TaskStatus.PENDING
        try:
            def report(p: TaskProgress) -> None:
                if self.is_cancelled(task_id):
                    raise TaskCancelled(task_id)
                if on_progress is not None:
                    on_progress(p)

            fn(report)
            return TaskStatus.CANCELLED if self.is_cancelled(task_id) else TaskStatus.COMPLETED
        except TaskCancelled:
            return TaskStatus.CANCELLED
        except Exception:
            return TaskStatus.FAILED
        finally:
            self.finish(lane, task_id)
