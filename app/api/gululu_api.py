"""骨碌碌标准 EPUB 导入。"""
from ..errors import ErrorCode, api_error
from .common import ApiContext


def gululu_start_import(
    ctx: ApiContext,
    source: str,
    image_mode: str = "online",
) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌导入服务不可用")
    return ctx.gululu_service.start(source, image_mode)


def gululu_start_export(
    ctx: ApiContext,
    source: str,
    image_mode: str = "online",
) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌导出服务不可用")
    return ctx.gululu_service.start_export(source, image_mode)


def gululu_start_update(
    ctx: ApiContext,
    source: str,
    image_mode: str = "online",
) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌更新服务不可用")
    return ctx.gululu_service.start_update(source, image_mode)


def gululu_get_comments(
    ctx: ApiContext,
    source_id: int,
    floor_ids: list[int],
    refresh: bool = False,
) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌评论服务不可用")
    return ctx.gululu_service.get_comments(source_id, floor_ids, refresh=bool(refresh))


def gululu_decrypt_secret(
    ctx: ApiContext,
    source_id: int,
    title: str,
    cipher: str,
    password: str,
) -> dict:
    from ..gululu_assistant import GululuSecretError, decrypt_cryptojs_secret

    del ctx
    try:
        normalized_source = int(source_id)
    except (TypeError, ValueError):
        normalized_source = 0
    normalized_title = str(title or "").strip()
    if normalized_source <= 0 or not normalized_title or len(normalized_title) > 120:
        return api_error(ErrorCode.BOOK_INVALID, "秘密来源或名称无效")
    try:
        plaintext = decrypt_cryptojs_secret(cipher, password)
    except GululuSecretError as exc:
        return api_error(ErrorCode.BOOK_INVALID, str(exc))
    return {
        "ok": True,
        "source_id": normalized_source,
        "title": normalized_title,
        "plaintext": plaintext,
    }


def gululu_import_status(ctx: ApiContext) -> dict:
    if ctx.gululu_service is None:
        return {"running": False, "stage": "idle", "detail": ""}
    return ctx.gululu_service.status()


def gululu_cancel(ctx: ApiContext) -> dict:
    if ctx.gululu_service is None:
        return api_error(ErrorCode.SERVICE_UNAVAILABLE, "骨碌碌导入服务不可用")
    return ctx.gululu_service.cancel()
