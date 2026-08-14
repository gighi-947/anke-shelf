"""统一备份包（P3 存储恢复）：ank-backup/1 格式 + 验证 + “只验证不覆盖”的导入。

备份包 = zip：
  manifest.json   {format:"ank-backup/1", created_at, app_version,
                   contract_version, files:[{name, version, size, sha256}]}
  shelf.json / progress.json / settings.json / annotations.json / statistics.json

流程约定：
  create_backup(dest_dir, paths, app_version)  -> 生成 zip
  verify_backup(zip_path)                      -> 只读校验（清单/校验和/可解析性/版本字段）
  restore_backup(zip_path, paths, overwrite)   -> 内部先 verify；失败不写；
    目标已存在且 overwrite=False 时返回 needs_overwrite，绝不静默覆盖。
"""
import hashlib
import json
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from .storage import atomic_write_text

BACKUP_FORMAT = "ank-backup/1"
VERSION_FIELDS = ("version", "settings_version")
STORE_NAMES = ("shelf", "progress", "settings", "annotations", "statistics")


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def _schema_version(raw: bytes):
    try:
        obj = json.loads(raw.decode("utf-8"))
    except Exception:
        return None
    if not isinstance(obj, dict):
        return None
    for key in VERSION_FIELDS:
        if isinstance(obj.get(key), int):
            return obj[key]
    return None


def create_backup(dest_dir, paths: dict, app_version: str) -> dict:
    """把五个 JSON 存储打成备份 zip；返回结果字典。"""
    dest = Path(dest_dir)
    dest.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    zip_path = dest / f"ankeshelf-backup-{stamp}.zip"
    files = []
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for name in STORE_NAMES:
            src = paths.get(name)
            if src is None or not Path(src).is_file():
                continue
            data = Path(src).read_bytes()
            zf.writestr(f"{name}.json", data)
            files.append(
                {
                    "name": f"{name}.json",
                    "version": _schema_version(data),
                    "size": len(data),
                    "sha256": _sha256(data),
                }
            )
        manifest = {
            "format": BACKUP_FORMAT,
            "created_at": stamp,
            "app_version": app_version,
            "files": files,
        }
        zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    return {
        "ok": True,
        "path": str(zip_path),
        "size": zip_path.stat().st_size,
        "files": [f["name"] for f in files],
    }


def verify_backup(zip_path) -> dict:
    """只读校验备份包：清单存在且格式正确、条目齐全、校验和匹配、JSON 可解析并带版本。"""
    errors = []
    manifest = None
    info = []
    try:
        with zipfile.ZipFile(zip_path) as zf:
            names = set(zf.namelist())
            if "manifest.json" not in names:
                return {"ok": False, "errors": ["缺少 manifest.json"], "files": []}
            try:
                manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
            except Exception:
                return {"ok": False, "errors": ["manifest.json 无法解析"], "files": []}
            if manifest.get("format") != BACKUP_FORMAT:
                return {
                    "ok": False,
                    "errors": [f"不支持的备份格式：{manifest.get('format')}"],
                    "files": [],
                }
            for entry in manifest.get("files", []):
                name = entry.get("name")
                if name not in names:
                    errors.append(f"缺少条目：{name}")
                    continue
                data = zf.read(name)
                if entry.get("sha256") and _sha256(data) != entry.get("sha256"):
                    errors.append(f"校验和不匹配：{name}")
                    continue
                version = _schema_version(data)
                if version is None:
                    errors.append(f"JSON 无法解析或缺少版本字段：{name}")
                    continue
                info.append(
                    {"name": name, "version": version, "size": len(data), "sha256": _sha256(data)}
                )
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "errors": [str(e)], "files": []}
    return {"ok": not errors, "errors": errors, "files": info, "manifest": manifest}


def restore_backup(zip_path, paths: dict, overwrite: bool = False) -> dict:
    """恢复备份：先 verify，失败不写；目标已存在且未显式覆盖时不写。"""
    check = verify_backup(zip_path)
    if not check["ok"]:
        return {"ok": False, "errors": check["errors"], "restored": []}
    existing = [
        f["name"]
        for f in check["files"]
        if f["name"].endswith(".json")
        and paths.get(f["name"].removesuffix(".json")) is not None
        and Path(paths[f["name"].removesuffix(".json")]).exists()
    ]
    if existing and not overwrite:
        return {
            "ok": False,
            "errors": ["目标数据已存在，需显式确认覆盖"],
            "needs_overwrite": True,
            "existing": existing,
        }
    restored = []
    with zipfile.ZipFile(zip_path) as zf:
        for f in check["files"]:
            key = f["name"].removesuffix(".json")
            target = paths.get(key)
            if target is None:
                continue
            atomic_write_text(Path(target), zf.read(f["name"]).decode("utf-8"))
            restored.append(f["name"])
    return {"ok": True, "restored": restored}
