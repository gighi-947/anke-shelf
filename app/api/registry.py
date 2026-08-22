"""方法名 → handler 的注册表（server.py 经 handler(name) 显式查询分发）。"""
from typing import Callable, Optional


class ApiRegistry:
    """按方法名注册 handler。

    - 分发入口是 `handler(name)`：只返回已注册项（未注册 None → 404）。
      HTTP 分发不经 getattr——getattr 会把对象上任意公共成员（如
      `fullscreen` property、`register` 方法）暴露成 /api 端点，接口暴露面
      必须收口在注册清单内。
    - `__getattr__` 仅供进程内直调（`api.get_settings(...}`，测试与内部
      使用）保留，不影响 HTTP 暴露面。
    """

    def __init__(self) -> None:
        self._handlers: dict[str, Callable] = {}

    def register(self, name: str, handler: Callable) -> None:
        self._handlers[name] = handler

    def handler(self, name: str) -> Optional[Callable]:
        return self._handlers.get(name)

    def __getattr__(self, name: str):
        try:
            return self._handlers[name]
        except KeyError:
            raise AttributeError(name) from None
