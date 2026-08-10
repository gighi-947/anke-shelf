"""统一日志字段（B7）：component event key=value，便于检索与诊断导出。"""
import logging
from typing import Any


def log_event(
    logger: logging.Logger,
    component: str,
    event: str,
    level: int = logging.INFO,
    **fields: Any,
) -> None:
    """输出 `component event key=value ...` 结构行；None 字段跳过。"""
    parts = [component, event]
    parts.extend(f"{k}={v}" for k, v in fields.items() if v is not None)
    logger.log(level, " ".join(parts))
