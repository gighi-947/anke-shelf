"""Gululu assistant text protocols and CryptoJS-compatible secret decoding."""
from __future__ import annotations

import base64
import binascii
import copy
import hashlib
import html
from typing import Callable, Iterable

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


MAX_SECRET_TITLE = 120
MAX_SECRET_PASSWORD = 1024
MAX_SECRET_CIPHER = 131072
_INVISIBLE = " \t\r\n\u200b\ufeff"


class GululuSecretError(ValueError):
    """A secret payload cannot be validated or decrypted."""


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


def _inline_protocol_at(text: str, start: int) -> tuple[int, dict] | None:
    prefixes = (
        ("<发现秘密>", "</发现秘密>", "gululuSecretClue"),
        ("<秘密>", "</秘密>", "gululuSecret"),
    )
    for opening, closing, node_type in prefixes:
        if not text.startswith(opening, start):
            continue
        end = text.find(closing, start + len(opening))
        if end < 0:
            return None
        payload = text[start + len(opening):end]
        if not payload.startswith("[") or "]" not in payload:
            return None
        title, value = payload[1:].split("]", 1)
        title = title.strip()
        value = value.strip()
        if not title or len(title) > MAX_SECRET_TITLE:
            return None
        if node_type == "gululuSecret":
            if not value or len(value) > MAX_SECRET_CIPHER:
                return None
            attrs = {"title": title, "cipher": value}
        else:
            if not value or len(value) > MAX_SECRET_PASSWORD:
                return None
            attrs = {"title": title, "password": value}
        return end + len(closing), {"type": node_type, "attrs": attrs, "content": []}
    return None


def _split_protocol_text(node: dict) -> list[dict]:
    text = str(node.get("text") or "")
    output: list[dict] = []
    cursor = 0
    plain_start = 0
    while cursor < len(text):
        if text[cursor] in "\u200b\ufeff":
            cursor += 1
            continue
        parsed = _inline_protocol_at(text, cursor)
        if parsed is None:
            cursor += 1
            continue
        end, protocol_node = parsed
        prefix = text[plain_start:cursor].strip("\u200b\ufeff")
        if prefix:
            plain = copy.deepcopy(node)
            plain["text"] = prefix
            output.append(plain)
        output.append(protocol_node)
        cursor = end
        while cursor < len(text) and text[cursor] in "\u200b\ufeff":
            cursor += 1
        plain_start = cursor
    suffix = text[plain_start:].strip("\u200b\ufeff")
    if suffix:
        plain = copy.deepcopy(node)
        plain["text"] = suffix
        output.append(plain)
    return output or [copy.deepcopy(node)]


def _prepare_inline(node: dict) -> list[dict]:
    if str(node.get("type") or "") == "text":
        return _split_protocol_text(node)
    prepared = copy.deepcopy(node)
    content = prepared.get("content")
    if isinstance(content, list):
        prepared["content"] = [
            item
            for child in content
            if isinstance(child, dict)
            for item in _prepare_inline(child)
        ]
    return [prepared]


def prepare_assistant_nodes(nodes: Iterable[dict]) -> list[dict]:
    """Convert assistant inline secrets and paragraph fold markers to inert AST nodes."""
    prepared = [
        item
        for node in nodes
        if isinstance(node, dict)
        for item in _prepare_inline(node)
    ]
    output: list[dict] = []
    folds: list[dict] = []

    def append(node: dict) -> None:
        target = folds[-1]["content"] if folds else output
        target.append(node)

    for node in prepared:
        text = _node_text(node).strip(_INVISIBLE)
        if text.startswith("<折叠>"):
            title_text = text[len("<折叠>"):].strip()
            if not title_text.startswith("["):
                append(_directive_error("折叠指令缺少标题"))
                continue
            title = title_text[1:-1] if title_text.endswith("]") else title_text[1:]
            title = title.strip()
            if not title:
                append(_directive_error("折叠指令缺少标题"))
                continue
            fold = {
                "type": "gululuAssistantFold",
                "attrs": {"title": title[:MAX_SECRET_TITLE]},
                "content": [],
            }
            append(fold)
            folds.append(fold)
            continue
        if text == "</折叠结束>":
            if folds:
                folds.pop()
            else:
                append(_directive_error("折叠结束标记没有对应的开始标记"))
            continue
        append(node)

    for fold in folds:
        fold["content"].append(_directive_error("折叠指令缺少结束标记"))
    return output


def render_assistant_node(
    node_type: str,
    attrs: dict,
    render_children: Callable[[], str],
    jump_floor_resolver: Callable[[int], str] | None = None,
) -> str | None:
    title = str(attrs.get("title") or "").strip()
    if node_type == "gululuSecret":
        return (
            '<button type="button" class="gululu-secret-cue" '
            f'data-gululu-secret-title="{html.escape(title, quote=True)}" '
            f'data-gululu-secret-cipher="{html.escape(str(attrs.get("cipher") or ""), quote=True)}">'
            f'秘密：{html.escape(title)}</button>'
        )
    if node_type == "gululuSecretClue":
        return (
            '<button type="button" class="gululu-clue-cue" '
            f'data-gululu-secret-title="{html.escape(title, quote=True)}" '
            f'data-gululu-secret-password="{html.escape(str(attrs.get("password") or ""), quote=True)}">'
            f'收集线索：{html.escape(title)}</button>'
        )
    if node_type == "gululuAssistantFold":
        return (
            '<details class="gululu-fold gululu-assistant-fold">'
            f'<summary>{html.escape(title or "折叠内容")}</summary>'
            f'{render_children()}</details>'
        )
    if node_type == "jumpFloorComponent":
        try:
            floor_number = int(attrs.get("floorNumber"))
        except (TypeError, ValueError):
            floor_number = 0
        description = str(attrs.get("description") or "").strip()
        label = html.escape(description or f"跳至第 {floor_number} 楼")
        href = jump_floor_resolver(floor_number) if floor_number > 0 and jump_floor_resolver else ""
        if href:
            return f'<a class="gululu-jump-floor" href="{html.escape(href, quote=True)}">{label}</a>'
        return f'<span class="gululu-jump-floor" data-gululu-jump-floor="{floor_number}">{label}</span>'
    if node_type == "sensitive":
        return '<p class="gululu-sensitive-unavailable">[敏感内容不可用]</p>'
    return None


def decrypt_cryptojs_secret(ciphertext: str, password: str) -> str:
    """Decrypt CryptoJS.AES.encrypt(text, passphrase).toString() output."""
    encoded = str(ciphertext or "").strip()
    password_text = str(password or "")
    if not encoded or len(encoded) > MAX_SECRET_CIPHER or not password_text:
        raise GululuSecretError("秘密数据格式错误")
    try:
        payload = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise GululuSecretError("秘密数据格式错误") from exc
    if len(payload) <= 16 or payload[:8] != b"Salted__" or len(payload[16:]) % 16:
        raise GululuSecretError("秘密数据格式错误")

    salt = payload[8:16]
    material = b""
    previous = b""
    password_bytes = password_text.encode("utf-8")
    while len(material) < 48:
        previous = hashlib.md5(previous + password_bytes + salt).digest()
        material += previous
    decryptor = Cipher(algorithms.AES(material[:32]), modes.CBC(material[32:48])).decryptor()
    padded = decryptor.update(payload[16:]) + decryptor.finalize()
    try:
        unpadder = padding.PKCS7(128).unpadder()
        plaintext = unpadder.update(padded) + unpadder.finalize()
        return plaintext.decode("utf-8")
    except (UnicodeDecodeError, ValueError) as exc:
        raise GululuSecretError("密码错误或秘密数据损坏") from exc
