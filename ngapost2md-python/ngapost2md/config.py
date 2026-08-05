"""配置加载与默认配置生成。

兼容 Go 版生成的 config.ini：值可能被反引号包裹，读取时统一去除。
"""
import configparser
from dataclasses import dataclass
from pathlib import Path


def _strip_backtick(v: str) -> str:
    return v.strip("`")


@dataclass
class Config:
    base_url: str = "https://bbs.nga.cn"
    ua: str = ""
    nga_passport_uid: str = ""
    nga_passport_cid: str = ""
    thread: int = 2
    page_download_limit: int = 100
    use_title_as_folder_name: bool = False
    use_title_as_md_file_name: bool = False
    use_network_media_url: bool = False
    assets_path: str = "./assets/"
    output_path: str = "./"
    no_images: bool = False      # 内容中不包含图片（CLI 参数）
    no_media: bool = False       # Markdown 中不下载/保留任何媒体（图片视频音频）
    max_floors: int = 0          # 只下载前 N 个有效楼层（CLI 参数），0=不限制
    epub_enabled: bool = False   # 同时导出 EPUB
    epub_image_mode: str = "embedded"  # EPUB 图片模式：embedded / online
    epub_per_chapter: int = 20   # EPUB 每章楼层数
    epub_image_quality: int = 85  # EPUB 图片压缩质量（WebP）
    epub_image_max_size: int = 1280  # EPUB 图片最长边像素（0=不缩放）
    epub_toc_pid: int = 0       # 从指定 pid 楼读取目录作为 EPUB TOC（0=用默认章节导航）
    epub_theme: str = "light"   # EPUB 主题：light / dark
    epub_text_color: str = ""   # EPUB 正文文字颜色（空=主题默认）
    epub_bg_color: str = ""     # EPUB 背景颜色（空=主题默认）

    def cookie_header(self) -> str:
        return f"ngaPassportUid={self.nga_passport_uid};ngaPassportCid={self.nga_passport_cid}"


# 默认配置，与 Go 版 config/ 目录保持一致
_DEFAULT = {
    "network": {
        "base_url": "https://bbs.nga.cn",
        "ua": "<;MODIFY_ME;>",
        "ngaPassportUid": "<;MODIFY_ME;>",
        "ngaPassportCid": "<;MODIFY_ME;>",
        "thread": "2",
        "page_download_limit": "100",
    },
    "post": {
        "use_title_as_folder_name": "False",
        "use_title_as_md_file_name": "False",
        "use_network_media_url": "False",
        "assets_path": "./assets/",
        "output_path": "./",
    },
}


def _new_parser() -> configparser.ConfigParser:
    parser = configparser.ConfigParser()
    parser.optionxform = str  # 保留键名大小写（ngaPassportUid 等）
    return parser


def load_config(path: str = "config.ini") -> Config:
    if not Path(path).exists():
        raise FileNotFoundError(
            f"找不到配置文件 {path}。可先运行 --gen-config-file 生成默认配置。"
        )
    parser = _new_parser()
    parser.read(path, encoding="utf-8")

    def sec(name: str) -> dict:
        return parser[name] if parser.has_section(name) else {}

    net, post = sec("network"), sec("post")

    def get(d: dict, key: str, default: str) -> str:
        return d.get(key, default) if key in d else default

    def to_bool(v: str) -> bool:
        return v.strip().lower() in ("true", "1", "yes")

    cfg = Config()
    cfg.base_url = _strip_backtick(get(net, "base_url", cfg.base_url))
    cfg.ua = _strip_backtick(get(net, "ua", ""))
    cfg.nga_passport_uid = _strip_backtick(get(net, "ngaPassportUid", ""))
    cfg.nga_passport_cid = _strip_backtick(get(net, "ngaPassportCid", ""))
    try:
        cfg.thread = int(get(net, "thread", "2"))
    except ValueError:
        cfg.thread = 2
    cfg.thread = max(1, min(3, cfg.thread))
    try:
        cfg.page_download_limit = int(get(net, "page_download_limit", "100"))
    except ValueError:
        cfg.page_download_limit = 100
    cfg.use_title_as_folder_name = to_bool(get(post, "use_title_as_folder_name", "False"))
    cfg.use_title_as_md_file_name = to_bool(get(post, "use_title_as_md_file_name", "False"))
    cfg.use_network_media_url = to_bool(get(post, "use_network_media_url", "False"))
    cfg.assets_path = get(post, "assets_path", "./assets/")
    cfg.output_path = get(post, "output_path", "./")
    return cfg


def gen_default_config_file(path: str = "config.ini") -> None:
    parser = _new_parser()
    for section, items in _DEFAULT.items():
        parser.add_section(section)
        for key, value in items.items():
            parser.set(section, key, value)
    with open(path, "w", encoding="utf-8") as f:
        parser.write(f)
