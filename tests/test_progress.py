"""进度存储单元测试：text_offset 坐标系与旧 scroll_ratio 迁移。"""
import tempfile
import unittest
from pathlib import Path

from app.shelf import ProgressStore


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
        self.assertIn("updated_at", p)

    def test_offset_clamped(self):
        self.store.set("a" * 32, 1, -5)
        self.assertEqual(self.store.get("a" * 32)["text_offset"], 0)
        self.store.set("a" * 32, 1, 100)
        self.assertEqual(self.store.get("a" * 32)["text_offset"], 100)

    def test_remove(self):
        self.store.set("a" * 32, 1, 10)
        self.store.remove("a" * 32)
        self.assertIsNone(self.store.get("a" * 32))

    def test_missing_file(self):
        self.store.load()
        self.assertIsNone(self.store.get("a" * 32))

    def test_save_version_2(self):
        self.store.set("a" * 32, 0, 0)
        with open(self.store._file, encoding="utf-8") as f:
            import json

            data = json.load(f)
        self.assertEqual(data["version"], 2)


class TestMigrate(unittest.TestCase):
    def test_keeps_text_offset(self):
        old = {"chapter_index": 2, "text_offset": 500}
        out = ProgressStore.migrate(old, 1000)
        self.assertEqual(out["text_offset"], 500)

    def test_migrates_scroll_ratio(self):
        old = {"chapter_index": 2, "scroll_ratio": 0.5}
        out = ProgressStore.migrate(old, 1000)
        self.assertEqual(out["text_offset"], 500)
        self.assertNotIn("scroll_ratio", out)

    def test_migrate_no_len_falls_back_zero(self):
        old = {"chapter_index": 2, "scroll_ratio": 0.5}
        out = ProgressStore.migrate(old, None)
        self.assertEqual(out["text_offset"], 0)

    def test_migrate_clamps_ratio(self):
        old = {"chapter_index": 2, "scroll_ratio": 1.5}
        out = ProgressStore.migrate(old, 100)
        self.assertEqual(out["text_offset"], 100)


if __name__ == "__main__":
    unittest.main()
