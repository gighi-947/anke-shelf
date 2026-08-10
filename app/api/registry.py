"""方法名 → handler 的注册表（server.py 的 getattr 分发保持不变）。"""
from typing import Callable


class ApiRegistry:
    """按方法名注册 handler；`getattr(api, name)` 返回对应可调用对象。

    未注册的名字抛 AttributeError，兼容 server.py 的
    `getattr(self.api or object(), name, None)` → 404 行为。
    """

    def __init__(self) -> None:
        self._handlers: dict[str, Callable] = {}

    def register(self, name: str, handler: Callable) -> None:
        self._handlers[name] = handler

    def __getattr__(self, name: str):
        try:
            return self._handlers[name]
        except KeyError:
            raise AttributeError(name) from None
