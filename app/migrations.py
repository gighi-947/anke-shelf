"""统一数据迁移框架（B6）：load → detect version → migrate → validate → 原子写。

磁盘格式不变；各 store 在 load 时按版本号逐步迁移，禁止边读边改原文件。
"""
from typing import Callable, Mapping


def run_migrations(
    data: dict,
    migrations: Mapping[int, Callable[[dict], dict]],
    current_version: int,
    version_key: str = "version",
) -> dict:
    """从 data 的当前版本逐步迁移到 current_version，返回迁移后的副本。"""
    version = int(data.get(version_key, 0) or 0)
    out = dict(data)
    while version < current_version:
        step = migrations.get(version + 1)
        if step is None:
            raise ValueError(f"缺少迁移步骤 {version} -> {version + 1}（{version_key}）")
        out = step(out)
        version = int(out.get(version_key, 0) or 0)
        if version <= 0:
            raise ValueError("迁移后版本号缺失或非法")
    return out
