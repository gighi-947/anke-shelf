"""核心爬取流程。对应 Go 源码 nga/nga.go 的 Tiezi 方法与顶层逻辑。"""
import configparser
import glob
import json
import logging
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

from .client import NgaClient
from .config import Config
from .format import _DELAY_SEC, _to_save_filename, fix_floor, init_format, ts2t
from .models import Floor, Tiezi

log = logging.getLogger("ngapost2md")

VERSION = "0.1.0"

client: NgaClient | None = None
cfg: Config | None = None

# 保护 Floors 扩展（并发拉页时可能触发 append）
_floors_lock = threading.Lock()


class CancelledError(RuntimeError):
    """任务被用户取消（集成层取消标志触发）。"""


def init_nga(nga_client: NgaClient, config: Config) -> None:
    global client, cfg
    client = nga_client
    cfg = config
    init_format(config)


# ---------- 文件夹名 ----------

def find_folder_name_by_tid(tid: int, author_id: int) -> str:
    base = cfg.output_path if cfg else "."
    folder_name = f"{tid}({author_id})" if author_id > 0 else str(tid)
    if os.path.isdir(os.path.join(base, folder_name)):
        return folder_name
    pattern = os.path.join(base, f"{tid}({author_id})-*") if author_id > 0 else os.path.join(base, f"{tid}-*")
    matches = glob.glob(pattern)
    if len(matches) == 1:
        return os.path.basename(matches[0])
    if len(matches) > 1:
        raise RuntimeError(f"有多个文件夹匹配: {pattern}")
    return ""


def get_needed_folder_name(tiezi: Tiezi) -> str:
    if tiezi.folder_name:
        return tiezi.folder_name
    already = find_folder_name_by_tid(tiezi.tid, tiezi.author_id)
    if already:
        return already
    if cfg and cfg.use_title_as_folder_name:
        if tiezi.author_id > 0:
            return f"{tiezi.tid}({tiezi.author_id})-{tiezi.title_folder_safe}"
        return f"{tiezi.tid}-{tiezi.title_folder_safe}"
    if tiezi.author_id > 0:
        return f"{tiezi.tid}({tiezi.author_id})"
    return str(tiezi.tid)


# ---------- 楼层解析 ----------

def analyze_floors(floors: list, resp, is_comments: bool) -> None:
    lou_comment = 1
    for value in resp:
        if is_comments:
            lou = lou_comment
        else:
            lou = int(value.get("lou", 0))
        with _floors_lock:
            while len(floors) < lou + 1:
                floors.append(Floor(lou=-1))
        cur = floors[lou]
        cur.lou = lou
        cur.pid = int(value.get("pid", 0) or 0)
        cur.timestamp = int(value.get("postdatetimestamp", 0) or 0)
        author = value.get("author") or {}
        cur.username = author.get("username", "") if isinstance(author, dict) else ""
        cur.user_id = int(author.get("uid", 0) or 0) if isinstance(author, dict) else 0
        cur.content = value.get("content", "") or ""
        cur.raw_content = cur.content
        cur.like_num = int(value.get("vote_good", 0) or 0)
        comments = value.get("comments")
        if comments is not None:
            cur.comments = []
            analyze_floors(cur.comments, comments, True)
        lou_comment += 1


# ---------- 拉页 ----------

def page(tiezi: Tiezi, page_no: int) -> None:
    data = {"page": str(page_no), "tid": str(tiezi.tid)}
    if tiezi.author_id > 0:
        data["authorid"] = str(tiezi.author_id)
    resp = client.post_form("app_api.php?__lib=post&__act=list", data)
    if resp.get("code") != 0:
        raise RuntimeError(f"nga 返回代码不为0: {resp.get('code')} {resp.get('msg', '')}")

    tiezi.timestamp = int(time.time())
    tiezi.title = resp.get("tsubject", "") or ""
    tiezi.title_folder_safe = _to_save_filename(tiezi.title)
    tiezi.category = resp.get("forum_name", "") or ""
    tiezi.username = resp.get("tauthor", "") or ""
    tiezi.user_id = int(resp.get("tauthorid", 0) or 0)

    web_max_page = int(resp.get("totalPage", 1) or 1)
    if cfg.page_download_limit > 0 and web_max_page > tiezi.local_max_page + cfg.page_download_limit:
        web_max_page = tiezi.local_max_page + cfg.page_download_limit
    tiezi.web_max_page = web_max_page

    vrows = int(resp.get("vrows", 1) or 1)
    tiezi.floor_count = vrows - 1
    if len(tiezi.floors) == 0:
        tiezi.floors = [Floor(lou=-1) for _ in range(max(tiezi.floor_count, 0))]

    hot = resp.get("hot_post")
    if hot is not None:
        tiezi.hot_posts = []
        analyze_floors(tiezi.hot_posts, hot, False)
    result = resp.get("result")
    if result is not None:
        analyze_floors(tiezi.floors, result, False)


# ---------- 初始化 ----------

def init_from_web(tiezi: Tiezi) -> None:
    tiezi.version = VERSION
    tiezi.assets = {}
    tiezi.local_max_page = 1
    tiezi.local_max_floor = -1
    tiezi.created_time = datetime.now().astimezone().isoformat(timespec="seconds")
    log.info("下载第 %02d 页", tiezi.local_max_page)
    page(tiezi, tiezi.local_max_page)
    tiezi.folder_name = get_needed_folder_name(tiezi)


def init_from_local(tiezi: Tiezi) -> None:
    tiezi.version = VERSION
    folder_name = find_folder_name_by_tid(tiezi.tid, tiezi.author_id)
    if not folder_name:
        raise RuntimeError("找不到本地 tid 文件夹，软件将退出。")
    tiezi.folder_name = folder_name

    process_file = os.path.join(cfg.output_path, folder_name, "process.ini")
    assets_file = os.path.join(cfg.output_path, folder_name, "assets.json")
    if not os.path.exists(process_file):
        raise RuntimeError(f"{process_file} 文件丢失")
    if not os.path.exists(assets_file):
        raise RuntimeError(f"{assets_file} 文件丢失")

    with open(assets_file, encoding="utf-8") as f:
        tiezi.assets = json.load(f)

    ini = configparser.ConfigParser()
    ini.optionxform = str
    ini.read(process_file, encoding="utf-8")
    tiezi.local_max_page = int(ini.get("local", "max_page", fallback="1"))
    tiezi.local_max_floor = int(ini.get("local", "max_floor", fallback="-1"))
    tiezi.created_time = ini.get("info", "created_time", fallback="")
    tiezi.updated_time = ini.get("info", "updated_time", fallback="")
    if not tiezi.created_time:
        tiezi.created_time = datetime.now().astimezone().isoformat(timespec="seconds")

    log.info("下载第 %02d 页", tiezi.local_max_page)
    page(tiezi, tiezi.local_max_page)


# ---------- 内容格式化（并发） ----------

def _fix_one_floor(tiezi: Tiezi, idx: int) -> None:
    if tiezi.floors[idx].lou != -1:
        fix_floor(tiezi.floors[idx], tiezi)


def fix_floor_content(tiezi: Tiezi, start_floor: int) -> None:
    with ThreadPoolExecutor(max_workers=cfg.thread) as pool:
        futures = [pool.submit(_fix_one_floor, tiezi, i)
                   for i in range(start_floor, len(tiezi.floors))]
        for f in futures:
            if _cancelled():
                for p in futures:
                    p.cancel()
                raise CancelledError("格式化已取消")
            f.result()


# ---------- Markdown 生成 ----------

def gen_markdown(tiezi: Tiezi, start_floor: int) -> None:
    folder = os.path.join(cfg.output_path, tiezi.folder_name or get_needed_folder_name(tiezi))
    os.makedirs(folder, exist_ok=True)

    md_name = f"{tiezi.title_folder_safe}.md" if cfg.use_title_as_md_file_name else "post.md"
    md_path = os.path.join(folder, md_name)

    with open(md_path, "a", encoding="utf-8") as f:
        for i in range(start_floor, len(tiezi.floors)):
            if _cancelled():
                raise CancelledError("Markdown 生成已取消")
            floor = tiezi.floors[i]
            if floor.lou == -1:
                continue

            if cfg.max_floors > 0 and floor.lou > tiezi.max_lou:
                break

            if floor.pid == 0:
                author_opt = f"-只看 {tiezi.author_id}" if tiezi.author_id > 0 else ""
                f.write(f"### {tiezi.title}{author_opt}\n\n")
                f.write("Made by ngapost2md (c) ludoux [GitHub Repo](https://github.com/ludoux/ngapost2md)\n\n")

            if floor.pid == 0 and tiezi.hot_posts:
                f.write("##### 热门回复\n\n")
                for v in tiezi.hot_posts:
                    if v.lou == -1:
                        continue
                    content = v.content
                    if len(content) > 22:
                        content = content[:20] + "..."
                    f.write(f"- [{v.lou}楼](#pid{v.pid}): {content}\n")
                f.write("\n")

            ip_str = f"\\({floor.ip_location}\\)" if floor.ip_location else ""
            f.write(f"----\n\n##### <span id=\"pid{floor.pid}\">{floor.lou}.[{floor.like_num}] "
                    f"\\<pid:{floor.pid}\\> {ts2t(floor.timestamp)} by {floor.username}({floor.user_id})"
                    f"{ip_str}</span>\n{floor.content}")

            if floor.comments:
                f.write("\n\n*---下挂评论---*")
                for comment in floor.comments:
                    if comment.lou <= 0:
                        continue
                    f.write(f"\n\n{comment.lou}.[{comment.like_num}] \\<pid:{comment.pid}\\>"
                            f"{ts2t(comment.timestamp)} by {comment.username}({comment.user_id}):\n"
                            f"{comment.content}")
            f.write("\n\n")


# ---------- 状态保存 ----------

def save_process_info(tiezi: Tiezi) -> None:
    folder = os.path.join(cfg.output_path, tiezi.folder_name or get_needed_folder_name(tiezi))
    ini = configparser.ConfigParser()
    ini.optionxform = str
    ini.add_section("local")
    ini.set("local", "max_floor", str(tiezi.local_max_floor))
    ini.set("local", "max_page", str(tiezi.local_max_page))
    ini.add_section("info")
    ini.set("info", "created_time", tiezi.created_time)
    ini.set("info", "updated_time", tiezi.updated_time)
    with open(os.path.join(folder, "process.ini"), "w", encoding="utf-8") as f:
        ini.write(f)


def save_assets_map(tiezi: Tiezi) -> None:
    folder = os.path.join(cfg.output_path, tiezi.folder_name or get_needed_folder_name(tiezi))
    with open(os.path.join(folder, "assets.json"), "w", encoding="utf-8") as f:
        json.dump(tiezi.assets, f, ensure_ascii=False, indent=2)


# ---------- 主流程 ----------

def _download_one_page(tiezi: Tiezi, page_no: int) -> None:
    if client is None or cfg is None:
        raise RuntimeError("ngapost2md 未初始化")
    if _cancelled():
        raise CancelledError("任务已取消")
    time.sleep(_DELAY_SEC)
    log.info("下载第 %02d 页", page_no)
    page(tiezi, page_no)


_cancel_cb = None


def _cancelled() -> bool:
    return bool(_cancel_cb and _cancel_cb())


def set_cancel_cb(cb) -> None:
    """设置取消回调（集成层用）。None 表示不可取消。"""
    global _cancel_cb
    _cancel_cb = cb


def download(tiezi: Tiezi, progress=None, cancel=None, no_images: bool = False) -> None:
    """执行完整下载流程（拉页 → 格式化 → Markdown → EPUB）。

    progress: callable(stage: str, detail: dict)，集成层用于 UI 进度。
    cancel:   callable() -> bool，返回 True 时尽快中止（抛 CancelledError）。
    no_images: EPUB 渲染中不包含图片。
    """
    if cancel is not None:
        set_cancel_cb(cancel)
    if tiezi.tid == 0:
        return

    def report(stage: str, **kw) -> None:
        if progress:
            try:
                progress(stage, kw)
            except Exception:  # noqa: BLE001
                pass

    # 若只下载前 N 楼且首页已收集足够楼层，则不再拉取后续页
    if cfg.max_floors > 0:
        valid = sum(1 for f in tiezi.floors if f.lou != -1)
        if valid >= cfg.max_floors:
            tiezi.web_max_page = tiezi.local_max_page

    with ThreadPoolExecutor(max_workers=cfg.thread) as pool:
        futures = [pool.submit(_download_one_page, tiezi, p)
                   for p in range(tiezi.local_max_page + 1, tiezi.web_max_page + 1)]
        for p, f in zip(range(tiezi.local_max_page + 1, tiezi.web_max_page + 1), futures):
            f.result()
            report("pages", current=p, total=tiezi.web_max_page)

    # 计算前 N 个有效楼层中的最大楼号（max_floors 限制，-1 表示不限制）
    if cfg.max_floors > 0:
        lous = sorted(f.lou for f in tiezi.floors if f.lou != -1)
        if lous:
            tiezi.max_lou = lous[min(len(lous), cfg.max_floors) - 1]

    report("format", total=len(tiezi.floors))
    fix_floor_content(tiezi, tiezi.local_max_floor + 1)
    report("markdown", total=len(tiezi.floors))
    gen_markdown(tiezi, tiezi.local_max_floor + 1)

    tiezi.local_max_page = tiezi.web_max_page
    for i in range(len(tiezi.floors) - 1, -1, -1):
        floor = tiezi.floors[i]
        if floor.lou > -1 and (tiezi.max_lou < 0 or floor.lou <= tiezi.max_lou):
            tiezi.local_max_floor = floor.lou
            break

    tiezi.updated_time = datetime.now().astimezone().isoformat(timespec="seconds")
    save_process_info(tiezi)
    save_assets_map(tiezi)
    if cfg.epub_enabled:
        from . import epub as epub_mod
        toc_chapters = None
        if getattr(cfg, "epub_toc_pid", 0) > 0:
            try:
                resp = client.post_form("app_api.php?__lib=post&__act=list",
                                        {"tid": str(tiezi.tid), "pid": str(cfg.epub_toc_pid)})
                if resp.get("code") == 0 and resp.get("result"):
                    from .toc import parse_toc
                    toc_chapters = parse_toc(resp["result"][0].get("content", ""))
                    log.info("已从 pid %d 解析帖子目录：%d 个章节", cfg.epub_toc_pid, len(toc_chapters))
            except Exception as e:  # noqa: BLE001
                log.warning("解析目录楼失败: %s", e)
        try:
            report("epub", total=0)
            epub_mod.build_epub(tiezi, cfg,
                                per_chapter=cfg.epub_per_chapter,
                                image_mode=cfg.epub_image_mode,
                                toc_chapters=toc_chapters,
                                progress=lambda d: report("epub", **d),
                                cancel=cancel,
                                no_images=bool(no_images))
        except Exception as e:  # noqa: BLE001
            if isinstance(e, CancelledError):
                raise
            log.error("EPUB 生成失败: %s", e)
    log.info("本次任务结束。")
