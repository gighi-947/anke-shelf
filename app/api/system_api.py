"""系统/窗口/版本/数据目录。"""
import logging
import os
import shutil
import subprocess
import time
from pathlib import Path

_log = logging.getLogger("app.system")

from .. import __version__
from ..backup import create_backup, restore_backup, verify_backup
from ..diagnostics import build_diagnostics
from ..dialogs import pick_folder, pick_paths
from ..errors import ApiError, ErrorCode
from ..instance_guard import release_instance_lock
from ..paths import (
    annotations_path,
    data_dir,
    progress_path,
    settings_path,
    shelf_path,
    statistics_path,
)
from ..storage import verify_json_file
from .common import ApiContext


def _pick_and_call(picker, runner, cancel_msg: str = "已取消") -> dict:
    """文件选择 → 取消映射 → 执行 → 异常映射 的统一模板（backup 三件套共用）。"""
    picked = picker()
    if not picked:
        raise ApiError(ErrorCode.EXPORT_FAILED, cancel_msg)
    try:
        return runner(picked)
    except Exception as e:  # noqa: BLE001
        raise ApiError(ErrorCode.STORAGE_ERROR, str(e))


def on_frontend_ready(ctx: ApiContext) -> None:
    """前端初始化完成后由 JS 调用；主程序据此显示隐藏中的窗口。"""
    ctx.frontend_ready.set()


def toggle_fullscreen(ctx: ApiContext) -> dict:
    """沉浸式阅读：切换宿主窗口全屏（窗口未就绪时由回调抛 RuntimeError → 503）。"""
    try:
        ctx.window_toggle(not ctx.fullscreen)
        ctx.fullscreen = not ctx.fullscreen
        return {"ok": True}
    except Exception as e:  # noqa: BLE001
        raise ApiError(ErrorCode.SERVICE_UNAVAILABLE, str(e))


def export_diagnostics(ctx: ApiContext) -> dict:
    """导出诊断包（版本/平台/日志/脱敏设置）到用户自选文件夹。"""
    dest = pick_folder("选择诊断包保存文件夹")
    if not dest:
        raise ApiError(ErrorCode.EXPORT_FAILED, "已取消")
    try:
        path = build_diagnostics(Path(dest))
        return {"ok": True, "path": str(path)}
    except (OSError, ValueError) as e:
        raise ApiError(ErrorCode.STORAGE_ERROR, str(e))


def verify_data_integrity(ctx: ApiContext) -> dict:
    """检查各 JSON 数据文件可解析性/版本/大小（不读取内容值，不含凭据）。"""
    results = [
        verify_json_file(p)
        for p in (shelf_path(), progress_path(), settings_path(), annotations_path(), statistics_path())
    ]
    return {"ok": True, "healthy": all(r["ok"] for r in results), "files": results}


def backup_create(ctx: ApiContext) -> dict:
    """创建统一备份包（ank-backup/1）到自选文件夹。"""
    paths = {
        "shelf": shelf_path(),
        "progress": progress_path(),
        "settings": settings_path(),
        "annotations": annotations_path(),
        "statistics": statistics_path(),
    }
    return _pick_and_call(
        lambda: pick_folder("选择备份保存文件夹"),
        lambda dest: create_backup(dest, paths, __version__),
    )


def backup_verify(ctx: ApiContext) -> dict:
    """选择备份包并只读验证（校验和/可解析性/版本字段，不写盘）。"""
    return _pick_and_call(
        lambda: pick_paths("backup"),
        lambda picked: verify_backup(Path(picked[0])),
    )


def backup_restore(ctx: ApiContext, overwrite: bool = False) -> dict:
    """选择备份包恢复：先验证再写；已有数据时需 overwrite=True 才覆盖。"""
    paths = {
        "shelf": shelf_path(),
        "progress": progress_path(),
        "settings": settings_path(),
        "annotations": annotations_path(),
        "statistics": statistics_path(),
    }
    return _pick_and_call(
        lambda: pick_paths("backup"),
        lambda picked: restore_backup(Path(picked[0]), paths, overwrite=bool(overwrite)),
    )


def log_frontend(ctx: ApiContext, message: str) -> None:
    """前端把启动阶段的关键节点写进启动日志，便于定位卡死/慢启动。"""
    logging.getLogger("app.frontend").info("JS: %s", message)


def get_version(ctx: ApiContext) -> str:
    """当前应用版本号（设置页展示用）。"""
    return __version__


def open_data_dir(ctx: ApiContext) -> dict:
    """在资源管理器中打开用户数据目录。"""
    try:
        os.startfile(str(data_dir()))
        return {"ok": True}
    except OSError as e:
        raise ApiError(ErrorCode.STORAGE_ERROR, str(e))


def uninstall_and_quit(ctx: ApiContext) -> dict:
    """清除全部用户数据后退出程序（卸载流程的一部分）。"""
    ctx.books.close_all()
    release_instance_lock()
    target = data_dir()
    trash = target.with_name(target.name + f".trash-{int(time.time())}")
    try:
        if target.exists():
            shutil.move(str(target), str(trash))
    except OSError:
        trash = target
    # 进程退出、文件句柄释放后，由分离的 PowerShell 清理改名后的目录。
    script = (
        "Start-Sleep -Seconds 2; "
        f"Remove-Item -LiteralPath '{trash}' -Recurse -Force -ErrorAction SilentlyContinue"
    )
    try:
        subprocess.Popen(
            ["powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script],
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    except Exception as e:  # noqa: BLE001
        _log.error("启动卸载清理脚本失败（数据目录可能残留）：%s", e)
    os._exit(0)
