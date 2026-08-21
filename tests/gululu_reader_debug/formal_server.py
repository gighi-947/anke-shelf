"""正式 Windows 阅读器的骨碌碌在线评论调试服务器。"""
from __future__ import annotations

import argparse
import json
import threading
from pathlib import Path

from app.annotations import AnnotationStore
from app.export_service import ExportService
from app.nga_login import NgaLoginController
from app.nga_service import NgaService
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
CRYPTOJS_CIPHER = (
    "U2FsdGVkX1+5H7Gx48HorblxhULBPlXtE11y6qTOMa4caaekW4/fZFQlbBlH2/p8"
)


def _fixture(name: str):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _compact_epub() -> Path:
    target = WORKSPACE / "66905" / "post.epub"
    target.parent.mkdir(parents=True, exist_ok=True)
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
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": f"<秘密>[炉心]{CRYPTOJS_CIPHER}</秘密>", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": "<发现秘密>[炉心]薪火-63299</发现秘密>", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": '<引用 id="66905" floor="3">', "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": "跳到第一章第三楼", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {}, "content": [
            {"type": "text", "text": "</引用>", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {"id": "dice-regression"}, "content": [
            {"type": "text", "text": "判定：【1D20+4=18】大成功", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {"id": "fog-regression"}, "content": [
            {"type": "text", "text": "迷雾后的正文", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {"id": "dice-batch-regression"}, "content": [
            {"type": "text", "text": "第二次判定：1D6=4", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {"id": "fog-batch-regression"}, "content": [
            {"type": "text", "text": "第二层迷雾后的正文", "attrs": None, "marks": [], "content": []},
        ]},
        {"type": "paragraph", "attrs": {"id": "overflow-regression"}, "content": [
            {"type": "text", "text": "1-2 自己回去休息", "marks": [
                {"type": "textStyle", "attrs": {"color": "rgb(0, 0, 0)"}},
            ]},
            {"type": "hardBreak"},
            {"type": "text", "text": "3-4 自己回去休息，同时让小伞也回去休息（好感+d3）", "marks": [
                {"type": "textStyle", "attrs": {"color": "rgb(0, 0, 0)"}},
            ]},
            {"type": "hardBreak"},
            {"type": "text", "text": "9 不管小伞，自己在外面修炼战斗（旧伤-d2）", "marks": [
                {"type": "textStyle", "attrs": {"color": "rgb(0, 0, 0)"}},
            ]},
        ]},
    ]
    floors[1]["paragraphContents"].extend(
        {
            "type": "paragraph",
            "attrs": {"id": f"paged-fullscreen-regression-{index}"},
            "content": [{
                "type": "text",
                "text": f"分页沉浸式进度回归段落 {index}：窗口尺寸变化后仍应停留在当前阅读位置。",
                "marks": [],
            }],
        }
        for index in range(1, 81)
    )
    build_epub(
        detail=_fixture("detail.json")["data"],
        floor_index=_fixture("floor_index.json")["data"],
        chapter_index=_fixture("chapter_index.json")["data"]["chapterIndex"],
        floors=floors,
        comments_by_floor={},
        output_path=target,
    )
    return target


def _nga_isolation_epub() -> Path:
    from ebooklib import epub

    target = WORKSPACE / "nga-isolation.epub"
    book = epub.EpubBook()
    book.set_identifier("nga-isolation-fixture")
    book.set_title("NGA 隔离测试")
    book.set_language("zh-CN")
    chapter = epub.EpubHtml(title="隔离章", file_name="chapter.xhtml", lang="zh-CN")
    chapter.content = (
        '<html><head><title>隔离章</title></head><body>'
        '<h1>NGA 隔离测试</h1>'
        '<section class="gululu-floor" id="floor-999">'
        '<header class="floor-head"><span class="floor-number">第 1 楼</span></header>'
        '<button class="gululu-music-cue" data-gululu-music-url="https://media.example/leak.mp3">'
        '<span class="gululu-music-title">不应播放</span></button>'
        '<button class="gululu-secret-cue" data-secret-title="不应打开">不应打开</button>'
        '</section></body></html>'
    )
    book.add_item(chapter)
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [chapter]
    epub.write_epub(str(target), book)
    return target


class _FakeGululuService:
    def __init__(self) -> None:
        floor_comments = _fixture("comments_floor_962170.json")["data"]["records"]
        floor_comments[0]["childrenComment"] = _fixture(
            "comments_children_9001.json"
        )["data"]["records"]
        second_floor_comment = comment_to_public(floor_comments[0])
        second_floor_comment.update({"id": 9102, "content": "第一章第二楼评论", "paragraph_id": ""})
        third_floor_comment = comment_to_public(floor_comments[0])
        third_floor_comment.update({"id": 9103, "content": "第一章第三楼评论", "paragraph_id": ""})
        self._comments = {
            0: [
                comment_to_public(item)
                for item in _fixture("comments_opus.json")["data"]["records"]
            ],
            962170: [comment_to_public(item) for item in floor_comments],
            962916: [second_floor_comment],
            963517: [third_floor_comment],
        }
        # 段落级评论测试：把 962170 楼第一条评论挂到测试书实际段落 dice-regression 上，
        # 使正文段落徽标 / 面板段落分组 / 双向跳转可端到端断言。
        for comment in self._comments[962170]:
            if comment.get("paragraph_id"):
                comment["paragraph_id"] = "dice-regression"
                break
        # 63299 参考书（--book）的段落评论：挂到首章 909377 楼段落 77733141 上。
        real_floor_comment = comment_to_public(floor_comments[0])
        real_floor_comment.update({
            "id": 9201,
            "content": "63299 首章段落评论（77733141）",
            "paragraph_id": "77733141",
        })
        self._comments[909377] = [real_floor_comment]

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


def create_app(extra_epub: str | None = None) -> tuple[Api, BookManager, Path]:
    WORKSPACE.mkdir(parents=True, exist_ok=True)
    data = WORKSPACE / "formal-data"
    covers = data / "covers"
    covers.mkdir(parents=True, exist_ok=True)
    books = BookManager()
    book = books.register(str(_compact_epub()))
    nga_book = books.register(str(_nga_isolation_epub()))
    shelf = Shelf(data / "shelf.json", covers)
    shelf.upsert(BookRecord(
        id=book.id,
        path=book.path,
        title=book.title,
        author=book.author,
        language=book.language,
        chapter_count=len(book.chapters),
    ))
    shelf.upsert(BookRecord(
        id=nga_book.id,
        path=nga_book.path,
        title=nga_book.title,
        author=nga_book.author,
        language=nga_book.language,
        chapter_count=len(nga_book.chapters),
        nga_tid=999,
    ))
    if extra_epub:
        extra_path = Path(extra_epub).resolve()
        if extra_path.exists():
            extra = books.register(str(extra_path))
            shelf.upsert(BookRecord(
                id=extra.id,
                path=extra.path,
                title=extra.title,
                author=extra.author,
                language=extra.language,
                chapter_count=len(extra.chapters),
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
        nga_service=NgaService(lambda _path: ""),
        export_service=ExportService(shelf),
        gululu_service=_FakeGululuService(),
        frontend_ready=threading.Event(),
        window_toggle=lambda _entering: None,
        nga_login=NgaLoginController(),
    )
    return api, books, covers


def main() -> int:
    parser = argparse.ArgumentParser(description="启动正式阅读器骨碌碌评论调试服务")
    parser.add_argument("--port", type=int, default=8878, help="本地端口（默认 8878）")
    parser.add_argument("--book", type=str, default=None, help="额外注册的 EPUB 路径（如真实参考书 63299）")
    args = parser.parse_args()
    api, books, covers = create_app(args.book)
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
