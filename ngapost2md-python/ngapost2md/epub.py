"""EPUB 导出：将帖子打包为还原 NGA 风格的电子书。

基于 ebooklib，从 Floor.raw_content 渲染 HTML（format_html），
默认嵌入图片，可通过 --epub-images online 切换为在线引用。
"""
import hashlib
import html as html_mod
import io
import logging
import os
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

from .format import fetch_bytes, normalize_image_url, ts2t
from .format_html import (
    NGA_COLORS,
    NGA_COLORS_DARK,
    NGA_THEME_DARK,
    NGA_THEME_LIGHT,
    get_theme,
    render_content_html,
    set_colors,
    set_no_images,
    set_theme,
)
from .models import Tiezi

log = logging.getLogger("ngapost2md")

_MIME = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".gif": "image/gif",
    ".webp": "image/webp",
}

def _build_css(theme: str = "light", text_color: str = "", bg_color: str = "") -> str:
    """按主题生成 CSS。theme: light/dark；text_color/bg_color 可覆盖默认。

    颜色 class 采用 NGA 对应的标准 CSS 颜色值，light 主题精确还原原帖色，
    dark 主题为深底可读性做调亮。
    """
    if theme == "dark":
        p = {"bg": "#1e1e1e", "text": "#d0d0d0", "muted": "#8a8a8a",
             "border": "#3a3a3a", "quote_bg": "#2a2a2a", "comment_bg": "#262626",
             "accent": "#5ba3d9", "dice": "#d9b45b"}
        colors = NGA_COLORS_DARK
    else:
        p = {"bg": "#ffffff", "text": "#222222", "muted": "#888888",
             "border": "#e0e0e0", "quote_bg": "#f7f7f7", "comment_bg": "#fafafa",
             "accent": "#2e86ab", "dice": "#b8860b"}
        colors = NGA_COLORS
    if text_color:
        p["text"] = text_color
    if bg_color:
        p["bg"] = bg_color
    css = f"""body {{ font-family: "Source Han Serif SC","Noto Serif CJK SC","SimSun",serif;
       line-height:1.8; margin:1em 1.2em; color:{p['text']}; background:{p['bg']}; }}
h1 {{ font-size:1.5em; margin:.4em 0; }}
h2 {{ font-size:1.3em; }}
.book-meta {{ color:{p['muted']}; font-size:.9em; margin-bottom:1em; }}
.nga-floor {{ border:1px solid {p['border']}; border-left:4px solid {p['accent']};
             padding:12px 14px; margin:14px 0; border-radius:2px; }}
.floor-head {{ color:{p['muted']}; font-size:.82em; border-bottom:1px dotted {p['border']};
              padding-bottom:6px; margin-bottom:8px; }}
.floor-head .lou {{ color:{p['accent']}; font-weight:bold; }}
.nga-quote {{ border-left:3px solid {p['border']}; background:{p['quote_bg']}; padding:8px 12px;
             margin:10px 0; font-size:.95em; }}
.quote-author {{ color:{p['muted']}; font-size:.9em; margin-bottom:4px; }}
.reply-to {{ color:{p['accent']}; font-size:.85em; text-decoration:none; }}
.uid {{ color:{p['accent']}; }}
.nga-img {{ max-width:100%; height:auto; margin:6px 0; }}
.smile {{ vertical-align:middle; }}
.nga-dice {{ color:{p['dice']}; font-weight:bold; margin:6px 0; }}
details {{ margin:8px 0; }}
details summary {{ cursor:pointer; color:{p['accent']}; }}
del {{ color:{p['muted']}; }}
.nga-comment {{ background:{p['comment_bg']}; border:1px solid {p['border']}; padding:8px 10px;
               margin:6px 0 6px 14px; font-size:.92em; }}
.comment-head {{ color:{p['muted']}; font-size:.8em; display:block; margin-bottom:4px; }}
a {{ color:{p['accent']}; text-decoration:none; }}
"""
    for name, val in colors.items():
        css += f"span.{name} {{ color:{val}; }}\n"
    return css


def _ext_of(url: str) -> str:
    path = urllib.parse.urlparse(url).path
    ext = os.path.splitext(path)[1].lower()
    return ext if ext in _MIME else ".jpg"


def compress_image(data: bytes, max_size: int, quality: int) -> tuple[bytes, str | None]:
    """统一压缩为 WebP。未装 Pillow 或压缩失败时原样返回 (data, None)。"""
    try:
        from PIL import Image
    except ImportError:
        return data, None
    try:
        im = Image.open(io.BytesIO(data))
        has_alpha = im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info)
        im = im.convert("RGBA" if has_alpha else "RGB")
        if max_size > 0 and max(im.size) > max_size:
            ratio = max_size / max(im.size)
            im = im.resize((int(im.width * ratio), int(im.height * ratio)), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "WEBP", quality=quality, method=4)
        return buf.getvalue(), "image/webp"
    except Exception:  # noqa: BLE001
        return data, None


class _ImageStore:
    """图片资源：嵌入模式下载并记录 EPUB 内部路径；在线模式直接用 URL。"""

    def __init__(self, tiezi: Tiezi, image_mode: str):
        self._tiezi = tiezi
        self._mode = image_mode
        self._images: dict[str, str] = {}  # url -> epub 内部路径
        self._urls: list[tuple[str, str]] = []  # (url, epub 内部路径)

    def resolve(self, url: str) -> str:
        url = normalize_image_url(url)
        if self._mode == "online":
            return url
        if url in self._images:
            return self._images[url]
        sha = hashlib.sha256(url.encode("utf-8")).hexdigest()
        # 统一 WebP 扩展名（图片会在下载后压缩为 WebP）
        epub_path = f"images/{sha[2:10]}.webp"
        self._images[url] = epub_path
        self._urls.append((url, epub_path))
        return epub_path

    def items(self):
        return list(self._urls)

    def fetch(self, url: str) -> bytes:
        return fetch_bytes(url)


def _floor_html(tiezi: Tiezi, floor, img_src) -> str:
    t = get_theme()
    floor_style = (f'border:1px solid {t["border"]}; border-left:4px solid {t["accent"]}; '
                   f'padding:12px 14px; margin:14px 0; border-radius:2px;')
    head_style = (f'color:{t["muted"]}; font-size:.82em; border-bottom:1px dotted {t["border"]}; '
                  f'padding-bottom:6px; margin-bottom:8px;')
    comment_style = (f'background:{t["comment_bg"]}; border:1px solid {t["border"]}; '
                     f'padding:8px 10px; margin:6px 0 6px 14px; font-size:.92em;')
    head = (f'<span class="lou" style="color:{t["accent"]}; font-weight:bold;">{floor.lou}楼</span> '
            f'· {floor.like_num}赞 · '
            f'{html_mod.escape(floor.username)}({floor.user_id}) · {ts2t(floor.timestamp)}'
            f'<span class="pid"> · pid:{floor.pid}</span>')
    body = render_content_html(floor.raw_content, tiezi, img_src)
    html = (f'<div class="nga-floor" id="pid{floor.pid}" style="{floor_style}">'
            f'<div class="floor-head" style="{head_style}">{head}</div>'
            f'<div class="floor-body">{body}</div></div>')
    for comment in floor.comments:
        if comment.lou <= 0:
            continue
        c_body = render_content_html(comment.raw_content, tiezi, img_src)
        c_head = (f'{comment.lou}楼 · {html_mod.escape(comment.username)}({comment.user_id}) · '
                  f'{ts2t(comment.timestamp)}')
        html += (f'<div class="nga-comment" style="{comment_style}">'
                 f'<span class="comment-head" style="color:{t["muted"]}; font-size:.8em; '
                 f'display:block; margin-bottom:4px;">{c_head}</span>{c_body}</div>')
    return html


def _group_floors(tiezi: Tiezi, per_chapter: int) -> list[list]:
    valid = [f for f in tiezi.floors if f.lou != -1]
    if tiezi.max_lou >= 0:
        valid = [f for f in valid if f.lou <= tiezi.max_lou]
    if not valid:
        return []
    main = valid[0] if valid[0].pid == 0 else None
    rest = valid[1:] if main else valid
    groups: list[list] = []
    if main:
        groups.append([main])
    for i in range(0, len(rest), per_chapter):
        groups.append(rest[i:i + per_chapter])
    return groups


def build_epub(tiezi: Tiezi, cfg, per_chapter: int = 20, image_mode: str = "embedded",
               toc_chapters: list | None = None, progress=None, cancel=None,
               no_images: bool = False) -> str:
    """构建 EPUB。progress: callable(dict) 报告图片进度；cancel: callable() -> bool。"""
    from ebooklib import epub

    output_dir = os.path.join(cfg.output_path, tiezi.folder_name or str(tiezi.tid))
    epub_path = os.path.join(output_dir, "post.epub")

    store = _ImageStore(tiezi, image_mode)
    img_src = store.resolve

    book = epub.EpubBook()
    book.set_identifier(f"ngapost2md-{tiezi.tid}")
    book.set_title(tiezi.title or f"NGA tid {tiezi.tid}")
    book.set_language("zh")
    if tiezi.username:
        book.add_author(tiezi.username)
    book.add_metadata("DC", "description", f"NGA tid {tiezi.tid} 帖子导出")

    theme = getattr(cfg, "epub_theme", "light")
    set_colors(NGA_COLORS_DARK if theme == "dark" else NGA_COLORS)
    set_theme(NGA_THEME_DARK if theme == "dark" else NGA_THEME_LIGHT)
    set_no_images(bool(no_images))
    css_item = epub.EpubItem(uid="style_main", file_name="style/main.css",
                             media_type="text/css",
                             content=_build_css(theme,
                                                getattr(cfg, "epub_text_color", ""),
                                                getattr(cfg, "epub_bg_color", "")))
    book.add_item(css_item)

    chapters = []
    pid_to_page: dict[int, str] = {}
    groups = _group_floors(tiezi, per_chapter)
    for gi, group in enumerate(groups):
        parts = []
        first, last = group[0].lou, group[-1].lou
        file_name = f"page_{gi:04d}.xhtml"
        if gi == 0 and group[0].pid == 0:
            title = "序章 · 主楼"
            parts.append(f'<h1>{html_mod.escape(tiezi.title)}</h1>')
            parts.append(f'<div class="book-meta">作者：{html_mod.escape(tiezi.username)} · '
                         f'NGA tid {tiezi.tid}</div>')
            if tiezi.hot_posts:
                parts.append('<h2>热门回复</h2>')
                for v in tiezi.hot_posts:
                    if v.lou == -1:
                        continue
                    content = render_content_html(v.raw_content, tiezi, img_src)
                    parts.append(f'<div class="nga-comment"><span class="comment-head">'
                                 f'{v.lou}楼 · {html_mod.escape(v.username)}({v.user_id})</span>'
                                 f'{content}</div>')
        else:
            title = f"第 {first} 楼" if first == last else f"第 {first}~{last} 楼"
        for floor in group:
            parts.append(_floor_html(tiezi, floor, img_src))
            pid_to_page[floor.pid] = file_name
        body = "".join(parts)
        chapter = epub.EpubHtml(title=title, file_name=file_name, lang="zh")
        chapter.content = ('<html xmlns="http://www.w3.org/1999/xhtml">'
                           '<head><title>%s</title>'
                           '<link rel="stylesheet" type="text/css" href="style/main.css"/>'
                           '</head><body>%s</body></html>' % (title, body))
        book.add_item(chapter)
        chapters.append(chapter)

    # 嵌入图片（并行下载 + 压缩 + 进度统计）
    items = store.items()
    total = len(items)
    ok = fail = 0
    quality = getattr(cfg, "epub_image_quality", 85)
    max_size = getattr(cfg, "epub_image_max_size", 1280)

    def _fetch_one(url, epub_path_in):
        try:
            data = store.fetch(url)
            data, mtype = compress_image(data, max_size, quality)
            return url, epub_path_in, data, mtype, None
        except Exception as e:  # noqa: BLE001
            return url, epub_path_in, None, None, e

    def cancelled() -> bool:
        return bool(cancel and cancel())

    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = [pool.submit(_fetch_one, url, path) for url, path in items]
        for i, fut in enumerate(futures, 1):
            if cancelled():
                from .nga import CancelledError

                raise CancelledError("EPUB 图片下载已取消")
            url, epub_path_in, data, mtype, err = fut.result()
            if err is None and data is not None:
                ok += 1
                media_type = mtype or _MIME.get(os.path.splitext(epub_path_in)[1].lower(), "image/jpeg")
                uid = "img_" + os.path.basename(epub_path_in).replace(".", "_")
                img_item = epub.EpubImage(uid=uid, file_name=epub_path_in,
                                          media_type=media_type, content=data)
                book.add_item(img_item)
            else:
                fail += 1
            if i % 50 == 0 or i == total:
                log.info("EPUB 图片嵌入进度: %d/%d  成功=%d  失败=%d", i, total, ok, fail)
            if progress and (i % 25 == 0 or i == total):
                try:
                    progress({"current": i, "total": total, "ok": ok, "fail": fail})
                except Exception:  # noqa: BLE001
                    pass
    log.info("EPUB 图片嵌入完成: 总 %d  成功 %d  失败 %d（失败回退在线引用）", total, ok, fail)

    # 目录：外部目录楼解析出的 TOC，或默认章节导航
    if toc_chapters:
        toc = []
        for ch in toc_chapters:
            links = [epub.Link(f"{pid_to_page[pid]}#pid{pid}", t, f"pid{pid}")
                     for t, pid in ch["lead"] if pid in pid_to_page]
            day_subs = []
            for d in ch["days"]:
                day_links = [epub.Link(f"{pid_to_page[pid]}#pid{pid}", t, f"pid{pid}")
                             for t, pid in d["entries"] if pid in pid_to_page]
                if day_links:
                    day_subs.append((epub.Section(d["day"]), day_links))
            subs = links + day_subs
            if subs:
                toc.append((epub.Section(ch["title"]), subs))
        book.toc = toc
    else:
        book.toc = chapters

    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = ["nav"] + chapters
    epub.write_epub(epub_path, book)
    log.info("EPUB 导出完成: %s", epub_path)
    return epub_path
