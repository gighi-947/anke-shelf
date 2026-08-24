"""楼层导出服务单元测试（映射与文件名，不含 Playwright 渲染）。"""
import json
import tempfile
import unittest
from pathlib import Path

from app.floor_export_service import FloorExportService, _safe_filename


class _DummyBook:
    def __init__(self, chapters):
        self.title = "测试"
        self.chapters = chapters


class _DummyChapter:
    def __init__(self, href):
        self.href = href


class FloorExportMappingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.books = None
        self.service = FloorExportService(None, None, server_port=12345)

    def tearDown(self):
        self.tmp.cleanup()

    def _nga_rec(self, root):
        from types import SimpleNamespace
        return SimpleNamespace(id="nga-book", path=str(root), title="测试安科", nga_tid=12345)

    def test_safe_filename(self):
        self.assertEqual("安科测试", _safe_filename(r'安科<>:"/\|?*测试'))

    def test_resolve_nga_floors(self):
        root = self.root / "nga_book"
        root.mkdir()
        (root / "meta.json").write_text(json.dumps({
            "format": "ank-native/1",
            "book_id": "nga-book",
            "tid": 12345,
            "title": "测试安科",
            "per_chapter": 2,
            "image_mode": "online",
            "theme": "light",
            "chapters": [
                {"file": "chapters/0000.xhtml", "title": "主楼", "floor_count": 1, "first_lou": 0, "last_lou": 0, "main": True},
                {"file": "chapters/0001.xhtml", "title": "第1~2楼", "floor_count": 2, "first_lou": 1, "last_lou": 2, "main": False},
            ],
        }, ensure_ascii=False), encoding="utf-8")
        (root / "floors.json").write_text(json.dumps([
            {"pid": 111, "lou": 0, "raw_content": "", "username": "a", "user_id": 1, "timestamp": 0, "like_num": 0, "comments": []},
            {"pid": 222, "lou": 1, "raw_content": "", "username": "a", "user_id": 1, "timestamp": 0, "like_num": 0, "comments": []},
        ], ensure_ascii=False), encoding="utf-8")
        mapping = self.service._resolve_nga_floors(self._nga_rec(root), None, [0, 1])
        self.assertEqual(mapping[0]["selector"], "#pid111")
        self.assertIn("/book/nga-book/chapters/0000.xhtml", mapping[0]["url"])
        self.assertEqual(mapping[1]["selector"], "#pid222")
        self.assertIn("/book/nga-book/chapters/0001.xhtml", mapping[1]["url"])

    def test_resolve_gululu_floors(self):
        from types import SimpleNamespace
        lib = self.root / "gululu_library" / "68846"
        lib.mkdir(parents=True)
        (lib / "post.epub").write_bytes(b"dummy")
        (lib / "snapshot.json").write_text(json.dumps({
            "version": 1,
            "source_id": 68846,
            "floor_index": [
                {"floorId": 1001, "floorNum": 1, "name": "前言"},
                {"floorId": 1002, "floorNum": 2, "name": "正文"},
            ],
            "chapter_index": [{"floor": 1, "title": "前言"}, {"floor": 2, "title": "正文"}],
        }, ensure_ascii=False), encoding="utf-8")
        rec = SimpleNamespace(id="g-book", path=str(lib / "post.epub"), title="骨碌碌", nga_tid=0)
        book = _DummyBook([_DummyChapter("EPUB/chapters/chapter_0001.xhtml"), _DummyChapter("EPUB/chapters/chapter_0002.xhtml")])
        mapping = self.service._resolve_gululu_floors(rec, book, [1, 2])
        self.assertEqual(mapping[1]["selector"], "#floor-1001")
        self.assertIn("/EPUB/chapters/chapter_0001.xhtml", mapping[1]["url"])
        self.assertEqual(mapping[2]["selector"], "#floor-1002")
        self.assertIn("/EPUB/chapters/chapter_0002.xhtml", mapping[2]["url"])


if __name__ == "__main__":
    unittest.main()
