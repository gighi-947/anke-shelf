"""单实例守卫：清理上次异常退出残留的进程，防止僵尸窗口堆积。"""
import os
import signal
import subprocess
import sys
import time
from pathlib import Path


def _safe_print(message: str) -> None:
    """打印启动诊断信息，stdout 无法编码（如英文 Windows 的 cp1252）时降级为可编码字符。"""
    try:
        print(message, flush=True)
    except UnicodeEncodeError:
        enc = getattr(sys.stdout, "encoding", None) or "utf-8"
        print(message.encode(enc, "replace").decode(enc, "replace"), flush=True)


def _instance_lock_file() -> Path:
    from .paths import data_dir

    return data_dir() / "instance.lock"


def cleanup_stale_instance() -> None:
    """启动时调用：若 instance.lock 记录的 PID 仍是 python 进程则终止它。

    先通过 tasklist 确认是 python.exe 再终止，避免误杀被复用的 PID。
    随后写入当前进程 PID 作为新锁。
    """
    lock = _instance_lock_file()
    try:
        pid = int(lock.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        pid = 0
    if pid:
        try:
            out = subprocess.run(
                ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
                capture_output=True, text=True, timeout=10,
            ).stdout
            proc_name = Path(sys.executable).name.lower()
            if f'"{pid}"' in out and proc_name in out.lower():
                _safe_print(f"检测到上次未正常退出的安科书架进程（PID {pid}），正在清理…")
                os.kill(pid, signal.SIGTERM)
                time.sleep(1.0)
        except (OSError, subprocess.SubprocessError):
            pass  # 进程已消失或无法确认，忽略
    lock.write_text(str(os.getpid()), encoding="utf-8")


def release_instance_lock() -> None:
    lock = _instance_lock_file()
    try:
        if lock.exists() and lock.read_text(encoding="utf-8").strip() == str(os.getpid()):
            lock.unlink(missing_ok=True)
    except OSError:
        pass
