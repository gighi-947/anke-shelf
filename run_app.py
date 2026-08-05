"""PyInstaller entry point: import the real app package so relative imports work."""
import logging
import os
import sys
from pathlib import Path


def _setup_release_logging() -> None:
    """发行版把 stdout/stderr 和日志写入 %APPDATA%\\AnkeShelf\\logs，方便定位卡死。"""
    if not getattr(sys, "frozen", False):
        return
    try:
        from app.paths import data_dir

        log_dir = data_dir() / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        log_path = log_dir / "startup.log"
        # 超过 2MB 就从头写，避免日志无限膨胀。
        mode = "a" if log_path.exists() and log_path.stat().st_size < 2 * 1024 * 1024 else "w"
        log_file = open(log_path, mode, encoding="utf-8", buffering=1)
        sys.stdout = log_file
        sys.stderr = log_file
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s %(levelname)s %(name)s: %(message)s",
            handlers=[logging.StreamHandler(log_file)],
            force=True,
        )
        logging.getLogger(__name__).info("=== AnkeShelf 启动 ===")
    except Exception:
        # 日志只是辅助手段，失败不能影响程序本身。
        pass


def _set_webview_compat_flags() -> None:
    """WebView2 兼容/省资源参数：
    - 关闭硬件加速（部分机器 GPU 合成会卡死 UI 线程）
    - 关闭后台联网、组件更新、同步等与阅读无关的 Chromium 服务，降低开销
    """
    if getattr(sys, "frozen", False):
        os.environ.setdefault(
            "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
            "--disable-gpu --disable-background-networking --disable-component-update "
            "--no-first-run --disable-sync --disable-default-apps --disable-extensions "
            "--disable-pinch",
        )


sys.path.insert(0, str(Path(__file__).resolve().parent))

from app.dpi import enable_per_monitor_dpi
from app.paths import migrate_legacy_data

migrate_legacy_data()
_setup_release_logging()
_set_webview_compat_flags()
enable_per_monitor_dpi()

from app.main import main

if __name__ == "__main__":
    sys.exit(main())
