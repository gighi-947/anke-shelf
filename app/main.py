"""程序入口：装配各组件、启动 HTTP 服务器线程、拉起 pywebview 窗口壳。

架构分工（参考 Readest）：
- Python 侧做数据：解析、存储、服务、搜索
- Web 侧做表现：渲染与交互（index.html + js）
- 通道：HTTP 传字节（章节/图片/CSS）与结构化 JSON API（/api/*）
- pywebview 只负责开窗口：不再注入 js_api，业务完全走本地 HTTP
"""
import logging
import os
import secrets
import sys
import threading
import time

import webview

from .dpi import enable_per_monitor_dpi
from .instance_guard import cleanup_stale_instance, release_instance_lock


class _PyWebViewNoiseFilter(logging.Filter):
    """过滤 pywebview 6.2.1 + Python 3.14 的已知后台线程噪声日志。

    winforms 后端的定时器从非 UI 线程读取 WebView2 控件属性（CoreWebView2、
    ZoomFactor 等）以及无障碍 API 访问，触发 COM 错误与无限递归；这些异常
    均被 pywebview 内部捕获，**不影响功能**，只是刷屏。在入口统一静默。
    """

    _NOISE = ("Error while processing", "maximum recursion depth exceeded")

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            msg = record.getMessage()
        except Exception:
            return True
        return not any(n in msg for n in self._NOISE)


def _silence_pywebview_noise() -> None:
    logging.getLogger("pywebview").addFilter(_PyWebViewNoiseFilter())


def _pin_webview_zoom(window) -> None:
    """固定 WebView2 缩放为 100%，并禁用 Ctrl+滚轮/快捷键缩放。

    WebView2 默认允许浏览器级缩放：一旦缩放因子被意外改成 1.x，
    页面按缩放后的视口渲染，实际内容会比窗口大，出现错位与滚动条。
    阅读器字号/页面宽度由应用内设置控制，不需要浏览器级缩放。
    """
    try:
        native = getattr(window, "native", None)
        if native is None:
            return
        browser = getattr(native, "browser", None)
        if browser is None:
            return
        ctrl = getattr(browser, "webview", None)
        if ctrl is None:
            return

        def _apply() -> None:
            try:
                ctrl.ZoomFactor = 1.0
                cwv = ctrl.CoreWebView2
                if cwv is not None:
                    cwv.Settings.IsZoomControlEnabled = False
            except Exception:
                pass

        try:
            if bool(native.InvokeRequired):
                import clr  # noqa: F401
                from System import Action

                native.Invoke(Action(_apply))
            else:
                _apply()
        except Exception:
            _apply()
    except Exception:
        pass


def _make_window_fullscreen_toggle(window):
    """保留进入全屏前的最大化状态，规避 WinForms 退出全屏强制 Normal。"""
    state = {
        "maximized": bool(getattr(window, "maximized", False)),
        "restore_maximized": False,
        "transition": False,
    }

    def on_maximized() -> None:
        if not state["transition"]:
            state["maximized"] = True

    def on_restored() -> None:
        if not state["transition"]:
            state["maximized"] = False

    window.events.maximized += on_maximized
    window.events.restored += on_restored

    def toggle(entering: bool) -> None:
        if entering:
            state["restore_maximized"] = state["maximized"]
        state["transition"] = True
        try:
            window.toggle_fullscreen()
            if not entering and state["restore_maximized"]:
                window.maximize()
        finally:
            state["transition"] = False

    return toggle


from .annotations import AnnotationStore
from .api import Api
from .book_manager import BookManager
from .export_service import ExportService
from .gululu_service import GululuService
from .nga_config import ensure_nga_config
from .nga_service import NgaService
from .paths import (
    annotations_path,
    covers_dir,
    ensure_data_dir,
    file_mtime,
    gululu_library_dir,
    nga_library_dir,
    progress_path,
    settings_path,
    shelf_path,
    statistics_path,
    web_dir,
)
from .search import SearchService
from .server import start_server
from .settings import Settings
from .shelf import ProgressStore, Shelf
from .startup_errors import show_startup_error
from .stats import StatsStore


def main() -> int:
    enable_per_monitor_dpi()
    _silence_pywebview_noise()
    ensure_data_dir()
    cleanup_stale_instance()

    books = BookManager()
    shelf = Shelf(shelf_path(), covers_dir())
    shelf.load()
    progress = ProgressStore(progress_path())
    progress.load()
    settings = Settings(settings_path())
    settings.load()
    search_svc = SearchService()
    # book_updated 回调改为显式注入，见 NgaService/GululuService

    def _on_book_updated(book_id: str) -> None:
        """下载/热更新后：若书在缓存中，按 revision 刷新全文索引（惰性重建）。"""
        if books.has(book_id):
            search_svc.refresh_if_stale(books.open(book_id))

    annotations = AnnotationStore(annotations_path())
    annotations.load()
    stats = StatsStore(statistics_path())
    stats.load()
    ensure_nga_config()
    nga_library_dir().mkdir(parents=True, exist_ok=True)
    gululu_library_dir().mkdir(parents=True, exist_ok=True)

    def _register_nga_book(path: str) -> str:
        """下载完成后注册到书架；返回 book_id。"""
        import re
        from pathlib import Path

        from .shelf import BookRecord

        book = books.register(path)
        folder = Path(path).parent.name
        m = re.match(r"^(\d+)(?:\((\d+)\))?$", folder)
        nga_tid = int(m.group(1)) if m else 0
        rec = BookRecord(
            id=book.id,
            path=book.path,
            title=book.title,
            author=book.author,
            language=book.language,
            chapter_count=len(book.chapters),
            file_size=Path(path).stat().st_size,
            cover_rel=shelf.extract_cover(book),
            nga_tid=nga_tid,
        )
        shelf.upsert(rec)
        shelf.save()
        return book.id

    nga_svc = NgaService(
        _register_nga_book,
        shelf=shelf,
        books=books,
        on_book_updated=_on_book_updated,
    )

    def _register_gululu_book(path: str) -> str:
        """骨碌碌 EPUB 生成完成后注册到标准书架。"""
        from pathlib import Path

        from .shelf import BookRecord

        book = books.register(path)
        existing = shelf.get(book.id)
        cover_rel = shelf.extract_cover(book) or (existing.cover_rel if existing else None)
        rec = BookRecord(
            id=book.id,
            path=book.path,
            title=book.title,
            author=book.author,
            language=book.language,
            chapter_count=len(book.chapters),
            file_size=Path(path).stat().st_size,
            file_mtime=file_mtime(path),
            cover_rel=cover_rel,
        )
        shelf.upsert(rec)
        shelf.save()
        return book.id

    gululu_svc = GululuService(
        _register_gululu_book,
        shelf=shelf,
        books=books,
        on_book_updated=_on_book_updated,
    )
    export_svc = ExportService(shelf)
    frontend_ready = threading.Event()
    window_fullscreen_toggle = None

    def _toggle_window_fullscreen(entering: bool) -> None:
        if window_fullscreen_toggle is None:
            raise RuntimeError("窗口尚未就绪")
        window_fullscreen_toggle(entering)

    api = Api(
        books=books,
        shelf=shelf,
        progress=progress,
        settings=settings,
        search=search_svc,
        annotations=annotations,
        stats=stats,
        nga_service=nga_svc,
        gululu_service=gululu_svc,
        export_service=export_svc,
        frontend_ready=frontend_ready,
        window_toggle=_toggle_window_fullscreen,
    )
    token = secrets.token_urlsafe(16)
    port = start_server(web_dir(), books, covers_dir(), api=api, token=token)
    startup_log = logging.getLogger("app.startup")
    startup_log.info("HTTP 服务已启动（端口 %s），开始创建窗口", port)

    wsize = settings.get("window_size") or [1024, 720]
    window = webview.create_window(
        "安科书架 · AnkeShelf",
        f"http://127.0.0.1:{port}/index.html?token={token}",
        width=int(wsize[0]),
        height=int(wsize[1]),
        min_size=(640, 480),
        text_select=True,
        # 窗口先隐藏，等前端完全就绪后再显示：
        # WebView2 首次初始化和前端启动阶段如果用户立刻操作，
        # 在部分机器上会导致整窗未响应；隐藏期可完全规避。
        hidden=True,
    )
    window_fullscreen_toggle = _make_window_fullscreen_toggle(window)
    window.events.loaded += lambda: _pin_webview_zoom(window)

    def on_closing() -> None:
        # 记忆窗口尺寸（下次启动恢复）
        try:
            # 沉浸式全屏中不把全屏分辨率记成窗口尺寸
            if not api.fullscreen:
                size = window.size
                if size and size[0] >= 640 and size[1] >= 480:
                    settings.update({"window_size": list(size)})
        except Exception:
            pass

    window.events.closing += on_closing

    def _show_when_ready() -> None:
        """后台线程：等页面加载并等前端发来 ready 信号后再显示窗口。"""
        t0 = time.monotonic()
        # 上限 12 秒：即使前端初始化异常也不让用户对着“不存在”的窗口久等。
        loaded = window.events.loaded.wait(12)
        startup_log.info(
            "页面加载事件：%s（耗时 %.1fs）",
            "已触发" if loaded else "超时",
            time.monotonic() - t0,
        )
        remaining = max(0.0, 12 - (time.monotonic() - t0))
        ready = frontend_ready.wait(remaining)
        startup_log.info(
            "前端就绪信号：%s（总耗时 %.1fs）",
            "已收到" if ready else "超时，仍按兜底显示",
            time.monotonic() - t0,
        )
        time.sleep(0.2)  # 等首帧绘制完成再显示，避免出现空白闪烁
        try:
            window.show()
            startup_log.info("窗口已显示")
        except Exception:
            startup_log.exception("窗口显示失败")

    try:
        webview.start(
            gui="edgechromium",
            func=_show_when_ready,
            # WebView2 使用 pywebview 默认的私有临时目录（每次启动全新）：
            # 实测自定义 storage_path 会导致初始化间歇性卡死与内存暴涨。
            private_mode=True,
        )
    except RuntimeError as exc:
        # 已知最典型的是 pythonnet/.NET 加载失败（发行包启动崩溃 P0）：
        # 此时 pywebview 无法使用，改用系统 MessageBox 给用户可执行的指引。
        logging.getLogger("app.startup").exception("pywebview 启动失败：%s", exc)
        show_startup_error(exc)
        books.close_all()
        release_instance_lock()
        return 1
    books.close_all()
    release_instance_lock()
    # pywebview 6.2.1 在 Windows 下窗口关闭后仍有非 daemon 线程，
    # 导致解释器退出时挂起、进程残留（窗口消失但进程“未响应”）。
    # 所有清理已完成，这里强制结束进程，避免残留实例堆积。
    os._exit(0)
    return 0

if __name__ == "__main__":
    sys.exit(main())
