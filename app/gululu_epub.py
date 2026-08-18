"""骨碌碌公开阅读接口到标准 EPUB3 的 Windows 端转换器。"""
from __future__ import annotations

import argparse
import html
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from .gululu_ast import render_ast
from .gululu_assistant import prepare_reader_experience_nodes
from .gululu_comments import (
    render_comment_block,
)
from .gululu_client import (
    SITE_BASE,
    GululuApiError,
    GululuCancelled,
    GululuClient,
    GululuError,
    GululuFormatError,
    GululuIndex,
    GululuSnapshot,
)
from .gululu_epub_styles import GULULU_EPUB_CSS
from .gululu_immersive import (
    ImmersiveFloor,
    prepare_immersive_floor,
)
from .gululu_images import (
    GululuImageCancelled,
    ImageBatch,
    ImageFetcher,
    collect_image_urls,
    normalize_image_mode,
    prepare_embedded_images,
)
from .gululu_source import extract_book_id, parse_book_id, parse_gululu_identifier


FALLBACK_FLOORS_PER_CHAPTER = 20


@dataclass(frozen=True)
class GululuBuildResult:
    path: Path
    image_mode: str
    image_total: int
    image_embedded: int
    image_failures: tuple[str, ...] = ()


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
    image_resolver: Optional[Callable[[str], str]] = None,
    jump_floor_resolver: Optional[Callable[[int], str]] = None,
    source_book_id: int = 0,
) -> str:
    floor_num = int(index_item.get("floorNum") or floor.get("floorNum") or 0)
    floor_id = int(index_item.get("floorId") or floor.get("id") or 0)
    title = html.escape(str(index_item.get("name") or floor.get("name") or ""))
    immersive = immersive or prepare_immersive_floor(floor.get("paragraphContents") or [])
    body = render_ast(
        prepare_reader_experience_nodes(immersive.nodes, floor_id),
        image_resolver=image_resolver,
        jump_floor_resolver=jump_floor_resolver,
        source_book_id=source_book_id,
    )
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
    image_mode: str = "online",
    image_fetcher: Optional[ImageFetcher] = None,
    progress: Optional[Callable[[str, int, int, str], None]] = None,
    cancel: Optional[Callable[[], bool]] = None,
) -> GululuBuildResult:
    """把已获取的数据快照打包为现有 Windows 阅读器可导入的 EPUB3。"""
    from ebooklib import epub

    try:
        book_id = int(detail["bookId"])
        title = str(detail["name"]).strip()
    except (KeyError, TypeError, ValueError) as exc:
        raise GululuFormatError("骨碌碌书籍详情缺少 bookId 或 name") from exc
    if not title:
        raise GululuFormatError("骨碌碌书名为空")
    try:
        normalized_image_mode = normalize_image_mode(image_mode)
    except ValueError as exc:
        raise GululuFormatError(str(exc)) from exc
    author_data = detail.get("author") if isinstance(detail.get("author"), dict) else {}
    author = str(author_data.get("nickName") or "").strip()
    groups = _chapter_groups(floor_index, chapter_index, floors)
    floor_targets: dict[int, str] = {}
    for chapter_number, (_, chapter_floors) in enumerate(groups, 1):
        for index_item, floor in chapter_floors:
            floor_number = int(index_item.get("floorNum") or floor.get("floorNum") or 0)
            floor_id = int(index_item.get("floorId") or floor.get("id") or 0)
            if floor_number > 0 and floor_id > 0:
                floor_targets[floor_number] = (
                    f"chapter_{chapter_number:04d}.xhtml#floor-{floor_id}"
                )
    comments_by_floor = comments_by_floor or {}
    immersive_by_floor = {
        int(floor.get("id") or 0): prepare_immersive_floor(floor.get("paragraphContents") or [])
        for floor in floors
        if isinstance(floor, dict)
    }
    image_urls = collect_image_urls(floors)
    image_batch = ImageBatch((), ())
    if normalized_image_mode == "embedded":
        def on_image_progress(current: int, total: int, ok: int, failed: int) -> None:
            if progress is not None:
                progress(
                    "images",
                    current,
                    total,
                    f"正在内嵌图片 {current}/{total}（成功 {ok}，失败 {failed}）",
                )

        try:
            image_batch = prepare_embedded_images(
                image_urls,
                fetcher=image_fetcher,
                progress=on_image_progress,
                cancel=cancel,
            )
        except GululuImageCancelled as exc:
            raise GululuCancelled(str(exc)) from exc
    embedded_sources = {
        item.source_url: f"../{item.file_name}"
        for item in image_batch.resources
    }
    if normalized_image_mode == "online":
        image_resolver = lambda url: url
    elif normalized_image_mode == "none":
        image_resolver = lambda url: ""
    else:
        image_resolver = embedded_sources.get

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
    for resource in image_batch.resources:
        uid = "gululu-image-" + Path(resource.file_name).stem
        book.add_item(epub.EpubImage(
            uid=uid,
            file_name=resource.file_name,
            media_type=resource.media_type,
            content=resource.content,
        ))
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
                'aria-hidden="true"><wbr/></span>'
            )
        for item, floor in chapter_floors:
            floor_id = int(item.get("floorId") or 0)
            immersive = immersive_by_floor.get(floor_id, ImmersiveFloor([]))
            parts.append(_floor_html(
                item,
                floor,
                comments_by_floor.get(floor_id, []),
                immersive,
                image_resolver,
                floor_targets.get,
                book_id,
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
    return GululuBuildResult(
        path=target,
        image_mode=normalized_image_mode,
        image_total=len(image_urls),
        image_embedded=len(image_batch.resources),
        image_failures=tuple(
            f"{item.source_url}: {item.error}"
            for item in image_batch.failures
        ),
    )


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
    ).path


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
