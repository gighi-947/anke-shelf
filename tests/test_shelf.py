"""书架与进度持久化单元测试。"""
import json
import tempfile
import unittest
from pathlib import Path

from app.shelf import BookRecord, ProgressStore, Shelf, _sniff_image_ext

SAMPLE_DIR = Path(__file__).parent / "sample"


def _make_rec(path: str, title: str, mtime: str = "2026-01-01T00:00:00+08:00") -> BookRecord:
    return BookRecord(
        id="a" * 32,
        path=path,
        title=title,
        author="作者",
        chapter_count=10,
        file_mtime=mtime,
    )


class TestShelf(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.covers = root / "covers"
        self.covers.mkdir()
        self.shelf = Shelf(root / "shelf.json", self.covers)

    def tearDown(self):
        self.tmp.cleanup()

    def test_upsert_list_sort(self):
        a = _make_rec("/a.epub", "甲", mtime="2026-01-01T00:00:00+08:00")
        b = _make_rec("/b.epub", "乙", mtime="2026-01-01T00:00:00+08:00")
        b.id = "b" * 32
        self.shelf.upsert(a)
        self.shelf.upsert(b)
        self.shelf.save()

        # 重新 load，round-trip 一致
        shelf2 = Shelf(self.shelf._file, self.covers)
        shelf2.load()
        self.assertEqual(len(shelf2.list_books()), 2)
        self.assertEqual(shelf2.get("a" * 32).title, "甲")

    def test_touch_sorts_to_front(self):
        a = _make_rec("/a.epub", "甲")
        b = _make_rec("/b.epub", "乙")
        b.id = "b" * 32
        self.shelf.upsert(a)
        self.shelf.upsert(b)
        self.shelf.save()
        self.shelf.touch("b" * 32)
        self.shelf.save()
        books = self.shelf.list_books()
        self.assertEqual(books[0].id, "b" * 32)  # 最近阅读排最前

    def test_touch_throttle(self):
        self.shelf.upsert(_make_rec("/a.epub", "甲"))
        self.shelf.touch("a" * 32, throttle=3600)  # 1h 节流
        first = self.shelf.get("a" * 32).last_read_at
        self.shelf.touch("a" * 32, throttle=3600)  # 立即再触 → 被节流
        self.assertEqual(self.shelf.get("a" * 32).last_read_at, first)

    def test_upsert_same_mtime_keeps_last_read(self):
        self.shelf.upsert(_make_rec("/a.epub", "甲"))
        self.shelf.touch("a" * 32, throttle=0)  # 跳过节流更新
        self.shelf.upsert(_make_rec("/a.epub", "甲"))  # mtime 相同 → 保留
        self.assertNotEqual(self.shelf.get("a" * 32).last_read_at, "")

    def test_remove_deletes_cover_file(self):
        self.shelf.upsert(_make_rec("/a.epub", "甲"))
        cover = self.covers / f"{'a' * 32}.png"
        cover.write_bytes(b"png")
        rec = self.shelf.get("a" * 32)
        rec.cover_rel = f"covers/{'a' * 32}.png"
        self.shelf.remove("a" * 32)
        self.assertFalse(cover.exists())
        self.assertIsNone(self.shelf.get("a" * 32))

    def test_set_custom_cover_copies_and_updates(self):
        self.shelf.upsert(_make_rec("/a.epub", "甲"))
        source = Path(self.tmp.name) / "custom.png"
        source.write_bytes(b"\x89PNG\r\n\x1a\nfake")
        rel = self.shelf.set_custom_cover("a" * 32, source)
        self.assertEqual(rel, f"covers/{'a' * 32}.png")
        self.assertTrue((self.covers / f"{'a' * 32}.png").exists())
        self.assertEqual(self.shelf.get("a" * 32).cover_rel, rel)

    def test_reset_cover_deletes_and_clears(self):
        self.shelf.upsert(_make_rec("/a.epub", "甲"))
        cover = self.covers / f"{'a' * 32}.png"
        cover.write_bytes(b"\x89PNG\r\n\x1a\nfake")
        rec = self.shelf.get("a" * 32)
        rec.cover_rel = f"covers/{'a' * 32}.png"
        self.assertTrue(self.shelf.reset_cover("a" * 32))
        self.assertFalse(cover.exists())
        self.assertIsNone(self.shelf.get("a" * 32).cover_rel)

    def test_atomic_write_valid_json(self):
        self.shelf.upsert(_make_rec("/a.epub", "甲"))
        self.shelf.save()
        with open(self.shelf._file, encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(data["version"], 1)
        # 无残留 tmp 文件
        self.assertEqual(list(self.shelf._file.parent.glob("*.tmp")), [])

    def test_save_does_not_persist_runtime_progress_pct(self):
        self.shelf.upsert(_make_rec("/a.epub", "甲"))
        rec = self.shelf.get("a" * 32)
        rec.progress_pct = 0.42  # 运行时合成字段
        self.shelf.save()
        with open(self.shelf._file, encoding="utf-8") as f:
            data = json.load(f)
        self.assertNotIn("progress_pct", data["books"][0])

    def test_load_corrupt_file(self):
        self.shelf._file.write_text("{corrupt", encoding="utf-8")
        self.shelf.load()
        self.assertEqual(self.shelf.list_books(), [])


class TestProgressStore(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = ProgressStore(Path(self.tmp.name) / "progress.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_set_get_roundtrip(self):
        self.store.set("a" * 32, 3, 1204)
        self.store.load()
        p = self.store.get("a" * 32)
        self.assertEqual(p["chapter_index"], 3)
        self.assertEqual(p["text_offset"], 1204)

    def test_offset_clamped(self):
        self.store.set("a" * 32, 1, -5)
        self.assertEqual(self.store.get("a" * 32)["text_offset"], 0)
        self.store.set("a" * 32, 1, 100)
        self.assertEqual(self.store.get("a" * 32)["text_offset"], 100)

    def test_remove(self):
        self.store.set("a" * 32, 1, 500)
        self.store.remove("a" * 32)
        self.assertIsNone(self.store.get("a" * 32))

    def test_missing_file(self):
        self.store.load()
        self.assertIsNone(self.store.get("a" * 32))


class TestSniffImage(unittest.TestCase):
    def test_png_jpg_gif(self):
        self.assertEqual(_sniff_image_ext(b"\x89PNG\r\n\x1a\nxxx"), "png")
        self.assertEqual(_sniff_image_ext(b"\xff\xd8\xff\xe0xxx"), "jpg")
        self.assertEqual(_sniff_image_ext(b"GIF89a"), "gif")
        self.assertEqual(_sniff_image_ext(b"RIFF\x10\x00\x00\x00WEBP"), "webp")
        self.assertEqual(_sniff_image_ext(b"<svg>"), "svg")
        self.assertEqual(_sniff_image_ext(b"?????"), "jpg")


if __name__ == "__main__":
    unittest.main()
