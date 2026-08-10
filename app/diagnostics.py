"""诊断导出（B6）：版本/平台/日志/脱敏设置；绝不包含 NGA Cookie 与凭据。"""
import json
import platform
import sys
import zipfile
from datetime import datetime
from pathlib import Path

from . import __version__
from .paths import data_dir


def build_diagnostics(dest: Path, data_root: Path | None = None) -> Path:
    """把诊断信息打包到 dest 目录（返回 zip 路径）。

    只包含：version.txt、脱敏后的 settings.json、logs/*.log；
    nga_config.ini 等含凭据的文件一律不打包。
    """
    root = data_root or data_dir()
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    zip_path = dest / f"ankeshelf-diagnostics-{stamp}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(
            "version.txt",
            f"AnkeShelf {__version__}\n"
            f"platform={platform.platform()}\n"
            f"python={sys.version.split()[0]}\n",
        )
        settings_file = root / "settings.json"
        if settings_file.is_file():
            settings = json.loads(settings_file.read_text(encoding="utf-8"))
            z.writestr("settings.json", json.dumps(settings, ensure_ascii=False, indent=2))
        logs = root / "logs"
        if logs.is_dir():
            for p in sorted(logs.glob("*.log")):
                z.write(p, f"logs/{p.name}")
    return zip_path
