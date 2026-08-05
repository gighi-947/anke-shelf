"""内容格式化：将 NGA 的 BBCode 风格内容转换为 Markdown。

对应 Go 源码 nga/nga.go 的 fixMost / processMedia / fixContent，
以及 nga/utils.go 的 anony / ts2t / downloadAssets。
"""
import hashlib
import logging
import os
import re
import time
from datetime import datetime

import httpx

from .config import Config
from .models import Floor, Tiezi
from .smile_map import SMILE_MAP, ANONY_1, ANONY_2

log = logging.getLogger("ngapost2md")

# 模块级配置，由 cli 初始化
_CFG: Config | None = None
_DELAY_SEC = 0.330  # 对应 Go 的 DELAY_MS = 330

# 媒体下载/去重的锁
_assets_lock = __import__("threading").Lock()

# NGA 缩略图/中图变体后缀（如 xxx.png.thumb.jpg、xxx.png.medium.jpg）
RE_THUMB_SUFFIX = re.compile(r"\.(thumb|medium)\.(?:jpg|jpeg|png|gif|webp)$", re.IGNORECASE)


def normalize_image_url(url: str) -> str:
    """把 NGA 缩略图/中图变体归一为原图 URL，提高 URL 哈希去重命中率。

    同一立绘若同时以原图、.thumb.jpg、.medium.jpg 出现，将被视为同一资源。
    """
    return RE_THUMB_SUFFIX.sub("", url)


def init_format(cfg: Config) -> None:
    global _CFG
    _CFG = cfg


# ---------- 工具 ----------

def anony(it: str) -> str:
    """匿名 ID 转换。输入 '#anony_' + 32 字符 hash，输出中文匿名昵称。"""
    i = 6
    res = []
    for j in range(6):
        if j in (0, 3):
            n = int("0" + it[i + 1:i + 2], 16)
            if n < len(ANONY_1):
                res.append(ANONY_1[n])
        else:
            n = int(it[i:i + 2], 16)
            if n < len(ANONY_2):
                res.append(ANONY_2[n])
        i += 2
    return "".join(res) + "?"


def ts2t(ts: int) -> str:
    return datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")


def _to_save_filename(name: str) -> str:
    """清理 Windows 非法字符（Go utils.go: ToSaveFilename）。"""
    for ch in '\\/<>:"|?*':
        name = name.replace(ch, " ")
    return name.replace("&amp;", "&")


# ---------- 媒体下载 ----------

def _download_headers() -> dict:
    headers = {"User-Agent": _CFG.ua if _CFG else ""}
    if _CFG:
        # 图床防盗链需要携带 Referer 与登录 Cookie
        headers["Referer"] = _CFG.base_url
        headers["Cookie"] = _CFG.cookie_header()
    return headers


def fetch_bytes(url: str) -> bytes:
    """下载二进制内容。优先用 curl_cffi 模拟 Chrome（通过图床 TLS 指纹反爬），回退 httpx。"""
    headers = _download_headers()
    try:
        from curl_cffi import requests as creq
        resp = creq.get(url, headers=headers, impersonate="chrome", timeout=60)
        resp.raise_for_status()
        return resp.content
    except ImportError:
        with httpx.Client(timeout=60.0, follow_redirects=True) as client:
            resp = client.get(url, headers=headers)
            resp.raise_for_status()
            return resp.content


def download_assets(url: str, file_name: str) -> None:
    try:
        data = fetch_bytes(url)
        with open(file_name, "wb") as f:
            f.write(data)
    except Exception as e:  # noqa: BLE001
        log.warning("下载资源失败: %s -> %s, 错误: %s", url, file_name, e)


def _process_media(content: str, content_re, src_re, media_type: str,
                   tiezi: Tiezi, floor: Floor, is_image: bool) -> str:
    if _CFG and _CFG.no_media:
        # Markdown 不保留任何媒体（图片/视频/音频），EPUB 由 raw_content 独立处理
        return content_re.sub("", content)
    assets = tiezi.assets
    for m in content_re.finditer(content):
        if src_re is not None:
            src_m = src_re.search(m.group(0))
            if not src_m:
                continue
            url = src_m.group(1)
            full_match = m.group(0)
        else:
            url = m.group(1)
            full_match = f"[img]{m.group(1)}[/img]"

        if is_image:
            url = normalize_image_url(url)
            if len(url) >= 2 and url[:2] == "./":
                url = "https://img.nga.178.com/attachments/" + url[2:]

        if is_image and _CFG and _CFG.no_images:
            # 不包含图片：直接移除图片标记，不下载
            content = content.replace(full_match, "")
            continue

        if _CFG and _CFG.use_network_media_url:
            replacement = f"![img]({url})" if is_image else f"【{media_type}：{url}】"
        else:
            sha = hashlib.sha256(url.encode("utf-8")).hexdigest()
            suffix_len = min(6, len(url))
            shorted = sha[2:8] + url[len(url) - suffix_len:]

            with _assets_lock:
                if shorted in assets:
                    file_name = assets[shorted]
                    need_download = False
                else:
                    file_name = f"{floor.lou}_{shorted}"
                    assets[shorted] = file_name
                    need_download = True

            if need_download:
                time.sleep(_DELAY_SEC)
                folder_name = tiezi.folder_name or str(tiezi.tid)
                asset_dir = os.path.normpath(os.path.join(_CFG.output_path if _CFG else ".",
                                                          folder_name,
                                                          _CFG.assets_path if _CFG else "./assets/"))
                os.makedirs(asset_dir, exist_ok=True)
                download_assets(url, os.path.join(asset_dir, file_name))

            relative_path = os.path.join(".", _CFG.assets_path if _CFG else "./assets/", file_name)
            relative_path = relative_path.replace("\\", "/")
            replacement = f"![img]({relative_path})" if is_image else f"【{media_type}：{relative_path}】"

        content = content.replace(full_match, replacement)
    return content


# ---------- 正则（对照 Go nga/nga.go:67-90 翻译） ----------

RE_VIDEO_CONTENT = re.compile(r'<span class="video">(<video[^>]*>.*?</video>)</span>')
RE_VIDEO_SRC = re.compile(r'src="([^"]+)"')
RE_AUDIO_CONTENT = re.compile(r'<span class="audio" onclick="audioClick\(event\)"> <audio src="([^"]+)"[^>]*></audio></span>')
RE_IMG_CONTENT = re.compile(r'\[img\](.+?)\[/img\]')

RE_ANONY = re.compile(r'#anony_.{32}')
RE_DICE = re.compile(r"<div class='dice'><b>ROLL : (.+?)</b>=(.+?)=<b>(.+?)</b></div>")
RE_COLLAPSE = re.compile(r'<div class="foldBox no"><div class="collapse_btn"><a href="javascript:;" onclick="collapse\(this\);">\+</a>(.+?) ...</div><span class="collapse_content" id="foldCnt">(.+?)</span></div>')
RE_SMILE = re.compile(r'\[s\:.+?\:.+?\]')
RE_URL1 = re.compile(r'\[url\](.+?)\[/url\]')
RE_URL2 = re.compile(r'\[url=(.+?)\](.+?)\[/url\]')

RE_QUOTE_MAIN_WITH_UID = re.compile(r'(?s)\[quote\]\[tid=.+?Post by \[uid.*?\](.+)\[\/uid\].*?\((\d{4}.+?)\):</b>(.+?)\[/quote\]((?:\n){0,2})')
RE_QUOTE_MAIN_NO_UID = re.compile(r'(?s)\[quote\]\[tid=.+?Post by (.+)<span .*?\((\d{4}.+?)\):</b>(.+?)\[/quote\]((?:\n){0,2})')
RE_QUOTE_OTHER_WITH_UID = re.compile(r'(?s)\[quote\]\[pid=(\d+?),.+?Post by \[uid.*?\](.+)\[\/uid\].*?\((\d{4}.+?)\):</b>(.+?)\[/quote\]((?:\n){0,2})')
RE_QUOTE_OTHER_NO_UID = re.compile(r'(?s)\[quote\]\[pid=(\d+?),.+?Post by (.+)<span .*?\((\d{4}.+?)\):</b>(.+?)\[/quote\]((?:\n){0,2})')

RE_REPLY_TID_WITH_UID = re.compile(r'(?s)<b>Reply to \[tid=(\d+?).+? Post by \[uid.*?\](.+)\[\/uid\].+?\((.+?)\)</b>((?:\n){0,2})')
RE_REPLY_TID_NO_UID = re.compile(r'(?s)<b>Reply to \[tid=(\d+?).+? Post by (.+)<span .+?\((.+?)\)</b>((?:\n){0,2})')
RE_REPLY_PID_WITH_UID = re.compile(r'(?s)<b>Reply to \[pid=(\d+?),.+? Post by \[uid.*?\](.+)\[\/uid\].+?\((.+?)\)</b>((?:\n){0,2})')
RE_REPLY_PID_NO_UID = re.compile(r'(?s)<b>Reply to \[pid=(\d+?),.+? Post by (.+)<span .+?\((.+?)\)</b>((?:\n){0,2})')

_REPLACEMENTS = {
    r"\u0026": "&",
    r"\u003c": "<",
    r"\u003e": ">",
    "&amp;#160;": " ",
    "<br/>": "\n",
    "<br>": "\n",
    "&lt;br/&gt;": "\n",
    "&lt;br&gt;": "\n",
    "<del class='gray'>": "~~",
    "</del>": "~~",
}


# ---------- fixMost 管线（Go nga.go:475） ----------

def _fix_anony(cont: str, floor: Floor) -> str:
    if floor is not None:
        if len(floor.username) > 7 and floor.username[:7] == "#anony_":
            floor.username = anony(floor.username)
    for it in RE_ANONY.findall(cont):
        cont = cont.replace(it, anony(it))
    return cont


def _author_with_uid(cont: str, author: str) -> str:
    """若能从 '[uid=xxx]author[/uid]' 提取到 uid，则追加 '(uid)'。"""
    if len(author) > 7 and author[:7] == "#anony_":
        return anony(author)
    m = re.search(r"\[uid=(\d+?)\]" + re.escape(author) + r"\[\/uid\]", cont)
    if m:
        return f"{author}({m.group(1)})"
    return author


def _fix_quote(cont: str, floor: Floor) -> str:
    # --- 圈主贴 ---
    re_main = RE_QUOTE_MAIN_WITH_UID if "uid=" in cont else RE_QUOTE_MAIN_NO_UID
    for m in re_main.finditer(cont):
        author = _author_with_uid(cont, m.group(1))
        quote_time = m.group(2)
        quote_text = m.group(3).replace("\n", "\n>")
        cont = cont.replace(m.group(0), f">[jump](#pid0) {author}({quote_time}) 说: {quote_text}\n\n")
        if floor is not None:
            floor.append_pid.append(0)

    # --- 圈其他楼 ---
    quote_count = cont.count("[quote]")
    for _ in range(quote_count):
        quote_start = cont.rfind("[quote]")
        if quote_start < 0:
            break
        end_rel = cont[quote_start:].find("[/quote]")
        if end_rel < 0:
            break
        quote_end = quote_start + end_rel
        clip = cont[quote_start:quote_end + 8]

        re_other = RE_QUOTE_OTHER_WITH_UID if "uid=" in clip else RE_QUOTE_OTHER_NO_UID
        for m in re_other.finditer(clip):
            quote_pid = m.group(1)
            author = _author_with_uid(cont, m.group(2))
            quote_time = m.group(3)
            quote_text = m.group(4).replace("\n", "\n>")
            cont = cont.replace(f"[url={quote_pid}]{m.group(2)}[/url]",
                                f"[{m.group(2)}]({quote_pid})")
            cont = cont.replace(m.group(0),
                                f">[jump](#pid{quote_pid}) {author}({quote_time}) 说: {quote_text}\n\n")
    return cont


def _fix_reply(cont: str, floor: Floor) -> str:
    # 回复主楼
    re_tid = RE_REPLY_TID_WITH_UID if "uid=" in cont else RE_REPLY_TID_NO_UID
    for m in re_tid.finditer(cont):
        author = _author_with_uid(cont, m.group(2))
        quote_time = m.group(3)
        cont = cont.replace(m.group(0), f">[jump](#pid0) {author}({quote_time}):\n\n")

    # 回复其他楼
    re_pid = RE_REPLY_PID_WITH_UID if "uid=" in cont else RE_REPLY_PID_NO_UID
    for m in re_pid.finditer(cont):
        quote_pid = m.group(1)
        author = _author_with_uid(cont, m.group(2))
        quote_time = m.group(3)
        cont = cont.replace(m.group(0), f">[jump](#pid{quote_pid}) {author}({quote_time}):\n\n")
        if floor is not None:
            floor.append_pid.append(int(quote_pid))
    return cont


def _fix_most(cont: str, floor: Floor) -> str:
    for old, new in _REPLACEMENTS.items():
        cont = cont.replace(old, new)
    cont = _fix_anony(cont, floor)
    # 骰子
    for m in RE_DICE.finditer(cont):
        cont = cont.replace(m.group(0), f" **【ROLL** : {m.group(1)}= **{m.group(3)}】** ")
    # 折叠
    for m in RE_COLLAPSE.finditer(cont):
        inner = m.group(2).replace("\n", "<br>")
        cont = cont.replace(m.group(0),
                            f"<details>\n  <summary>{m.group(1)}</summary>\n  <pre>{inner}</pre>\n</details>")
    # 表情（在线模式）
    for it in RE_SMILE.findall(cont):
        alt = it.split(":", 2)[2]
        smile_file = SMILE_MAP.get(it, "").replace('"', "")
        cont = cont.replace(it, f"![{alt}(https://img4.nga.178.com/ngabbs/post/smile/{smile_file})")
    # URL
    for m in RE_URL1.finditer(cont):
        cont = cont.replace(m.group(0), f"[url]({m.group(1)})")
    for m in RE_URL2.finditer(cont):
        cont = cont.replace(m.group(0), f"[{m.group(2)}]({m.group(1)})")
    # 引用 / 回复
    cont = _fix_quote(cont, floor)
    cont = _fix_reply(cont, floor)
    return cont


# ---------- 楼层内容入口（Go nga.go:720 fixContent） ----------

def fix_floor(floor: Floor, tiezi: Tiezi) -> None:
    ori_comments = floor.comments
    cur = floor
    cur_comment_i = -1
    while True:
        cont = cur.content
        cont = _fix_most(cont, cur)
        cont = _process_media(cont, RE_VIDEO_CONTENT, RE_VIDEO_SRC, "视频", tiezi, cur, False)
        cont = _process_media(cont, RE_AUDIO_CONTENT, RE_VIDEO_SRC, "音频", tiezi, cur, False)
        cont = _process_media(cont, RE_IMG_CONTENT, None, "图片", tiezi, cur, True)
        cur.content = cont
        if cur_comment_i + 1 < len(ori_comments):
            cur = ori_comments[cur_comment_i + 1]
            cur_comment_i += 1
        else:
            break
