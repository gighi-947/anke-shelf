"""骨碌碌标准 EPUB 导入。"""
from ..errors import ErrorCode, api_error
from .common import ApiContext


def gululu_start_import(ctx: ApiContext, source: str) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌导入服务不可用")
    return ctx.gululu_service.start(source)


def gululu_start_export(ctx: ApiContext, source: str) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌导出服务不可用")
    return ctx.gululu_service.start_export(source)


def gululu_get_comments(
    ctx: ApiContext,
    source_id: int,
    floor_ids: list[int],
    refresh: bool = False,
) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌评论服务不可用")
    return ctx.gululu_service.get_comments(source_id, floor_ids, refresh=bool(refresh))


def gululu_import_status(ctx: ApiContext) -> dict:
    if ctx.gululu_service is None:
        return {"running": False, "stage": "idle", "detail": ""}
    return ctx.gululu_service.status()


def gululu_cancel(ctx: ApiContext) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌导入服务不可用")
    return ctx.gululu_service.cancel()
