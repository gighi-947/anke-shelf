"""命令行入口。对应 Go 源码 main.go。"""
import argparse
import logging
import re
import sys

from . import __version__
from .client import NgaClient
from .config import gen_default_config_file, load_config
from .models import Tiezi
from . import nga

log = logging.getLogger("ngapost2md")


def _extract_tid_authorid(url: str) -> tuple[int, int]:
    m = re.search(r"read\.php\?(.*$)", url)
    if not m:
        return 0, 0
    params = m.group(1)
    tid = 0
    author_id = 0
    tm = re.search(r"tid=(\d+)", params)
    if tm:
        tid = int(tm.group(1))
    am = re.search(r"authorid=(\d+)", params)
    if am:
        author_id = int(am.group(1))
    return tid, author_id


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="ngapost2md",
        description="NGA 帖子转 Markdown（Python 重写版）",
    )
    parser.add_argument("tid_or_url", nargs="?", help="帖子 tid 或 NGA 链接")
    parser.add_argument("--authorid", type=int, default=0, help="只下载此 authorid 的发言层")
    parser.add_argument("--max-floors", type=int, default=0, help="只下载前 N 个有效楼层（0 为不限制）")
    parser.add_argument("--no-images", action="store_true", help="内容中不包含图片（移除图片标记）")
    parser.add_argument("--no-media", action="store_true", help="Markdown 中不下载/保留任何媒体（图片视频音频）")
    parser.add_argument("--epub", action="store_true", help="同时导出 EPUB")
    parser.add_argument("--epub-images", choices=["embedded", "online"], default="embedded",
                        help="EPUB 图片模式：embedded（嵌入，默认）/ online（在线引用）")
    parser.add_argument("--epub-per-chapter", type=int, default=20, help="EPUB 每章楼层数（默认 20）")
    parser.add_argument("--epub-image-quality", type=int, default=85, help="EPUB 图片压缩质量 WebP（默认 85）")
    parser.add_argument("--epub-image-max-size", type=int, default=1280,
                        help="EPUB 图片最长边像素（默认 1280，0 表示不缩放）")
    parser.add_argument("--epub-toc-pid", type=int, default=0,
                        help="从指定 pid 楼读取帖子目录作为 EPUB 目录（0 为默认章节导航）")
    parser.add_argument("--epub-theme", choices=["light", "dark"], default="light",
                        help="EPUB 主题：light（默认）/ dark（暗色）")
    parser.add_argument("--epub-text-color", default="",
                        help="EPUB 正文文字颜色，如 #e0e0e0（空=主题默认）")
    parser.add_argument("--epub-bg-color", default="",
                        help="EPUB 背景颜色，如 #1e1e1e（空=主题默认）")
    parser.add_argument("-v", "--version", action="store_true", help="显示版本信息并退出")
    parser.add_argument("--gen-config-file", action="store_true", help="生成默认配置文件 config.ini 并退出")
    args = parser.parse_args(argv)

    if args.version:
        print(f"ngapost2md (Python 重写版) v{__version__}")
        return 0
    if args.gen_config_file:
        gen_default_config_file()
        print("导出默认配置文件 config.ini 成功。")
        return 0
    if not args.tid_or_url:
        parser.print_help()
        return 1

    if sys.platform == "win32":
        for stream in (sys.stdout, sys.stderr):
            try:
                stream.reconfigure(encoding="utf-8")
            except Exception:
                pass

    logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s")

    cfg = load_config()
    cfg.max_floors = args.max_floors
    cfg.no_images = args.no_images
    cfg.no_media = args.no_media
    cfg.epub_enabled = args.epub
    cfg.epub_image_mode = args.epub_images
    cfg.epub_per_chapter = max(1, args.epub_per_chapter)
    cfg.epub_image_quality = max(1, min(100, args.epub_image_quality))
    cfg.epub_image_max_size = max(0, args.epub_image_max_size)
    cfg.epub_toc_pid = max(0, args.epub_toc_pid)
    cfg.epub_theme = args.epub_theme
    cfg.epub_text_color = args.epub_text_color
    cfg.epub_bg_color = args.epub_bg_color
    if not cfg.nga_passport_uid or "MODIFY_ME" in cfg.nga_passport_uid:
        print("配置项配置错误: ngaPassportUid")
        return 1
    if not cfg.nga_passport_cid or "MODIFY_ME" in cfg.nga_passport_cid:
        print("配置项配置错误: ngaPassportCid")
        return 1
    if not cfg.ua or "MODIFY_ME" in cfg.ua:
        print("配置项配置错误: ua")
        return 1

    if "read.php" in args.tid_or_url:
        tid, aid_from_url = _extract_tid_authorid(args.tid_or_url)
        if tid == 0:
            print("无法从链接中提取到有效的 tid:", args.tid_or_url)
            return 1
        if args.authorid == 0 and aid_from_url != 0:
            args.authorid = aid_from_url
    else:
        try:
            tid = int(args.tid_or_url)
        except ValueError:
            print("tid", args.tid_or_url, "无法转为数字")
            return 1

    nga_client = NgaClient(cfg)
    try:
        nga.init_nga(nga_client, cfg)
        tiezi = Tiezi(tid=tid, author_id=args.authorid)
        folder = nga.find_folder_name_by_tid(tid, args.authorid)
        if folder:
            log.info("本地存在此 tid (%s) 文件夹，追加最新更改。", folder)
            nga.init_from_local(tiezi)
        else:
            nga.init_from_web(tiezi)
        nga.download(tiezi)
    finally:
        nga_client.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
