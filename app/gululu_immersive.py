"""Parse Gululu author directives into inert EPUB semantic markers."""
from __future__ import annotations

import copy
import html
import re
import urllib.parse
from dataclasses import dataclass


_MANUAL_MUSIC = re.compile(
    r"^\s*<音乐>\s*(.*?)\s*[♪♫]\s*(.*?)\s*</音乐结束>\s*$",
    re.DOTALL,
)
_AUTO_MUSIC = re.compile(
    r"^\s*<自动音乐>\s*(.*?)\s*[♪♫]\s*(.*?)\s*</自动音乐结束>\s*$",
    re.DOTALL,
)
_VFX = re.compile(r"^\s*<特效[:：]\s*(.*?)\s*>\s*$", re.DOTALL)

_VFX_NAMES = {
    "下雨": "rain",
    "雨": "rain",
    "rain": "rain",
    "下雪": "snow",
    "雪": "snow",
    "snow": "snow",
    "打雷": "thunder",
    "雷": "thunder",
    "thunder": "thunder",
    "lightning": "thunder",
    "地震": "quake",
    "震动": "quake",
    "quake": "quake",
    "earthquake": "quake",
    "狂风": "wind",
    "风": "wind",
    "wind": "wind",
    "gale": "wind",
    "停止": "stop",
    "关闭": "stop",
    "stop": "stop",
    "clear": "stop",
}


@dataclass(frozen=True)
class ImmersiveFloor:
    nodes: list[dict]
    vfx: str = ""
    background_update: str | None = None


def safe_https_url(value: object) -> str:
    raw = str(value or "").strip()
    if not raw or len(raw) > 2048 or any(ord(char) < 32 for char in raw):
        return ""
    try:
        parsed = urllib.parse.urlsplit(raw)
    except ValueError:
        return ""
    if (
        parsed.scheme.lower() != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
    ):
        return ""
    return raw


def _node_text(node: dict) -> str:
    if str(node.get("type") or "") == "text":
        return str(node.get("text") or "")
    content = node.get("content")
    if not isinstance(content, list):
        return ""
    return "".join(_node_text(child) for child in content if isinstance(child, dict))


def _directive_error(message: str) -> dict:
    return {
        "type": "gululuDirectiveError",
        "attrs": {"message": message},
        "content": [],
    }


def background_attribute(attrs: dict) -> str:
    background = str(attrs.get("gululuBackground") or "")
    if not background:
        return ""
    return f' data-gululu-background-url="{html.escape(background, quote=True)}"'


def render_immersive_node(node_type: str, attrs: dict) -> str | None:
    if node_type == "gululuMusic":
        title = str(attrs.get("title") or "BGM")
        url = html.escape(str(attrs.get("url") or ""), quote=True)
        auto = ' data-gululu-music-auto="true"' if attrs.get("auto") else ""
        label = "自动音乐" if attrs.get("auto") else "音乐"
        return (
            '<p class="gululu-music-row">'
            f'<button type="button" class="gululu-music-cue" '
            f'data-gululu-music-url="{url}"{auto}>'
            f'<span class="gululu-music-kind">{label}</span>'
            f'<span class="gululu-music-title">{html.escape(title)}</span>'
            "</button></p>"
        )
    if node_type == "gululuMusicStop":
        return (
            '<span class="gululu-music-stop" data-gululu-music-stop="true" '
            'role="button" tabindex="0" aria-label="停止音乐">&#9632;</span>'
        )
    if node_type == "gululuBackgroundClear":
        return (
            '<span class="gululu-immersive-marker" '
            'data-gululu-background-clear="true" aria-hidden="true"></span>'
        )
    if node_type == "gululuDirectiveError":
        message = html.escape(str(attrs.get("message") or "沉浸指令不可用"))
        return f'<p class="gululu-directive-error">[{message}]</p>'
    return None


def prepare_immersive_floor(nodes: object) -> ImmersiveFloor:
    """Recognize complete directive paragraphs without mutating the API snapshot."""
    if not isinstance(nodes, list):
        return ImmersiveFloor([])

    output: list[dict] = []
    vfx = ""
    in_background = False
    background_update: str | None = None

    for source in nodes:
        if not isinstance(source, dict):
            continue
        node = copy.deepcopy(source)
        node_type = str(node.get("type") or "")
        text = _node_text(node).strip() if node_type == "paragraph" else ""

        music = _AUTO_MUSIC.fullmatch(text) or _MANUAL_MUSIC.fullmatch(text)
        if music:
            title = music.group(1).strip() or "BGM"
            url = safe_https_url(music.group(2))
            if not url:
                output.append(_directive_error(f"音乐链接不可用：{title}"))
            else:
                output.append({
                    "type": "gululuMusic",
                    "attrs": {
                        "title": title,
                        "url": url,
                        "auto": bool(_AUTO_MUSIC.fullmatch(text)),
                    },
                    "content": [],
                })
            continue

        if text == "<停止音乐>":
            output.append({"type": "gululuMusicStop", "attrs": {}, "content": []})
            continue

        effect_match = _VFX.fullmatch(text)
        if effect_match:
            requested = effect_match.group(1).strip()
            mapped = _VFX_NAMES.get(requested.lower(), "")
            if mapped:
                if not vfx:
                    vfx = mapped
            else:
                output.append(_directive_error(f"暂不支持的特效：{requested or '空'}"))
            continue

        if text == "<背景>":
            in_background = True
            continue
        if text == "</背景>":
            if in_background:
                in_background = False
                continue
        if text in {"<移除背景>", "<清除背景>", "<恢复背景>"}:
            output.append({"type": "gululuBackgroundClear", "attrs": {}, "content": []})
            background_update = ""
            continue

        if in_background and node_type == "image":
            attrs = node.get("attrs") if isinstance(node.get("attrs"), dict) else {}
            background_url = safe_https_url(attrs.get("src"))
            if background_url:
                attrs["gululuBackground"] = background_url
                node["attrs"] = attrs
                background_update = background_url
            else:
                output.append(_directive_error("背景图片链接不可用"))
        output.append(node)

    if in_background:
        output.append(_directive_error("背景指令缺少结束标记"))
    return ImmersiveFloor(output, vfx=vfx, background_update=background_update)
