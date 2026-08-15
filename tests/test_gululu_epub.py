"""骨碌碌公开 API 到标准 EPUB 的转换回归测试。"""
import json
import posixpath
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

import httpx

from app.epub import EpubBook
from app.gululu_epub import (
    GululuApiError,
    GululuClient,
    GululuFormatError,
    build_epub,
    parse_book_id,
    parse_gululu_identifier,
    render_ast,
)
from app.gululu_immersive import prepare_immersive_floor, safe_https_url


FIXTURE_DIR = Path(__file__).parent / "fixtures" / "gululu"


def _fixture(name: str):
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


class TestParseBookId(unittest.TestCase):
    def test_accepts_id_and_public_book_url(self):
        self.assertEqual(parse_book_id("66905"), 66905)
        self.assertEqual(parse_book_id("https://www.gululu.world/book/66905"), 66905)

    def test_rejects_other_hosts_and_paths(self):
        with self.assertRaises(ValueError):
            parse_book_id("https://example.com/book/66905")
        with self.assertRaises(ValueError):
            parse_book_id("https://www.gululu.world/user/66905")

    def test_parses_only_gululu_epub_identifiers(self):
        self.assertEqual(parse_gululu_identifier("gululu-66905"), 66905)
        self.assertIsNone(parse_gululu_identifier("978-7-0000-0000-0"))
        self.assertIsNone(parse_gululu_identifier("gululu-0"))


class TestGululuClient(unittest.TestCase):
    def test_fetch_snapshot_uses_public_reader_contract(self):
        responses = {
            ("GET", "/reader/opus/detail/66905"): _fixture("detail.json"),
            ("GET", "/reader/floor/index-list/66905"): _fixture("floor_index.json"),
            ("GET", "/reader/opus/chapter-index"): _fixture("chapter_index.json"),
            ("POST", "/reader/floor/content-by-ids"): _fixture("floors.json"),
        }
        seen_batches = []
        progress = []

        def handler(request: httpx.Request) -> httpx.Response:
            self.assertEqual(request.headers.get("platform"), "1")
            if request.method == "GET" and request.url.path.endswith("chapter-index"):
                self.assertEqual(request.url.params.get("opusId"), "66905")
            if request.url.path.endswith("/comment/page"):
                fixture = (
                    "comments_floor_962170.json"
                    if request.url.params.get("floorId") == "962170"
                    else "comments_opus.json"
                )
                return httpx.Response(200, json=_fixture(fixture))
            if request.url.path.endswith("/comment/page-children"):
                self.assertEqual(request.url.params.get("parentId"), "9001")
                return httpx.Response(200, json=_fixture("comments_children_9001.json"))
            if request.method == "POST":
                batch = json.loads(request.content)
                seen_batches.append(batch)
                payload = _fixture("floors.json")
                payload["data"] = [item for item in payload["data"] if item["id"] in batch]
                return httpx.Response(200, json=payload)
            return httpx.Response(200, json=responses[(request.method, request.url.path)])

        http = httpx.Client(
            base_url="https://backend.gululu.world",
            transport=httpx.MockTransport(handler),
        )
        with GululuClient(http=http, floor_batch_size=2) as client:
            snapshot = client.fetch_snapshot(
                66905,
                progress=lambda stage, current, total, detail: progress.append(
                    (stage, current, total, detail)
                ),
            )

        self.assertEqual(snapshot.detail["name"], "测试安科")
        self.assertEqual([f["floorNum"] for f in snapshot.floor_index], [1, 2, 3, 4])
        self.assertEqual(snapshot.chapter_index[0], {"floor": 2, "title": "第一章"})
        self.assertEqual([f["id"] for f in snapshot.floors], [962170, 962916, 963517, 964087])
        self.assertEqual(seen_batches, [[962170, 962916], [963517, 964087]])
        self.assertEqual(snapshot.comments_by_floor[0][0]["content"], "作品评论")
        floor_comment = snapshot.comments_by_floor[962170][0]
        self.assertEqual(floor_comment["content"], "段落评论")
        self.assertEqual(floor_comment["childrenComment"][0]["content"], "回复内容")
        self.assertIn(("floors", 2, 4, "正在获取楼层 2/4"), progress)
        self.assertIn(("floors", 4, 4, "正在获取楼层 4/4"), progress)
        self.assertIn(("comments", 2, 2, "正在获取评论 2/2"), progress)

    def test_snapshot_can_skip_comments_for_compact_import(self):
        requested_comments = []

        def handler(request: httpx.Request) -> httpx.Response:
            if "/comment/" in request.url.path:
                requested_comments.append(request.url.path)
                return httpx.Response(500)
            if request.url.path.endswith("/detail/66905"):
                return httpx.Response(200, json=_fixture("detail.json"))
            if request.url.path.endswith("/index-list/66905"):
                return httpx.Response(200, json=_fixture("floor_index.json"))
            if request.url.path.endswith("/chapter-index"):
                return httpx.Response(200, json=_fixture("chapter_index.json"))
            payload = _fixture("floors.json")
            batch = json.loads(request.content)
            payload["data"] = [item for item in payload["data"] if item["id"] in batch]
            return httpx.Response(200, json=payload)

        http = httpx.Client(
            base_url="https://backend.gululu.world",
            transport=httpx.MockTransport(handler),
        )
        with GululuClient(http=http) as client:
            snapshot = client.fetch_snapshot(66905, include_comments=False)

        self.assertEqual(snapshot.comments_by_floor, {})
        self.assertEqual(requested_comments, [])

    def test_snapshot_treats_null_chapter_data_as_no_author_chapters(self):
        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/detail/66905"):
                return httpx.Response(200, json=_fixture("detail.json"))
            if request.url.path.endswith("/index-list/66905"):
                return httpx.Response(200, json=_fixture("floor_index.json"))
            if request.url.path.endswith("/chapter-index"):
                return httpx.Response(
                    200,
                    json={"code": 200, "data": None, "msg": "查询成功"},
                )
            payload = _fixture("floors.json")
            batch = json.loads(request.content)
            payload["data"] = [item for item in payload["data"] if item["id"] in batch]
            return httpx.Response(200, json=payload)

        http = httpx.Client(
            base_url="https://backend.gululu.world",
            transport=httpx.MockTransport(handler),
        )
        with GululuClient(http=http) as client:
            snapshot = client.fetch_snapshot(66905, include_comments=False)

        self.assertEqual(snapshot.chapter_index, [])

    def test_api_business_failure_is_explicit(self):
        http = httpx.Client(
            base_url="https://backend.gululu.world",
            transport=httpx.MockTransport(
                lambda request: httpx.Response(200, json={"code": 500, "data": None, "msg": "失败"})
            ),
        )
        with GululuClient(http=http) as client:
            with self.assertRaisesRegex(GululuApiError, "失败"):
                client.fetch_snapshot(66905)


class TestGululuAstRenderer(unittest.TestCase):
    def test_recursive_semantic_rendering(self):
        floors = _fixture("floors.json")["data"]
        html = render_ast(floors[0]["paragraphContents"] + floors[1]["paragraphContents"])
        self.assertIn('<p data-paragraph-id="p1">普通文字<br/>', html)
        self.assertIn('<span style="color:rgb(221, 0, 0)"><strong>红色粗体</strong></span>', html)
        self.assertIn('<img src="https://image.gululu.world/test/a.webp" alt="测试图"/>', html)
        self.assertIn("<h3><em>幕间</em></h3>", html)
        self.assertIn('<details class="gululu-fold" open="open">', html)
        self.assertIn("<del>折叠内容</del>", html)

    def test_unknown_node_gets_visible_placeholder_or_strict_error(self):
        nodes = [{"type": "mysteryWidget", "attrs": {}, "content": []}]
        self.assertIn("暂不支持的内容：mysteryWidget", render_ast(nodes))
        with self.assertRaises(GululuFormatError):
            render_ast(nodes, strict=True)

    def test_unsafe_color_is_not_emitted(self):
        nodes = [{
            "type": "paragraph",
            "attrs": {},
            "content": [{
                "type": "text",
                "text": "安全文本",
                "marks": [{"type": "textStyle", "attrs": {"color": "red;background:url(x)"}}],
            }],
        }]
        html = render_ast(nodes)
        self.assertIn("安全文本", html)
        self.assertNotIn("background", html)

    def test_immersive_directives_become_safe_semantic_markers(self):
        nodes = [
            {"type": "paragraph", "content": [{"type": "text", "text": "<自动音乐>雨夜 ♪https://media.example/rain.mp3</自动音乐结束>"}]},
            {"type": "paragraph", "content": [{"type": "text", "text": "<特效:下雨>"}]},
            {"type": "paragraph", "content": [{"type": "text", "text": "<背景>"}]},
            {"type": "image", "attrs": {"src": "https://image.example/street.webp"}},
            {"type": "paragraph", "content": [{"type": "text", "text": "</背景>"}]},
            {"type": "paragraph", "content": [{"type": "text", "text": "<移除背景>"}]},
            {"type": "paragraph", "content": [{"type": "text", "text": "<停止音乐>"}]},
        ]

        prepared = prepare_immersive_floor(nodes)
        rendered = render_ast(prepared.nodes)

        self.assertEqual(prepared.vfx, "rain")
        self.assertEqual(prepared.background_update, "")
        self.assertIn('data-gululu-music-url="https://media.example/rain.mp3"', rendered)
        self.assertIn('data-gululu-music-auto="true"', rendered)
        self.assertIn('data-gululu-background-url="https://image.example/street.webp"', rendered)
        self.assertIn('data-gululu-background-clear="true"', rendered)
        self.assertIn('data-gululu-music-stop="true"', rendered)
        self.assertNotIn("&lt;特效", rendered)

    def test_immersive_external_urls_require_credential_free_https(self):
        self.assertEqual(safe_https_url("https://media.example/a.mp3"), "https://media.example/a.mp3")
        self.assertEqual(safe_https_url("http://media.example/a.mp3"), "")
        self.assertEqual(safe_https_url("https://user:pass@media.example/a.mp3"), "")

        prepared = prepare_immersive_floor([{
            "type": "paragraph",
            "content": [{"type": "text", "text": "<音乐>危险 ♪javascript:alert(1)</音乐结束>"}],
        }])
        rendered = render_ast(prepared.nodes)
        self.assertIn("音乐链接不可用", rendered)
        self.assertNotIn("javascript:", rendered)


class TestBuildGululuEpub(unittest.TestCase):
    def test_empty_author_chapters_fall_back_to_nga_floor_groups(self):
        floor_index = [
            {"floorId": number, "floorNum": number, "name": f"文稿 {number}"}
            for number in range(1, 43)
        ]
        floors = [
            {
                "id": number,
                "floorNum": number,
                "name": f"文稿 {number}",
                "paragraphContents": [{
                    "type": "paragraph",
                    "attrs": {},
                    "content": [{"type": "text", "text": f"正文 {number}"}],
                }],
            }
            for number in range(1, 43)
        ]
        detail = {
            "bookId": 32203,
            "name": "无作者章节测试",
            "author": {"nickName": "测试作者"},
        }

        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "fallback.epub"
            build_epub(
                detail=detail,
                floor_index=floor_index,
                chapter_index=[],
                floors=floors,
                output_path=target,
            )
            book = EpubBook(str(target)).open()
            try:
                self.assertEqual(len(book.chapters), 3)
                self.assertEqual(
                    [entry.label for entry in book.toc],
                    ["第 1~20 楼", "第 21~40 楼", "第 41~42 楼"],
                )
                self.assertIn("正文 1", book.chapter_text(0))
                self.assertIn("正文 20", book.chapter_text(0))
                self.assertNotIn("正文 21", book.chapter_text(0))
                self.assertIn("正文 21", book.chapter_text(1))
                self.assertNotIn("正文 41", book.chapter_text(1))
            finally:
                book.close()

    def test_build_carries_background_state_and_floor_effects_between_chapters(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "immersive.epub"
            floors = _fixture("floors.json")["data"]
            floors[0]["paragraphContents"].extend([
                {"type": "paragraph", "content": [{"type": "text", "text": "<背景>"}]},
                {"type": "image", "attrs": {"src": "https://image.example/scene.webp"}},
                {"type": "paragraph", "content": [{"type": "text", "text": "</背景>"}]},
                {"type": "paragraph", "content": [{"type": "text", "text": "<特效:下雪>"}]},
            ])
            build_epub(
                detail=_fixture("detail.json")["data"],
                floor_index=_fixture("floor_index.json")["data"],
                chapter_index=_fixture("chapter_index.json")["data"]["chapterIndex"],
                floors=floors,
                output_path=target,
            )

            with zipfile.ZipFile(target) as zf:
                first = zf.read("EPUB/chapters/chapter_0001.xhtml").decode("utf-8")
                second = zf.read("EPUB/chapters/chapter_0002.xhtml").decode("utf-8")
            self.assertIn('data-gululu-vfx="snow"', first)
            self.assertIn('data-gululu-background-url="https://image.example/scene.webp"', first)
            self.assertIn(
                'data-gululu-background-initial="https://image.example/scene.webp"',
                second,
            )

    def test_builds_importable_epub_with_source_chapter_groups(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "gululu.epub"
            floor_comments = _fixture("comments_floor_962170.json")["data"]["records"]
            floor_comments[0]["childrenComment"] = _fixture(
                "comments_children_9001.json"
            )["data"]["records"]
            build_epub(
                detail=_fixture("detail.json")["data"],
                floor_index=_fixture("floor_index.json")["data"],
                chapter_index=_fixture("chapter_index.json")["data"]["chapterIndex"],
                floors=_fixture("floors.json")["data"],
                comments_by_floor={
                    0: _fixture("comments_opus.json")["data"]["records"],
                    962170: floor_comments,
                },
                output_path=target,
            )

            book = EpubBook(str(target)).open()
            try:
                self.assertEqual(book.title, "测试安科")
                self.assertEqual(book.author, "测试作者")
                self.assertEqual(book.identifier, "gululu-66905")
                self.assertEqual(book.source, "https://www.gululu.world/book/66905")
                self.assertEqual(len(book.chapters), 3)
                self.assertEqual([entry.label for entry in book.toc], ["前言", "第一章", "第二章"])
                chapter = book.chapter_text(1)
                self.assertIn("第 2 楼", chapter)
                self.assertIn("第 3 楼", chapter)
                self.assertIn("折叠内容", chapter)
                first_chapter = book.chapter_text(0)
                self.assertIn("作品评论", first_chapter)
                self.assertIn("段落评论", first_chapter)
                self.assertIn("回复者", first_chapter)
                self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;<br/>第二行", first_chapter)
            finally:
                book.close()

            with zipfile.ZipFile(target) as zf:
                self.assertEqual(zf.read("mimetype"), b"application/epub+zip")
                css = zf.read("EPUB/style/main.css").decode("utf-8")
                self.assertIn("max-width:100%", css)
                self.assertIn("break-inside:avoid", css)
                chapters = [name for name in zf.namelist() if name.startswith("EPUB/chapters/")]
                entries = set(zf.namelist())
                self.assertEqual(len(chapters), 3)
                for name in chapters:
                    root = ET.fromstring(zf.read(name))
                    links = root.findall(".//{http://www.w3.org/1999/xhtml}link[@rel='stylesheet']")
                    self.assertTrue(links, f"章节缺少样式表：{name}")
                    for link in links:
                        resolved = posixpath.normpath(
                            posixpath.join(posixpath.dirname(name), link.get("href", ""))
                        )
                        self.assertIn(resolved, entries, f"章节样式表路径无效：{name} -> {resolved}")


if __name__ == "__main__":
    unittest.main()
