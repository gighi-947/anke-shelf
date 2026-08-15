"""Small helpers for rendering Gululu rich-text marks."""
from __future__ import annotations

import html
import re


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
