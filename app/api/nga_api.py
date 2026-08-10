"""NGA 下载 / 热更新 / 导出。"""
from ..nga_config import clear_nga_config, load_nga_config, save_nga_config
from .common import ApiContext


def nga_get_config(ctx: ApiContext) -> dict:
    return load_nga_config()


def nga_save_config(ctx: ApiContext, patch: dict) -> dict:
    return save_nga_config(patch or {})


def nga_clear_config(ctx: ApiContext) -> dict:
    """清除已保存的 NGA 登录配置（Cookie/UA），重置为占位模板。"""
    return clear_nga_config()


def nga_update_book(ctx: ApiContext, book_id: str, params: dict) -> dict:
    """对已下载的 NGA 帖子做增量热更新。"""
    if ctx.nga_service is None:
        return {"ok": False, "error": "NGA 下载服务不可用"}
    return ctx.nga_service.update_book(book_id, params or {})


def nga_update_defaults(ctx: ApiContext, book_id: str) -> dict:
    """返回热更新表单的默认参数（最近一次下载/更新设置）。"""
    if ctx.nga_service is None:
        return {"ok": False, "error": "NGA 下载服务不可用"}
    return ctx.nga_service.update_defaults(book_id)


def export_start(ctx: ApiContext, book_id: str, fmt: str = "both") -> dict:
    """把 NGA 下载的帖子导出为用户自选格式（epub/md/both）+ 自选文件夹。"""
    if ctx.export_service is None:
        return {"ok": False, "error": "导出服务不可用"}
    return ctx.export_service.start(book_id, fmt)


def export_status(ctx: ApiContext) -> dict:
    if ctx.export_service is None:
        return {"running": False, "stage": "idle", "detail": "", "files": [], "dest": "", "error": ""}
    return ctx.export_service.status()


def export_open_dest(ctx: ApiContext) -> dict:
    if ctx.export_service is None:
        return {"ok": False, "error": "导出服务不可用"}
    return ctx.export_service.open_dest()


def nga_start_download(ctx: ApiContext, params: dict) -> dict:
    if ctx.nga_service is None:
        return {"ok": False, "error": "NGA 下载服务不可用"}
    return ctx.nga_service.start(params or {})


def nga_download_status(ctx: ApiContext) -> dict:
    if ctx.nga_service is None:
        return {"running": False, "stage": "idle", "detail": ""}
    return ctx.nga_service.status()


def nga_cancel(ctx: ApiContext) -> None:
    if ctx.nga_service is not None:
        ctx.nga_service.cancel()
