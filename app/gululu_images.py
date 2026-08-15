"""Gululu body-image modes and embedded EPUB resource preparation."""
from __future__ import annotations

import hashlib
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable, Iterable, Optional

import httpx


IMAGE_MODES = frozenset({"online", "embedded", "none"})
_MAX_IMAGE_BYTES = 25 * 1024 * 1024
_MAX_WORKERS = 6

ImageFetcher = Callable[[str], tuple[bytes, str]]
ImageProgress = Callable[[int, int, int, int], None]


class GululuImageCancelled(Exception):
    """Image preparation was cancelled by the owning import task."""


@dataclass(frozen=True)
class EmbeddedImage:
    source_url: str
    file_name: str
    media_type: str
    content: bytes


@dataclass(frozen=True)
class ImageFailure:
    source_url: str
    error: str


@dataclass(frozen=True)
class ImageBatch:
    resources: tuple[EmbeddedImage, ...]
    failures: tuple[ImageFailure, ...]


def normalize_image_mode(value: object) -> str:
    mode = str(value or "online").strip().lower()
    if mode not in IMAGE_MODES:
        raise ValueError("骨碌碌图片模式必须是 online、embedded 或 none")
    return mode


def collect_image_urls(floors: Iterable[dict]) -> list[str]:
    """Collect unique HTTPS body-image URLs in source order."""
    urls: list[str] = []
    seen: set[str] = set()

    def visit(value: object) -> None:
        if isinstance(value, list):
            for item in value:
                visit(item)
            return
        if not isinstance(value, dict):
            return
        if str(value.get("type") or "") == "image":
            attrs = value.get("attrs") if isinstance(value.get("attrs"), dict) else {}
            source = str(attrs.get("src") or "").strip()
            if source.startswith("https://") and source not in seen:
                seen.add(source)
                urls.append(source)
        visit(value.get("content"))

    for floor in floors:
        if isinstance(floor, dict):
            visit(floor.get("paragraphContents"))
    return urls


def prepare_embedded_images(
    urls: Iterable[str],
    *,
    fetcher: Optional[ImageFetcher] = None,
    progress: Optional[ImageProgress] = None,
    cancel: Optional[Callable[[], bool]] = None,
) -> ImageBatch:
    sources = list(dict.fromkeys(urls))
    if not sources:
        return ImageBatch((), ())

    client = None
    if fetcher is None:
        client = httpx.Client(
            timeout=30.0,
            follow_redirects=True,
            headers={"Referer": "https://www.gululu.world/"},
        )

        def fetch(source: str) -> tuple[bytes, str]:
            assert client is not None
            with client.stream("GET", source) as response:
                response.raise_for_status()
                if response.url.scheme != "https":
                    raise ValueError("图片重定向到非 HTTPS 地址")
                declared_size = response.headers.get("content-length")
                if declared_size and int(declared_size) > _MAX_IMAGE_BYTES:
                    raise ValueError("图片超过 25 MB 限制")
                chunks = []
                size = 0
                for chunk in response.iter_bytes():
                    size += len(chunk)
                    if size > _MAX_IMAGE_BYTES:
                        raise ValueError("图片超过 25 MB 限制")
                    chunks.append(chunk)
                return b"".join(chunks), response.headers.get("content-type", "")
    else:
        fetch = fetcher

    def download(source: str) -> EmbeddedImage | ImageFailure:
        try:
            content, content_type = fetch(source)
            media_type, extension = _detect_image(content, content_type)
            digest = hashlib.sha256(source.encode("utf-8")).hexdigest()[:16]
            return EmbeddedImage(
                source_url=source,
                file_name=f"images/{digest}.{extension}",
                media_type=media_type,
                content=content,
            )
        except Exception as exc:  # noqa: BLE001 - converted to an explicit per-image result
            return ImageFailure(source, str(exc) or exc.__class__.__name__)

    resources: list[EmbeddedImage] = []
    failures: list[ImageFailure] = []
    pool = ThreadPoolExecutor(max_workers=_MAX_WORKERS, thread_name_prefix="gululu-image")
    futures = [pool.submit(download, source) for source in sources]
    try:
        for index, future in enumerate(futures, 1):
            if cancel is not None and cancel():
                for pending in futures:
                    pending.cancel()
                raise GululuImageCancelled("骨碌碌图片内嵌已取消")
            item = future.result()
            if isinstance(item, EmbeddedImage):
                resources.append(item)
            else:
                failures.append(item)
            if progress is not None:
                progress(index, len(sources), len(resources), len(failures))
    finally:
        pool.shutdown(wait=True, cancel_futures=True)
        if client is not None:
            client.close()
    return ImageBatch(tuple(resources), tuple(failures))


def _detect_image(content: bytes, content_type: str) -> tuple[str, str]:
    del content_type  # File signatures are authoritative; HTTP labels are not trusted.
    if content.startswith(b"\xff\xd8\xff"):
        return "image/jpeg", "jpg"
    if content.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png", "png"
    if content.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif", "gif"
    if len(content) >= 12 and content[:4] == b"RIFF" and content[8:12] == b"WEBP":
        return "image/webp", "webp"
    if len(content) >= 12 and content[4:8] == b"ftyp" and content[8:12] in {b"avif", b"avis"}:
        return "image/avif", "avif"
    raise ValueError("响应不是受支持的 JPEG、PNG、GIF、WebP 或 AVIF 图片")
