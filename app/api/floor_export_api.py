"""楼层导出 API：批量楼层渲染为 PNG/WebP，状态轮询与取消。"""
from .common import ApiContext
from ..errors import ApiError, ErrorCode


def floor_export_start(
    ctx: ApiContext,
    book_id: str,
    floors,
    theme: str = "light",
    fmt: str = "png",
    scale: float = 2.0,
    output_dir: str = "",
    no_images: bool = False,
    theme_colors=None,
    reader_style=None,
) -> dict:
    try:
        floor_list = [int(x) for x in floors]
    except (TypeError, ValueError):
        raise ApiError(ErrorCode.BOOK_INVALID, "楼层参数格式不正确")
    return ctx.floor_export_service.start(
        book_id=book_id,
        floors=floor_list,
        theme=theme,
        fmt=fmt,
        scale=scale,
        output_dir=output_dir,
        no_images=no_images,
        theme_colors=theme_colors,
        reader_style=reader_style,
    )


def floor_export_floors(ctx: ApiContext, book_id: str) -> dict:
    return ctx.floor_export_service.floor_list(book_id)


def floor_export_status(ctx: ApiContext) -> dict:
    return ctx.floor_export_service.status()


def floor_export_cancel(ctx: ApiContext) -> dict:
    return ctx.floor_export_service.cancel()


def floor_export_open_dest(ctx: ApiContext) -> dict:
    return ctx.floor_export_service.open_dest()


def pick_folder(ctx: ApiContext, title: str = "选择文件夹") -> dict:
    """弹出系统文件夹选择对话框；取消返回空字符串。"""
    from .. import dialogs

    if ctx.file_dialog is not None:
        path = ctx.file_dialog(title)
    else:
        path = dialogs.pick_folder(title)
    return {"path": path or ""}
