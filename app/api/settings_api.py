"""设置与字体。"""
from .common import ApiContext, pick_paths


def get_fonts(ctx: ApiContext) -> dict:
    from ..fonts import list_fonts

    return {
        "fonts": list_fonts(),
        "global_font": ctx.settings.get("custom_font") or "",
        "book_fonts": ctx.settings.get("book_fonts") or {},
    }


def pick_font_file(ctx: ApiContext) -> dict:
    paths = pick_paths(ctx, "font")
    if not paths:
        return {}
    from ..fonts import register_font

    try:
        return register_font(paths[0])
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}


def get_settings(ctx: ApiContext) -> dict:
    return ctx.settings.get_all()


def save_settings(ctx: ApiContext, patch: dict) -> None:
    ctx.settings.update(patch or {})
