"""标注存储单元测试：高亮/书签 CRUD、原子写、跨书隔离、导出。"""
import json
import tempfile
import unittest
from pathlib import Path

from app.annotations import AnnotationStore


class TestAnnotationStore(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = AnnotationStore(Path(self.tmp.name) / "annotations.json")

    def tearDown(self):
        self.tmp.cleanup()

    # ---------- 高亮 ----------

    def test_add_get(self):
        h = self.store.add_highlight("a" * 32, 2, 100, 200, "原文", "yellow", "笔记")
        self.assertEqual(h["chapter_index"], 2)
        self.assertEqual(h["start_offset"], 100)
        self.assertEqual(h["end_offset"], 200)
        got = self.store.get_highlights("a" * 32)
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["id"], h["id"])

    def test_roundtrip_persist(self):
        self.store.add_highlight("a" * 32, 0, 5, 10, "x")
        s2 = AnnotationStore(Path(self.tmp.name) / "annotations.json")
        s2.load()
        self.assertEqual(len(s2.get_highlights("a" * 32)), 1)

    def test_invalid_range_rejected(self):
        with self.assertRaises(ValueError):
            self.store.add_highlight("a" * 32, 0, 200, 100, "x")
        with self.assertRaises(ValueError):
            self.store.add_highlight("a" * 32, 0, 100, 100, "x")

    def test_color_fallback(self):
        h = self.store.add_highlight("a" * 32, 0, 0, 5, "x", color="not-a-color")
        self.assertEqual(h["color"], "yellow")

    def test_update_note_color(self):
        h = self.store.add_highlight("a" * 32, 0, 0, 5, "x", note="n1")
        r = self.store.update_annotation("a" * 32, h["id"], {"note": "n2", "color": "blue"})
        self.assertEqual(r["note"], "n2")
        self.assertEqual(r["color"], "blue")
        # 非法色不生效
        r2 = self.store.update_annotation("a" * 32, h["id"], {"color": "bad"})
        self.assertEqual(r2["color"], "blue")
        # 不存在的 id
        self.assertIsNone(self.store.update_annotation("a" * 32, "nope", {"note": "x"}))

    def test_delete_annotation(self):
        h = self.store.add_highlight("a" * 32, 0, 0, 5, "x")
        self.assertTrue(self.store.delete_annotation("a" * 32, h["id"]))
        self.assertFalse(self.store.delete_annotation("a" * 32, h["id"]))
        self.assertEqual(self.store.get_highlights("a" * 32), [])

    # ---------- 书签 ----------

    def test_bookmark_crud(self):
        bm = self.store.add_bookmark("a" * 32, 3, 500, "所在句")
        self.assertEqual(bm["offset"], 500)
        self.assertEqual(len(self.store.get_bookmarks("a" * 32)), 1)
        self.assertTrue(self.store.delete_bookmark("a" * 32, bm["id"]))
        self.assertEqual(self.store.get_bookmarks("a" * 32), [])

    # ---------- 隔离与清理 ----------

    def test_books_isolated(self):
        self.store.add_highlight("a" * 32, 0, 0, 5, "x")
        self.store.add_highlight("b" * 32, 1, 0, 5, "y")
        self.assertEqual(len(self.store.get_highlights("a" * 32)), 1)
        self.assertEqual(len(self.store.get_highlights("b" * 32)), 1)

    def test_remove_book(self):
        self.store.add_highlight("a" * 32, 0, 0, 5, "x")
        self.store.add_bookmark("a" * 32, 0, 5, "b")
        self.store.remove_book("a" * 32)
        self.assertEqual(self.store.get_all("a" * 32), {"highlights": [], "bookmarks": []})

    def test_atomic_write(self):
        self.store.add_highlight("a" * 32, 0, 0, 5, "x")
        with open(self.store._file, encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(data["version"], 1)
        self.assertEqual(list(self.store._file.parent.glob("*.tmp")), [])

    def test_load_corrupt(self):
        self.store._file.write_text("{bad", encoding="utf-8")
        self.store.load()
        self.assertEqual(self.store.get_all("a" * 32), {"highlights": [], "bookmarks": []})

    # ---------- 导出 ----------

    def test_export_markdown(self):
        self.store.add_highlight("a" * 32, 1, 10, 20, "引力波是时空涟漪", "yellow", "重要")
        self.store.add_bookmark("a" * 32, 2, 30, "下一章起点")
        out = self.store.export("a" * 32, "markdown", "测试书", lambda i: f"第 {i + 1} 章")
        self.assertIn("# 测试书", out)
        self.assertIn("## 第 2 章", out)
        self.assertIn("引力波是时空涟漪", out)
        self.assertIn("重要", out)
        self.assertIn("下一章起点", out)

    def test_export_json(self):
        self.store.add_highlight("a" * 32, 0, 0, 5, "x")
        out = self.store.export("a" * 32, "json", "测试书", lambda i: "")
        data = json.loads(out)
        self.assertEqual(data["book"], "测试书")
        self.assertEqual(len(data["highlights"]), 1)


if __name__ == "__main__":
    unittest.main()
