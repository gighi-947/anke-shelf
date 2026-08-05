"""NGA 凭据与应用内下载配置管理。

配置存于 %APPDATA%\\AnkeShelf\\nga_config.ini（复用 ngapost2md 的 ini 格式），
首次运行时若项目自带 ngapost2md-python\\config.ini（源码开发模式）则自动导入。
"""
import configparser
from pathlib import Path

from .paths import data_dir, nga_config_path

DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)


def _new_parser() -> configparser.ConfigParser:
    parser = configparser.ConfigParser()
    parser.optionxform = str  # 保留 ngaPassportUid 键大小写
    return parser


def _strip_backtick(v: str) -> str:
    return v.strip("`")


def _read_ini(path: Path) -> dict:
    parser = _new_parser()
    if path.exists():
        parser.read(path, encoding="utf-8")
    out = {}
    for section in ("network",):
        if parser.has_section(section):
            for key, val in parser.items(section):
                out[key] = _strip_backtick(val)
    return out


def _candidate_source() -> Path:
    """源码开发模式下，优先复用 ngapost2md-python 目录里已配置好的 config.ini。"""
    project_root = Path(__file__).resolve().parent.parent
    for p in (
        project_root / "ngapost2md-python" / "config.ini",
        project_root / "config.ini",
    ):
        if p.exists():
            return p
    return Path()


def _is_real(uid: str, cid: str, ua: str) -> bool:
    banned = ("<;MODIFY_ME;>", "MODIFY_ME", "")
    return uid not in banned and cid not in banned and ua not in banned


def _write_placeholder(path: Path) -> None:
    """写入不带任何真实凭据的占位模板。"""
    parser = _new_parser()
    parser.add_section("network")
    for key, default in (("base_url", "https://bbs.nga.cn"),
                         ("ua", DEFAULT_UA),
                         ("ngaPassportUid", ""),
                         ("ngaPassportCid", "")):
        parser.set("network", key, default)
    with open(path, "w", encoding="utf-8") as f:
        parser.write(f)


def ensure_nga_config() -> Path:
    """确保配置文件存在；首次运行自动导入项目 config.ini（若有真实凭据）。"""
    path = nga_config_path()
    if path.exists():
        return path
    data_dir().mkdir(parents=True, exist_ok=True)
    src = _candidate_source()
    if src and _is_real(*(_read_ini(src).get(k, "") for k in
                          ("ngaPassportUid", "ngaPassportCid", "ua"))):
        parser = _new_parser()
        parser.read(src, encoding="utf-8")
        with open(path, "w", encoding="utf-8") as f:
            parser.write(f)
        return path
    # 无可用来源：写一个带占位符的模板
    _write_placeholder(path)
    return path


def load_nga_config() -> dict:
    """返回前端可编辑的配置快照（不含完整 Cookie 之外的信息）。"""
    ensure_nga_config()
    raw = _read_ini(nga_config_path())
    uid = raw.get("ngaPassportUid", "")
    cid = raw.get("ngaPassportCid", "")
    # UA 未配置时默认填入常用浏览器 UA，用户无需手动填写
    ua = raw.get("ua", "") or DEFAULT_UA
    return {
        "uid": uid,
        "cid": cid,
        "ua": ua,
        "base_url": raw.get("base_url", "https://bbs.nga.cn"),
        "configured": _is_real(uid, cid, ua),
    }


def save_nga_config(patch: dict) -> dict:
    """保存用户编辑的 NGA 配置。返回更新后的快照。"""
    path = ensure_nga_config()
    parser = _new_parser()
    if path.exists():
        parser.read(path, encoding="utf-8")
    if not parser.has_section("network"):
        parser.add_section("network")
    mapping = {
        "uid": "ngaPassportUid",
        "cid": "ngaPassportCid",
        "ua": "ua",
        "base_url": "base_url",
    }
    for key, ini_key in mapping.items():
        if key in patch and isinstance(patch[key], str):
            parser.set("network", ini_key, patch[key].strip())
    with open(path, "w", encoding="utf-8") as f:
        parser.write(f)
    return load_nga_config()


def clear_nga_config() -> dict:
    """删除已保存的 NGA 凭据，重置为占位模板。"""
    path = nga_config_path()
    try:
        if path.exists():
            path.unlink()
    except OSError:
        pass
    # 直接写占位模板：绝不能再次从源码 config.ini 自动导入真实凭据。
    data_dir().mkdir(parents=True, exist_ok=True)
    _write_placeholder(path)
    return load_nga_config()
