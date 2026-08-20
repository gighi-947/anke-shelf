"""任务基础设施（B7）：按 lane 限流的单飞任务管理器。

设计约束（与既有 NGA 单飞语义一致）：
- 每个 lane 同时只允许一个任务运行（如 network:nga=1）；
- 不同 lane 可并行（如导出与下载）；
- 取消通过 cancel 标志由任务方检查（TaskCancelled）。

当前接入方：NgaService（lane=network:nga）、GululuService 与 ExportService
均已走 TaskManager；新增大文件导入/索引重建等任务时直接复用本模块。
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
        self._errors: dict[str, str] = {}  # task_id -> 最近一次失败详情
        self._lock = threading.RLock()

    def start(self, lane: str, task_id: str) -> bool:
        """尝试占用 lane；空闲则登记并返回 True，同任务重入视为已持有，否则 False。"""
        with self._lock:
            if lane in self._active:
                return self._active[lane] == task_id
            self._active[lane] = task_id
            self._cancelled.discard(task_id)
            self._errors.pop(task_id, None)
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

    def error(self, task_id: str) -> Optional[str]:
        """最近一次失败任务的异常详情；无失败/未知任务返回 None。"""
        with self._lock:
            return self._errors.get(task_id)

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
        except Exception as e:
            with self._lock:
                self._errors[task_id] = f"{type(e).__name__}: {e}"
            return TaskStatus.FAILED
        finally:
            self.finish(lane, task_id)
