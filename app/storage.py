"""统一持久化工具：时间戳、原子写盘、损坏隔离与完整性校验。"""
import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path

log = logging.getLogger("storage")


def now_iso() -> str:
    """UTC ISO 时间戳（秒级），用于 JSON 落盘。"""
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def atomic_write_json(path: Path, data: dict) -> None:
    """原子写 JSON：临时文件 + os.replace（Windows 下同盘原子）。"""
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    backup_previous(path)
    os.replace(tmp, path)


def atomic_write_text(path: Path, text: str) -> None:
    """原子写文本：临时文件 + os.replace。"""
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    backup_previous(path)
    os.replace(tmp, path)


def backup_previous(path: Path) -> None:
    """替换前保留最近一次有效副本（.bak），供损坏后人工/自动恢复。"""
    try:
        if path.exists():
            path.with_suffix(path.suffix + ".bak").write_bytes(path.read_bytes())
    except OSError:
        pass


def isolate_corrupt(path: Path):
    """把无法解析的数据文件改名隔离为 .corrupt-<时间戳>，返回隔离后的路径。"""
    try:
        if path.exists():
            stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
            target = path.with_name(path.name + f".corrupt-{stamp}")
            os.replace(path, target)
            return target
    except OSError:
        pass
    return None


def load_json_file(path: Path):
    """读取 JSON 文件；损坏时隔离为 .corrupt-* 并返回 None（调用方回退默认值）。"""
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None
    except (json.JSONDecodeError, OSError) as e:
        target = isolate_corrupt(path)
        log.warning("数据文件损坏，已隔离：%s -> %s（%s）", path, target or "-", e)
        return None


def verify_json_file(path: Path) -> dict:
    """单文件完整性检查：不读取内容值，只报告可解析性/大小/版本号。"""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        version = data.get("version", data.get("settings_version")) if isinstance(data, dict) else None
        return {
            "file": path.name,
            "ok": True,
            "error": "",
            "size": path.stat().st_size,
            "version": version,
        }
    except FileNotFoundError:
        return {"file": path.name, "ok": True, "error": "missing", "size": 0, "version": None}
    except (json.JSONDecodeError, OSError) as e:
        size = path.stat().st_size if path.exists() else 0
        return {"file": path.name, "ok": False, "error": str(e), "size": size, "version": None}
