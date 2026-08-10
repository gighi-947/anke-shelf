"""轻量进程内领域事件（B5）：为缓存失效等跨模块联动提供单一出口。

不做 MQ/异步；订阅方异常不影响主流程（由订阅方自行记日志）。
"""
import threading
from collections import defaultdict
from typing import Callable, DefaultDict


class EventBus:
    def __init__(self) -> None:
        self._subs: DefaultDict[str, list[Callable]] = defaultdict(list)
        self._lock = threading.RLock()

    def on(self, event: str, fn: Callable) -> None:
        with self._lock:
            self._subs[event].append(fn)

    def emit(self, event: str, **kwargs) -> None:
        with self._lock:
            handlers = list(self._subs.get(event, ()))
        for fn in handlers:
            try:
                fn(**kwargs)
            except Exception:  # noqa: BLE001
                pass


bus = EventBus()
