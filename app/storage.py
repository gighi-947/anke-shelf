"""统一持久化工具：时间戳与原子写盘。"""
import json
import os
from datetime import datetime, timezone
from pathlib import Path


def now_iso() -> str:
    """UTC ISO 时间戳（秒级），用于 JSON 落盘。"""
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def atomic_write_json(path: Path, data: dict) -> None:
    """原子写 JSON：临时文件 + os.replace（Windows 下同盘原子）。"""
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def atomic_write_text(path: Path, text: str) -> None:
    """原子写文本：临时文件 + os.replace。"""
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)
