"""正式 Windows 阅读器的骨碌碌在线评论调试服务器。"""
from __future__ import annotations

import argparse
import json
import threading
from pathlib import Path

from app.annotations import AnnotationStore
from app.api import Api
from app.book_manager import BookManager
from app.gululu_comments import comment_to_public
from app.gululu_epub import build_epub
from app.paths import web_dir
from app.search import SearchService
from app.server import EpubHandler, start_server
from app.settings import Settings
from app.shelf import BookRecord, ProgressStore, Shelf
from app.stats import StatsStore


DEBUG_ROOT = Path(__file__).resolve().parent
WORKSPACE = DEBUG_ROOT / "workspace"
FIXTURES = DEBUG_ROOT.parent / "fixtures" / "gululu"
TOKEN = "gululu-formal-debug"


def _fixture(name: str):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _compact_epub() -> Path:
    target = WORKSPACE / "formal-compact.epub"
    floors = _fixture("floors.json")["data"]
    first_floor = floors[0]["paragraphContents"]
    first_floor[1:2] = [
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": "<背景>", "attrs": None, "marks": [], "content": []},
        ]},
        first_floor[1],
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": "</背景>", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": "<音乐>雨夜 ♪https://media.example/rain.mp3</音乐结束>", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": "<特效:下雨>", "attrs": None, "marks": [], "content": []},
        ]},
    ]
    build_epub(
        detail=_fixture("detail.json")["data"],
        floor_index=_fixture("floor_index.json")["data"],
        chapter_index=_fixture("chapter_index.json")["data"]["chapterIndex"],
        floors=floors,
        comments_by_floor={},
        output_path=target,
    )
    return target


class _FakeGululuService:
    def __init__(self) -> None:
        floor_comments = _fixture("comments_floor_962170.json")["data"]["records"]
        floor_comments[0]["childrenComment"] = _fixture(
            "comments_children_9001.json"
        )["data"]["records"]
        self._comments = {
            0: [
                comment_to_public(item)
                for item in _fixture("comments_opus.json")["data"]["records"]
            ],
            962170: [comment_to_public(item) for item in floor_comments],
        }

    def get_comments(self, source_id, floor_ids, refresh=False):
        return {
            "ok": True,
            "source_id": source_id,
            "floors": [
                {
                    "floor_id": floor_id,
                    "comments": self._comments.get(floor_id, []),
                    "cached": not refresh,
                    "stale": False,
                    "fetched_at": "2026-08-15T00:00:00+00:00",
                    "error": "",
                }
                for floor_id in floor_ids
            ],
            "error": "",
        }

    def status(self):
        return {"running": False, "stage": "idle", "detail": ""}


def create_app() -> tuple[Api, BookManager, Path]:
    WORKSPACE.mkdir(parents=True, exist_ok=True)
    data = WORKSPACE / "formal-data"
    covers = data / "covers"
    covers.mkdir(parents=True, exist_ok=True)
    books = BookManager()
    book = books.register(str(_compact_epub()))
    shelf = Shelf(data / "shelf.json", covers)
    shelf.load()
    shelf.upsert(BookRecord(
        id=book.id,
        path=book.path,
        title=book.title,
        author=book.author,
        language=book.language,
        chapter_count=len(book.chapters),
    ))
    shelf.save()
    progress = ProgressStore(data / "progress.json")
    progress.load()
    settings = Settings(data / "settings.json")
    settings.load()
    annotations = AnnotationStore(data / "annotations.json")
    annotations.load()
    stats = StatsStore(data / "statistics.json")
    stats.load()
    api = Api(
        books=books,
        shelf=shelf,
        progress=progress,
        settings=settings,
        search=SearchService(),
        annotations=annotations,
        stats=stats,
        gululu_service=_FakeGululuService(),
        frontend_ready=threading.Event(),
    )
    return api, books, covers


def main() -> int:
    parser = argparse.ArgumentParser(description="启动正式阅读器骨碌碌评论调试服务")
    parser.add_argument("--port", type=int, default=8878, help="本地端口（默认 8878）")
    args = parser.parse_args()
    api, books, covers = create_app()
    EpubHandler.log_message = lambda *unused: None
    port = start_server(web_dir(), books, covers, api=api, token=TOKEN, port=args.port)
    print(f"http://127.0.0.1:{port}/index.html?token={TOKEN}", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        pass
    finally:
        books.close_all()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
