"""路径定位模块：用户数据目录与前端资源目录。

兼容两种运行模式：
- 源码运行：web 资源位于项目根目录下的 web/
- PyInstaller 冻结（_MEIPASS）：web 资源打包进 exe，从 _MEIPASS 取
"""
import os
import logging
import shutil
import sys
from datetime import datetime
from pathlib import Path

APP_DIR_NAME = "AnkeShelf"
LEGACY_APP_DIR_NAME = "EpubReader"
LEGACY_LINUX_DIR_NAME = ".epub_reader"

logger = logging.getLogger("app.paths")


def _legacy_data_dir() -> Path:
    """旧版数据目录：%APPDATA%\\EpubReader（Linux 兜底 ~/.epub_reader）。"""
    base = os.environ.get("APPDATA")
    if base:
        return Path(base) / LEGACY_APP_DIR_NAME
    return Path.home() / LEGACY_LINUX_DIR_NAME


def _default_data_dir() -> Path:
    base = os.environ.get("APPDATA")
    if base:
        return Path(base) / APP_DIR_NAME
    return Path.home() / ".ankeshelf"


def data_dir() -> Path:
    """用户数据目录：%APPDATA%\\AnkeShelf（Linux 兜底 ~/.ankeshelf）。"""
    return _default_data_dir()


def migrate_legacy_data() -> None:
    """一次性迁移旧版 %APPDATA%\\EpubReader 数据到 AnkeShelf。

    仅在真实默认数据目录上执行（测试注入的临时目录不受影响）；
    若新版目录已存在书架等数据则跳过，避免覆盖。
    """
    new_dir = data_dir()
    if new_dir != _default_data_dir():
        return
    old_dir = _legacy_data_dir()
    if old_dir == new_dir or not old_dir.exists():
        return
    try:
        if not new_dir.exists():
            os.rename(old_dir, new_dir)
            logger.info("已迁移旧数据目录 %s -> %s", old_dir, new_dir)
            return
        if any(new_dir.iterdir()):
            return
        new_dir.mkdir(parents=True, exist_ok=True)
        for child in list(old_dir.iterdir()):
            shutil.move(str(child), str(new_dir / child.name))
        try:
            old_dir.rmdir()
        except OSError:
            pass
        logger.info("已合并旧数据目录 %s -> %s", old_dir, new_dir)
    except OSError:
        logger.exception("旧数据目录迁移失败（可手动移动 %s 到 %s）", old_dir, new_dir)


def ensure_data_dir() -> Path:
    """确保数据目录与封面缓存目录存在（含旧版数据一次性迁移）。"""
    migrate_legacy_data()
    d = data_dir()
    d.mkdir(parents=True, exist_ok=True)
    (d / "covers").mkdir(exist_ok=True)
    return d


def web_dir() -> Path:
    """前端静态资源目录。"""
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        return Path(meipass) / "web"
    return Path(__file__).resolve().parent.parent / "web"


def file_mtime(path: str) -> str:
    """文件修改时间 → ISO 字符串；不可读时返回空串。"""
    try:
        return datetime.fromtimestamp(Path(path).stat().st_mtime).astimezone().isoformat(
            timespec="seconds"
        )
    except OSError:
        return ""


def dir_mtime(path) -> str:
    """目录修改时间 → ISO 字符串；不可读时返回空串。"""
    return file_mtime(str(path))


def shelf_path() -> Path:
    return data_dir() / "shelf.json"


def progress_path() -> Path:
    return data_dir() / "progress.json"


def settings_path() -> Path:
    return data_dir() / "settings.json"


def covers_dir() -> Path:
    return data_dir() / "covers"


def annotations_path() -> Path:
    return data_dir() / "annotations.json"


def statistics_path() -> Path:
    return data_dir() / "statistics.json"


def nga_config_path() -> Path:
    """NGA 凭据/UA 配置（应用内维护，独立于 ngapost2md 的 config.ini）。"""
    return data_dir() / "nga_config.ini"


def nga_library_dir() -> Path:
    """NGA 下载库：每帖一个文件夹（ngapost2md 输出目录）。"""
    return data_dir() / "nga_library"
