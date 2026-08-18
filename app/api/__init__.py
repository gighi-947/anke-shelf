"""本地 HTTP API 服务层（app/api.py 拆分后的包）。

外部协议不变：`Api(...)` 构造签名与 /api/<name> → `getattr(api, name)` 分发
完全兼容旧实现；内部按域拆成独立 handler 模块，方法名即接口契约。
"""
from .common import ApiContext, bind
from .registry import ApiRegistry
from . import (
    annotation_api,
    gululu_api,
    library,
    nga_api,
    reader,
    search_api,
    settings_api,
    stats_api,
    system_api,
)

_HANDLERS = (
    # 系统 / 窗口 / 版本
    ("on_frontend_ready", system_api.on_frontend_ready),
    ("toggle_fullscreen", system_api.toggle_fullscreen),
    ("log_frontend", system_api.log_frontend),
    ("get_version", system_api.get_version),
    ("open_data_dir", system_api.open_data_dir),
    ("uninstall_and_quit", system_api.uninstall_and_quit),
    ("export_diagnostics", system_api.export_diagnostics),
    ("verify_data_integrity", system_api.verify_data_integrity),
    ("backup_create", system_api.backup_create),
    ("backup_verify", system_api.backup_verify),
    ("backup_restore", system_api.backup_restore),
    # 书架与书籍
    ("get_shelf", library.get_shelf),
    ("import_books", library.import_books),
    ("remove_book", library.remove_book),
    ("open_book", library.open_book),
    ("rename_book", library.rename_book),
    # 阅读
    ("save_progress", reader.save_progress),
    ("get_chapter_plaintext", reader.get_chapter_plaintext),
    # 搜索
    ("search", search_api.search),
    ("search_more", search_api.search_more),
    ("is_index_ready", search_api.is_index_ready),
    # 标注
    ("get_annotations", annotation_api.get_annotations),
    ("save_annotation", annotation_api.save_annotation),
    ("update_annotation", annotation_api.update_annotation),
    ("delete_annotation", annotation_api.delete_annotation),
    ("add_bookmark", annotation_api.add_bookmark),
    ("delete_bookmark", annotation_api.delete_bookmark),
    ("export_annotations", annotation_api.export_annotations),
    # 统计
    ("record_reading", stats_api.record_reading),
    ("get_stats", stats_api.get_stats),
    # NGA 下载 / 热更新 / 导出
    ("nga_get_config", nga_api.nga_get_config),
    ("nga_save_config", nga_api.nga_save_config),
    ("nga_clear_config", nga_api.nga_clear_config),
    ("nga_update_book", nga_api.nga_update_book),
    ("nga_update_defaults", nga_api.nga_update_defaults),
    ("export_start", nga_api.export_start),
    ("export_status", nga_api.export_status),
    ("export_open_dest", nga_api.export_open_dest),
    ("export_cancel", nga_api.export_cancel),
    ("nga_start_download", nga_api.nga_start_download),
    ("nga_download_status", nga_api.nga_download_status),
    ("nga_cancel", nga_api.nga_cancel),
    # 骨碌碌标准 EPUB 导入
    ("gululu_start_import", gululu_api.gululu_start_import),
    ("gululu_start_export", gululu_api.gululu_start_export),
    ("gululu_start_update", gululu_api.gululu_start_update),
    ("gululu_get_comments", gululu_api.gululu_get_comments),
    ("gululu_decrypt_secret", gululu_api.gululu_decrypt_secret),
    ("gululu_import_status", gululu_api.gululu_import_status),
    ("gululu_cancel", gululu_api.gululu_cancel),
    # 设置与字体
    ("get_fonts", settings_api.get_fonts),
    ("pick_font_file", settings_api.pick_font_file),
    ("get_settings", settings_api.get_settings),
    ("save_settings", settings_api.save_settings),
)


def api_manifest() -> list[dict]:
    """导出当前 API 方法契约清单（方法名），供前端与 CI 对照，防止两端漂移。"""
    return [{"name": name} for name, _ in _HANDLERS]


class Api(ApiRegistry):
    """本地 HTTP API 聚合门面：构造时注册全部 handler。

    方法名即 /api/<name> 契约（server.py 的 getattr 分发保持不变）；
    未注册的方法名由 __getattr__ 抛 AttributeError（等价旧 404 行为）。
    """

    def __init__(
        self,
        books,
        shelf,
        progress,
        settings,
        search,
        annotations=None,
        stats=None,
        nga_service=None,
        export_service=None,
        gululu_service=None,
        frontend_ready=None,
        file_dialog=None,
        window_toggle=None,
    ):
        super().__init__()
        ctx = ApiContext(
            books=books,
            shelf=shelf,
            progress=progress,
            settings=settings,
            search=search,
            annotations=annotations,
            stats=stats,
            nga_service=nga_service,
            export_service=export_service,
            gululu_service=gululu_service,
            frontend_ready=frontend_ready,
            file_dialog=file_dialog,
            window_toggle=window_toggle,
        )
        self._ctx = ctx
        for name, fn in _HANDLERS:
            self.register(name, bind(ctx, fn))

    @property
    def fullscreen(self) -> bool:
        """当前沉浸式全屏状态（main.py 关闭窗口时读取，避免把全屏分辨率记为窗口尺寸）。"""
        return self._ctx.fullscreen
