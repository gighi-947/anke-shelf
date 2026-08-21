"""Small helpers for rendering Gululu rich-text marks."""
from __future__ import annotations

import html
import re
from typing import Callable, Iterable, Optional

from .gululu_assistant import render_assistant_node
from .gululu_immersive import background_attribute, render_immersive_node


_HEX_COLOR = re.compile(r"#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?")
_RGB_COLOR = re.compile(
    r"rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)",
    re.IGNORECASE,
)


def _safe_color(value: object) -> str:
    raw = str(value or "").strip()
    if _HEX_COLOR.fullmatch(raw):
        return raw
    match = _RGB_COLOR.fullmatch(raw)
    if match and all(0 <= int(part) <= 255 for part in match.groups()):
        return f"rgb({int(match[1])}, {int(match[2])}, {int(match[3])})"
    return ""


def render_marks(text: str, marks: object) -> str:
    rendered = html.escape(text)
    for mark in marks if isinstance(marks, list) else []:
        if not isinstance(mark, dict):
            continue
        mark_type = str(mark.get("type") or "")
        if mark_type == "bold":
            rendered = f"<strong>{rendered}</strong>"
        elif mark_type == "italic":
            rendered = f"<em>{rendered}</em>"
        elif mark_type == "strike":
            rendered = f"<del>{rendered}</del>"
        elif mark_type == "underline":
            rendered = f"<u>{rendered}</u>"
        elif mark_type == "textStyle":
            attrs = mark.get("attrs") if isinstance(mark.get("attrs"), dict) else {}
            color = _safe_color(attrs.get("color"))
            if color:
                rendered = f'<span style="color:{color}">{rendered}</span>'
        elif mark_type:
            rendered = (
                f'<span class="unsupported-mark" data-mark="{html.escape(mark_type)}">'
                f"{rendered}</span>"
            )
    return rendered


def render_ast(
    nodes: Iterable[dict],
    *,
    image_resolver: Optional[Callable[[str], str]] = None,
    jump_floor_resolver: Optional[Callable[[int], str]] = None,
    source_book_id: int = 0,
    strict: bool = False,
) -> str:
    """Recursively convert the known Gululu rich-text AST to safe XHTML."""
    resolver = image_resolver or (lambda url: url)

    def render_children(node: dict) -> str:
        content = node.get("content")
        if not isinstance(content, list):
            return ""
        return "".join(render_node(child) for child in content if isinstance(child, dict))

    def render_node(node: dict) -> str:
        node_type = str(node.get("type") or "")
        attrs = node.get("attrs") if isinstance(node.get("attrs"), dict) else {}
        assistant_html = render_assistant_node(
            node_type,
            attrs,
            lambda: render_children(node),
            jump_floor_resolver,
            source_book_id,
        )
        if assistant_html is not None:
            return assistant_html
        immersive_html = render_immersive_node(node_type, attrs)
        if immersive_html is not None:
            return immersive_html
        if node_type == "text":
            return render_marks(str(node.get("text") or ""), node.get("marks"))
        if node_type == "hardBreak":
            return "<br/>"
        if node_type == "paragraph":
            content = render_children(node)
            paragraph_id = attrs.get("id")
            paragraph_attr = ""
            if paragraph_id not in (None, ""):
                paragraph_attr = (
                    f' data-paragraph-id="{html.escape(str(paragraph_id), quote=True)}"'
                )
            if content:
                return f"<p{paragraph_attr}>{content}</p>"
            return f'<p class="empty-paragraph"{paragraph_attr}>&#160;</p>'
        if node_type == "heading":
            try:
                source_level = int(attrs.get("level", 2))
            except (TypeError, ValueError):
                source_level = 2
            level = min(6, max(3, source_level + 1))
            return f"<h{level}>{render_children(node)}</h{level}>"
        if node_type == "image":
            source = str(attrs.get("src") or "").strip()
            if not source.startswith("https://"):
                return '<p class="image-unavailable">[图片地址不可用]</p>'
            resolved = resolver(source)
            if not resolved:
                return '<p class="image-omitted">[图片已省略]</p>'
            alt = html.escape(str(attrs.get("alt") or "图片"))
            image = (
                f'<img src="{html.escape(resolved)}" alt="{alt}" '
                'loading="lazy" decoding="async"/>'
            )
            if str(attrs.get("avatar") or "").lower() == "true":
                image = f'<span class="avatar-image">{image}</span>'
            background_attr = background_attribute(attrs)
            return f'<figure class="gululu-image"{background_attr}>{image}</figure>'
        if node_type == "collapsibleBlock":
            # 官方 collapsibleBlock：首个子块是折叠标题（summary），其余子块才是正文；
            # collapsed="false" 时默认展开。旧书/异常数据缺首子块时回退“折叠内容”。
            content = node.get("content")
            if not isinstance(content, list):
                content = []
            first = content[0] if content and isinstance(content[0], dict) else None
            summary = render_children(first) if first else ""
            if not summary:
                summary = "折叠内容"
            body = "".join(
                render_node(child)
                for child in content[1:]
                if isinstance(child, dict)
            )
            open_attr = ""
            if str(attrs.get("collapsed") or "").lower() == "false":
                open_attr = ' open="open"'
            return (
                f'<details class="gululu-fold"{open_attr}>'
                f"<summary>{summary}</summary>"
                f"{body}</details>"
            )
        if strict:
            from .gululu_client import GululuFormatError
            raise GululuFormatError(f"暂不支持的骨碌碌正文节点：{node_type or 'unknown'}")
        label = html.escape(node_type or "unknown")
        return f'<div class="unsupported-node">[暂不支持的内容：{label}]</div>'

    return "".join(render_node(node) for node in nodes if isinstance(node, dict))
