"""骨碌碌公开阅读接口到标准 EPUB3 的 Windows 端转换器。"""
from __future__ import annotations

import argparse
import html
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Optional

import httpx

from .gululu_ast import render_marks
from .gululu_comments import (
    fetch_comment_scopes,
    fetch_comments_by_floor,
    render_comment_block,
)
from .gululu_epub_styles import GULULU_EPUB_CSS
from .gululu_immersive import (
    ImmersiveFloor,
    background_attribute,
    prepare_immersive_floor,
    render_immersive_node,
)
from .gululu_source import parse_book_id, parse_gululu_identifier


API_BASE = "https://backend.gululu.world"
SITE_BASE = "https://www.gululu.world"
FALLBACK_FLOORS_PER_CHAPTER = 20


class GululuError(Exception):
    """骨碌碌转换失败，message 可直接用于命令行或 UI。"""


class GululuApiError(GululuError):
    """公开阅读 API 的网络、协议或业务错误。"""


class GululuFormatError(GululuError):
    """正文 AST 或 EPUB 输入不符合已知结构。"""


class GululuCancelled(GululuError):
    """调用方请求取消骨碌碌获取或 EPUB 生成。"""


@dataclass(frozen=True)
class GululuSnapshot:
    """一次 EPUB 导出所需的完整公开数据快照。"""

    detail: dict
    floor_index: list[dict]
    chapter_index: list[dict]
    floors: list[dict]
    comments_by_floor: dict[int, list[dict]] = field(default_factory=dict)


class GululuClient:
    """骨碌碌匿名阅读 API 客户端；正文按 ID 分批拉取。"""

    def __init__(
        self,
        http: Optional[httpx.Client] = None,
        *,
        floor_batch_size: int = 20,
        timeout: float = 30.0,
    ) -> None:
        if floor_batch_size < 1:
            raise ValueError("floor_batch_size 必须大于 0")
        self._http = http or httpx.Client(
            base_url=API_BASE,
            timeout=timeout,
            follow_redirects=True,
        )
        self._floor_batch_size = floor_batch_size

    def __enter__(self) -> "GululuClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def close(self) -> None:
        self._http.close()

    def _request_data(self, method: str, path: str, **kwargs):
        headers = dict(kwargs.pop("headers", {}) or {})
        headers["platform"] = "1"
        try:
            response = self._http.request(method, path, headers=headers, **kwargs)
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise GululuApiError(f"骨碌碌接口请求失败（{path}）：{exc}") from exc
        if not isinstance(payload, dict):
            raise GululuApiError(f"骨碌碌接口响应格式错误（{path}）")
        if payload.get("code") != 200:
            message = str(payload.get("msg") or "未知业务错误")
            raise GululuApiError(f"骨碌碌接口返回失败（{path}）：{message}")
        if "data" not in payload:
            raise GululuApiError(f"骨碌碌接口响应缺少 data（{path}）")
        return payload["data"]

    def fetch_snapshot(
        self,
        book_id: int,
        *,
        progress: Optional[Callable[[str, int, int, str], None]] = None,
        cancel: Optional[Callable[[], bool]] = None,
        include_comments: bool = True,
    ) -> GululuSnapshot:
        """读取详情、目录、章节标记和全部楼层，并按目录稳定排序。"""
        def check_cancelled() -> None:
            if cancel is not None and cancel():
                raise GululuCancelled("骨碌碌导入已取消")

        def report(stage: str, current: int, total: int, detail: str) -> None:
            check_cancelled()
            if progress is not None:
                progress(stage, current, total, detail)

        book_id = parse_book_id(book_id)
        report("metadata", 0, 0, "正在读取书籍信息")
        detail = self._request_data("GET", f"/reader/opus/detail/{book_id}")
        report("index", 0, 0, "正在读取目录")
        floor_index = self._request_data("GET", f"/reader/floor/index-list/{book_id}")
        chapter_data = self._request_data(
            "GET", "/reader/opus/chapter-index", params={"opusId": book_id}
        )
        if not isinstance(detail, dict) or not isinstance(floor_index, list):
            raise GululuApiError("骨碌碌书籍详情或楼层目录格式错误")
        if chapter_data is None:
            chapter_data = {}
        elif not isinstance(chapter_data, dict):
            raise GululuApiError("骨碌碌章节目录格式错误")
        chapter_index = chapter_data.get("chapterIndex") or []
        if not isinstance(chapter_index, list):
            raise GululuApiError("骨碌碌 chapterIndex 格式错误")

        floor_ids = []
        for item in floor_index:
            if not isinstance(item, dict) or not isinstance(item.get("floorId"), int):
                raise GululuApiError("骨碌碌楼层目录条目格式错误")
            floor_ids.append(item["floorId"])

        by_id: dict[int, dict] = {}
        total = len(floor_ids)
        for start in range(0, len(floor_ids), self._floor_batch_size):
            check_cancelled()
            batch = floor_ids[start:start + self._floor_batch_size]
            data = self._request_data("POST", "/reader/floor/content-by-ids", json=batch)
            if not isinstance(data, list):
                raise GululuApiError("骨碌碌楼层正文格式错误")
            for floor in data:
                if isinstance(floor, dict) and isinstance(floor.get("id"), int):
                    by_id[floor["id"]] = floor
            current = min(start + len(batch), total)
            report("floors", current, total, f"正在获取楼层 {current}/{total}")

        missing = [floor_id for floor_id in floor_ids if floor_id not in by_id]
        if missing:
            preview = ", ".join(str(value) for value in missing[:5])
            raise GululuApiError(f"骨碌碌楼层正文缺失：{preview}")
        floors = [by_id[floor_id] for floor_id in floor_ids]
        comments_by_floor = {}
        if include_comments:
            try:
                comments_by_floor = fetch_comments_by_floor(
                    self._request_data,
                    book_id,
                    floors,
                    report=report,
                    check_cancelled=check_cancelled,
                )
            except ValueError as exc:
                raise GululuApiError(str(exc)) from exc
        return GululuSnapshot(
            detail,
            list(floor_index),
            list(chapter_index),
            floors,
            comments_by_floor,
        )

    def fetch_comments(self, book_id: int, floor_ids: list[int]) -> dict[int, list[dict]]:
        """读取指定楼层的公开评论；0 表示作品评论。"""
        book_id = parse_book_id(book_id)
        try:
            return fetch_comment_scopes(self._request_data, book_id, floor_ids)
        except ValueError as exc:
            raise GululuApiError(str(exc)) from exc


def render_ast(
    nodes: Iterable[dict],
    *,
    image_resolver: Optional[Callable[[str], str]] = None,
    strict: bool = False,
) -> str:
    """递归转换骨碌碌正文 AST；未知节点默认显示占位，strict 时显式失败。"""
    resolver = image_resolver or (lambda url: url)

    def render_children(node: dict) -> str:
        content = node.get("content")
        if not isinstance(content, list):
            return ""
        return "".join(render_node(child) for child in content if isinstance(child, dict))

    def render_node(node: dict) -> str:
        node_type = str(node.get("type") or "")
        attrs = node.get("attrs") if isinstance(node.get("attrs"), dict) else {}
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
            image = f'<img src="{html.escape(resolved)}" alt="{alt}"/>'
            if str(attrs.get("avatar") or "").lower() == "true":
                image = f'<span class="avatar-image">{image}</span>'
            background_attr = background_attribute(attrs)
            return f'<figure class="gululu-image"{background_attr}>{image}</figure>'
        if node_type == "collapsibleBlock":
            return (
                '<details class="gululu-fold" open="open">'
                "<summary>折叠内容</summary>"
                f'{render_children(node)}</details>'
            )
        if strict:
            raise GululuFormatError(f"暂不支持的骨碌碌正文节点：{node_type or 'unknown'}")
        label = html.escape(node_type or "unknown")
        return f'<div class="unsupported-node">[暂不支持的内容：{label}]</div>'

    return "".join(render_node(node) for node in nodes if isinstance(node, dict))


def _chapter_groups(
    floor_index: list[dict],
    chapter_index: list[dict],
    floors: list[dict],
) -> list[tuple[str, list[tuple[dict, dict]]]]:
    floor_by_id = {floor.get("id"): floor for floor in floors if isinstance(floor, dict)}
    markers = {
        marker.get("floor"): str(marker.get("title") or "").strip()
        for marker in chapter_index
        if isinstance(marker, dict) and isinstance(marker.get("floor"), int)
    }
    ordered = []
    for item in floor_index:
        floor = floor_by_id.get(item.get("floorId"))
        if floor is None:
            raise GululuFormatError(f"缺少第 {item.get('floorNum', '?')} 楼正文")
        ordered.append((item, floor))
    if not ordered:
        raise GululuFormatError("骨碌碌书籍没有可导出的楼层")

    has_author_chapters = any(
        markers.get(item.get("floorNum")) for item, _floor in ordered
    )
    if not has_author_chapters:
        groups = []
        for start in range(0, len(ordered), FALLBACK_FLOORS_PER_CHAPTER):
            group = ordered[start:start + FALLBACK_FLOORS_PER_CHAPTER]
            first = group[0][0].get("floorNum")
            last = group[-1][0].get("floorNum")
            title = f"第 {first} 楼" if first == last else f"第 {first}~{last} 楼"
            groups.append((title, group))
        return groups

    groups: list[tuple[str, list[tuple[dict, dict]]]] = []
    current: list[tuple[dict, dict]] = []
    current_title = ""
    for item, floor in ordered:
        floor_num = item.get("floorNum")
        marker_title = markers.get(floor_num)
        if marker_title and current:
            groups.append((current_title, current))
            current = []
        if not current:
            current_title = marker_title or str(item.get("name") or f"第 {floor_num} 楼")
        current.append((item, floor))
    if current:
        groups.append((current_title, current))
    return groups


def _floor_html(
    index_item: dict,
    floor: dict,
    comments: list[dict],
    immersive: Optional[ImmersiveFloor] = None,
) -> str:
    floor_num = int(index_item.get("floorNum") or floor.get("floorNum") or 0)
    floor_id = int(index_item.get("floorId") or floor.get("id") or 0)
    title = html.escape(str(index_item.get("name") or floor.get("name") or ""))
    immersive = immersive or prepare_immersive_floor(floor.get("paragraphContents") or [])
    body = render_ast(immersive.nodes)
    effect_attr = (
        f' data-gululu-vfx="{html.escape(immersive.vfx, quote=True)}"'
        if immersive.vfx else ""
    )
    try:
        comment_html = render_comment_block(comments, label="评论")
    except ValueError as exc:
        raise GululuFormatError(f"第 {floor_num} 楼评论格式错误：{exc}") from exc
    return (
        f'<section class="gululu-floor" id="floor-{floor_id}"{effect_attr}>'
        '<header class="floor-head">'
        f'<span class="floor-number">第 {floor_num} 楼</span>'
        f'<span class="floor-title">{title}</span>'
        "</header>"
        f'<div class="floor-content">{body}</div>'
        f"{comment_html}"
        "</section>"
    )


def build_epub(
    *,
    detail: dict,
    floor_index: list[dict],
    chapter_index: list[dict],
    floors: list[dict],
    comments_by_floor: Optional[dict[int, list[dict]]] = None,
    output_path: str | Path,
    progress: Optional[Callable[[str, int, int, str], None]] = None,
    cancel: Optional[Callable[[], bool]] = None,
) -> Path:
    """把已获取的数据快照打包为现有 Windows 阅读器可导入的 EPUB3。"""
    from ebooklib import epub

    try:
        book_id = int(detail["bookId"])
        title = str(detail["name"]).strip()
    except (KeyError, TypeError, ValueError) as exc:
        raise GululuFormatError("骨碌碌书籍详情缺少 bookId 或 name") from exc
    if not title:
        raise GululuFormatError("骨碌碌书名为空")
    author_data = detail.get("author") if isinstance(detail.get("author"), dict) else {}
    author = str(author_data.get("nickName") or "").strip()
    groups = _chapter_groups(floor_index, chapter_index, floors)
    comments_by_floor = comments_by_floor or {}
    immersive_by_floor = {
        int(floor.get("id") or 0): prepare_immersive_floor(floor.get("paragraphContents") or [])
        for floor in floors
        if isinstance(floor, dict)
    }

    target = Path(output_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    book = epub.EpubBook()
    book.set_identifier(f"gululu-{book_id}")
    book.set_title(title)
    book.set_language("zh-CN")
    if author:
        book.add_author(author)
    book.add_metadata("DC", "source", f"{SITE_BASE}/book/{book_id}")
    description = str(detail.get("oneLineText") or "").strip()
    if description:
        book.add_metadata("DC", "description", description)

    style = epub.EpubItem(
        uid="style-main",
        file_name="style/main.css",
        media_type="text/css",
        content=GULULU_EPUB_CSS,
    )
    book.add_item(style)
    chapters = []
    total_groups = len(groups)
    active_background = ""
    for index, (chapter_title, chapter_floors) in enumerate(groups, 1):
        if cancel is not None and cancel():
            raise GululuCancelled("骨碌碌导入已取消")
        escaped_title = html.escape(chapter_title)
        source = html.escape(f"{SITE_BASE}/book/{book_id}")
        parts = [f'<h1 class="chapter-title">{escaped_title}</h1>']
        if index == 1:
            parts.append(
                f'<p class="book-meta">来源：<a href="{source}">骨碌碌</a>'
                f" · 作者：{html.escape(author or '未知')}</p>"
            )
            try:
                parts.append(
                    render_comment_block(
                        comments_by_floor.get(0, []),
                        label="作品评论",
                        opus=True,
                    )
                )
            except ValueError as exc:
                raise GululuFormatError(f"作品评论格式错误：{exc}") from exc
        chapter_background = active_background
        if chapter_background:
            parts.append(
                '<span class="gululu-immersive-marker" '
                f'data-gululu-background-initial="{html.escape(chapter_background, quote=True)}" '
                'aria-hidden="true"></span>'
            )
        for item, floor in chapter_floors:
            floor_id = int(item.get("floorId") or 0)
            immersive = immersive_by_floor.get(floor_id, ImmersiveFloor([]))
            parts.append(_floor_html(
                item,
                floor,
                comments_by_floor.get(floor_id, []),
                immersive,
            ))
            if immersive.background_update is not None:
                active_background = immersive.background_update
        chapter = epub.EpubHtml(
            title=chapter_title,
            file_name=f"chapters/chapter_{index:04d}.xhtml",
            lang="zh-CN",
        )
        chapter.content = (
            '<html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN">'
            f"<head><title>{escaped_title}</title></head>"
            f"<body>{''.join(parts)}</body></html>"
        )
        chapter.add_link(href="../style/main.css", rel="stylesheet", type="text/css")
        book.add_item(chapter)
        chapters.append(chapter)
        if progress is not None:
            progress("epub", index, total_groups, f"正在生成章节 {index}/{total_groups}")

    book.toc = chapters
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = chapters
    if cancel is not None and cancel():
        raise GululuCancelled("骨碌碌导入已取消")
    try:
        epub.write_epub(str(target), book)
    except (OSError, ValueError) as exc:
        raise GululuFormatError(f"写入 EPUB 失败：{exc}") from exc
    return target


def download_epub(source: str | int, output_path: str | Path) -> Path:
    """从公开书籍链接/ID 下载快照并生成在线图片模式 EPUB。"""
    book_id = parse_book_id(source)
    with GululuClient() as client:
        snapshot = client.fetch_snapshot(book_id)
    return build_epub(
        detail=snapshot.detail,
        floor_index=snapshot.floor_index,
        chapter_index=snapshot.chapter_index,
        floors=snapshot.floors,
        comments_by_floor=snapshot.comments_by_floor,
        output_path=output_path,
    )


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="把骨碌碌公开安科转换为 AnkeShelf 可导入的 EPUB")
    parser.add_argument("source", help="书籍 ID 或 https://www.gululu.world/book/<id>")
    parser.add_argument("-o", "--output", help="输出 EPUB 路径（默认 gululu-<id>.epub）")
    args = parser.parse_args(argv)
    try:
        book_id = parse_book_id(args.source)
        output = Path(args.output) if args.output else Path.cwd() / f"gululu-{book_id}.epub"
        result = download_epub(book_id, output)
    except (GululuError, ValueError) as exc:
        parser.error(str(exc))
    print(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
