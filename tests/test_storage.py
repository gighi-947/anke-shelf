"""统一持久化工具测试。"""
import json
import tempfile
import unittest
from pathlib import Path

from app.storage import atomic_write_json, atomic_write_text, now_iso


class TestStorage(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_atomic_write_json(self):
        p = self.root / "data.json"
        atomic_write_json(p, {"a": 1})
        self.assertEqual(json.loads(p.read_text(encoding="utf-8")), {"a": 1})
        self.assertEqual(list(self.root.glob("*.tmp")), [])

    def test_atomic_write_text(self):
        p = self.root / "meta.txt"
        atomic_write_text(p, "hello")
        self.assertEqual(p.read_text(encoding="utf-8"), "hello")
        self.assertEqual(list(self.root.glob("*.tmp")), [])

    def test_now_iso_format(self):
        v = now_iso()
        self.assertRegex(v, r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+\d{2}:\d{2}$")


if __name__ == "__main__":
    unittest.main()
