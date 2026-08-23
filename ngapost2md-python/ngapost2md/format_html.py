"""NGA 原始内容 → 还原风格的 HTML（供 EPUB 使用）。

Markdown 管线会丢失 NGA 的样式元素（span 颜色、引用块、折叠等）。
本模块从 Floor.raw_content（未转 Markdown 的原始 HTML+BBCode）直接渲染 HTML，
最大程度还原 NGA 网页排版。输出为 EPUB3 要求的 XHTML 片段。
"""
import html
import re

from .models import Tiezi
from .smile_map import SMILE_MAP
from .format import anony, normalize_image_url

RE_SCRIPT = re.compile(r"<script.*?</script>", re.DOTALL | re.IGNORECASE)
RE_STYLE = re.compile(r"<style.*?</style>", re.DOTALL | re.IGNORECASE)
RE_BR = re.compile(r"<br\s*/?>", re.IGNORECASE)

RE_B = re.compile(r"\[b\](.*?)\[/b\]", re.DOTALL)
RE_I = re.compile(r"\[i\](.*?)\[/i\]", re.DOTALL)
RE_URL = re.compile(r"\[url=(.+?)\](.+?)\[/url\]")
RE_URL_PLAIN = re.compile(r"\[url\](.+?)\[/url\]")
RE_AUDIO = re.compile(r"\[audio\](https://[^\[\]\"\s]+)\[/audio\]")
RE_IMG = re.compile(r"\[img\](.+?)\[/img\]")
RE_SMILE = re.compile(r"\[s\:.+?\:.+?\]")
RE_UID = re.compile(r"\[uid=(\d+?)\](.+?)\[/uid\]")
RE_PID_REPLY = re.compile(r"\[pid=(\d+?),.*?\]Reply\[/pid\]")
RE_COLOR = re.compile(r"\[color=([^\]]+)\](.*?)\[/color\]", re.DOTALL)
RE_COLOR_OK = re.compile(r"^[a-zA-Z]+$|^#[0-9a-fA-F]{3,8}$")
RE_ANONY = re.compile(r"#anony_.{32}")

RE_DEL_GRAY = re.compile(r"<del class=['\"]gray['\"]>")
RE_DICE = re.compile(r"<div class='dice'><b>ROLL : (.+?)</b>=(.+?)=<b>(.+?)</b></div>")
RE_COLLAPSE = re.compile(
    r'<div class="foldBox no"><div class="collapse_btn"><a href="javascript:;" '
    r'onclick="collapse\(this\);">\+</a>(.+?) ...</div>'
    r'<span class="collapse_content" id="foldCnt">(.+?)</span></div>'
)
RE_POSTBY_UID = re.compile(r"<b>Post by \[uid=(\d+?)\](.+?)\[\/uid\][^<]*?\((\d{4}.*?)\):</b>")
RE_POSTBY_ANONY = re.compile(r"<b>Post by (.+?)<span .*?\((\d{4}.*?)\):</b>")

SMILE_BASE = "https://img4.nga.178.com/ngabbs/post/smile/"

# NGA 颜色 class 的标准 CSS 颜色值（class 名即 CSS 标准色名）
NGA_COLORS = {
    "red": "#ff0000", "skyblue": "#87ceeb", "royalblue": "#4169e1",
    "blue": "#0000ff", "darkblue": "#00008b", "orange": "#ffa500",
    "orangered": "#ff4500", "crimson": "#dc143c", "firebrick": "#b22222",
    "darkred": "#8b0000", "green": "#008000", "limegreen": "#32cd32",
    "seagreen": "#2e8b57", "teal": "#008080", "deeppink": "#ff1493",
    "tomato": "#ff6347", "coral": "#ff7f50", "purple": "#800080",
    "indigo": "#4b0082", "burlywood": "#deb887", "sandybrown": "#f4a460",
    "chocolate": "#d2691e", "sienna": "#a0522d", "silver": "#c0c0c0",
    "gray": "#808080", "gold": "#ffd700", "brown": "#a52a2a", "azure": "#007fff",
}
# dark 主题下为保证深底可读而调亮的对应色
NGA_COLORS_DARK = {
    "red": "#ff6b6b", "skyblue": "#7ec8e3", "royalblue": "#7ba0ff",
    "blue": "#6ea8fe", "darkblue": "#6a8cff", "orange": "#ffb86b",
    "orangered": "#ff6a52", "crimson": "#ff7070", "firebrick": "#d76a6a",
    "darkred": "#c0504d", "green": "#6bc26b", "limegreen": "#5edc5e",
    "seagreen": "#5ec488", "teal": "#3ddad0", "deeppink": "#ff4fa3",
    "tomato": "#ff7a6b", "coral": "#ff9480", "purple": "#b18bff",
    "indigo": "#8a7bff", "burlywood": "#e0c3a0", "sandybrown": "#f7b774",
    "chocolate": "#e08a5a", "sienna": "#d0935f", "silver": "#bdbdbd",
    "gray": "#a0a0a0", "gold": "#ffd766", "brown": "#c98f5f",
    "azure": "#5bc8ff",
}

_COLORS = NGA_COLORS  # 当前主题颜色映射，由 build_epub 设置
RE_SPAN_CLASS = re.compile(r'<span class="([a-zA-Z]+)">')

_NO_IMAGES = False  # 集成层开关：EPUB 中移除全部图片（与 Markdown --no-images 语义一致）

# 布局主题色（引用块/楼层卡片/骰子等），light/dark
NGA_THEME_LIGHT = {"border": "#e0e0e0", "quote_bg": "#f7f7f7", "comment_bg": "#fafafa",
                   "floor_bg": "#fafafa", "accent": "#2e86ab", "dice": "#b8860b", "muted": "#888888"}
NGA_THEME_DARK = {"border": "#3a3a3a", "quote_bg": "#2a2a2a", "comment_bg": "#262626",
                  "floor_bg": "#2a2a2a", "accent": "#5ba3d9", "dice": "#d9b45b", "muted": "#8a8a8a"}
_THEME = NGA_THEME_LIGHT


def set_colors(colors: dict) -> None:
    """设置当前主题的颜色映射（light/dark），用于内联着色。"""
    global _COLORS
    _COLORS = colors


def set_theme(palette: dict) -> None:
    """设置当前主题的布局色，用于引用块/楼层卡片等内联样式。"""
    global _THEME
    _THEME = palette


def set_no_images(flag: bool) -> None:
    """开关 EPUB 渲染中的图片（集成层调用；不影响 Markdown 管线）。"""
    global _NO_IMAGES
    _NO_IMAGES = bool(flag)


def get_theme() -> dict:
    return _THEME


def _inline_span_colors(c: str) -> str:
    """把已知颜色 span class 内联为 style，保证阅读器忽略外部 CSS 时颜色仍生效。"""
    def repl(m: re.Match) -> str:
        name = m.group(1)
        val = _COLORS.get(name)
        if val:
            return f'<span class="{name}" style="color:{val}">'
        return m.group(0)
    return RE_SPAN_CLASS.sub(repl, c)


def render_content_html(raw: str, tiezi: Tiezi, img_src) -> str:
    """将 NGA 原始 content 渲染为 XHTML 片段。

    img_src: callable(url) -> str，用于解析图片/表情地址。
     嵌入模式返回 EPUB 内部路径；在线模式返回原始 URL。
    """
    c = RE_SCRIPT.sub("", raw)
    c = RE_STYLE.sub("", c)

    # 折叠
    c = RE_COLLAPSE.sub(
        lambda m: f"<details><summary>{m.group(1)}</summary><div>{m.group(2)}</div></details>",
        c,
    )
    # 骰子
    c = RE_DICE.sub(
        lambda m: (f'<div class="nga-dice" style="color:{_THEME["dice"]}; font-weight:bold; '
                   f'margin:6px 0;">ROLL : {m.group(1)}= <b>{m.group(3)}</b></div>'),
        c,
    )
    # 外链音乐（方案 A）：[audio]https://…[/audio] → 骨碌碌同款音乐 cue，
    # 复用双端宿主层播放器与样式；cue 文本进坐标（与骨碌碌一致，
    # 提取器与 JS TextPos 同源提取，搜索索引与渲染坐标不漂移）。
    # 非 https 外链不转换（播放桥只收 https，保留原文双端一致降级）。
    c = RE_AUDIO.sub(
        lambda m: (
            '<p class="gululu-music-row">'
            f'<button type="button" class="gululu-music-cue" '
            f'data-gululu-music-url="{m.group(1)}">'
            '<span class="gululu-music-kind">外链音乐</span>'
            f'<span class="gululu-music-title">{html.escape(m.group(1))}</span>'
            "</button></p>"
        ),
        c,
    )
    # 匿名 ID
    c = RE_ANONY.sub(lambda m: _safe_anony(m.group(0)), c)
    # 引用头（Post by ...:）
    c = RE_POSTBY_UID.sub(
        lambda m: f'<div class="quote-author">Post by {m.group(2)}({m.group(1)})({m.group(3)}):</div>',
        c,
    )
    c = RE_POSTBY_ANONY.sub(
        lambda m: f'<div class="quote-author">Post by {m.group(1)}({m.group(2)}):</div>',
        c,
    )
    # 回复引用
    c = RE_PID_REPLY.sub(
        lambda m: f'<a class="reply-to" href="#pid{m.group(1)}">回复</a>',
        c,
    )
    # uid
    c = RE_UID.sub(lambda m: f'<span class="uid">{m.group(2)}</span>', c)
    # quote → blockquote（内联样式，防阅读器忽略外部 CSS）
    qstyle = (f'border-left:3px solid {_THEME["border"]}; background:{_THEME["quote_bg"]}; '
              f'padding:8px 12px; margin:10px 0; font-size:.95em;')
    c = c.replace("[quote]", f'<blockquote class="nga-quote" style="{qstyle}">')
    c = c.replace("[/quote]", "</blockquote>")
    # 删除线
    c = RE_DEL_GRAY.sub("<del>", c)
    # 加粗 / 斜体
    c = RE_B.sub(r"<b>\1</b>", c)
    c = RE_I.sub(r"<i>\1</i>", c)
    # 链接
    c = RE_URL.sub(r'<a href="\1">\2</a>', c)
    c = RE_URL_PLAIN.sub(r'<a href="\1">\1</a>', c)
    # 自定义颜色 [color=名/#hex]文字[/color] → 内联样式
    c = RE_COLOR.sub(_color_html, c)
    # 表情
    c = RE_SMILE.sub(lambda m: _smile_html(m, img_src), c)
    # 图片
    c = RE_IMG.sub(lambda m: _img_html(m, img_src), c)
    # 换行统一
    c = RE_BR.sub("<br/>", c)
    # 颜色内联（防阅读器忽略外部 CSS）
    return _inline_span_colors(c)


def _color_html(m: re.Match) -> str:
    color = m.group(1).strip()
    if not RE_COLOR_OK.match(color):
        return m.group(0)  # 非法颜色值，保留原文
    return f'<span style="color:{html_escape(color)}">{m.group(2)}</span>'


def html_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;").replace(">", "&gt;")


def _safe_anony(it: str) -> str:
    try:
        return anony(it)
    except Exception:  # noqa: BLE001
        return it


def _smile_html(m: re.Match, img_src) -> str:
    it = m.group(0)
    smile_file = SMILE_MAP.get(it, "")
    if not smile_file:
        return it
    alt = it.split(":", 2)[2].rstrip("]")
    url = SMILE_BASE + smile_file
    return f'<img class="smile" alt="{alt}" src="{img_src(url)}"/>'


def _img_html(m: re.Match, img_src) -> str:
    if _NO_IMAGES:
        return ""
    url = m.group(1)
    url = normalize_image_url(url)
    if len(url) >= 2 and url[:2] == "./":
        url = "https://img.nga.178.com/attachments/" + url[2:]
    return f'<img class="nga-img" src="{img_src(url)}"/>'
