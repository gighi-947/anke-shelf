"""统一持久化工具测试。"""
import json
import tempfile
import unittest
from pathlib import Path

from app.storage import (
    atomic_write_json,
    atomic_write_text,
    load_json_file,
    now_iso,
    verify_json_file,
)


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

    def test_atomic_write_keeps_previous_backup(self):
        p = self.root / "data.json"
        atomic_write_json(p, {"a": 1})
        atomic_write_json(p, {"a": 2})
        bak = self.root / "data.json.bak"
        self.assertTrue(bak.exists())
        self.assertEqual(json.loads(bak.read_text(encoding="utf-8")), {"a": 1})

    def test_corrupt_file_isolated_and_default_returned(self):
        p = self.root / "shelf.json"
        p.write_text("{not json", encoding="utf-8")
        self.assertIsNone(load_json_file(p))
        self.assertFalse(p.exists())
        corrupt = list(self.root.glob("shelf.json.corrupt-*"))
        self.assertEqual(len(corrupt), 1)
        self.assertEqual(corrupt[0].read_text(encoding="utf-8"), "{not json")
        # 再次读取不再生成新的隔离文件（文件已缺失）
        self.assertIsNone(load_json_file(p))
        self.assertEqual(len(list(self.root.glob("shelf.json.corrupt-*"))), 1)

    def test_verify_reports_health_and_version(self):
        ok = self.root / "shelf.json"
        ok.write_text('{"version": 1, "books": []}', encoding="utf-8")
        report = verify_json_file(ok)
        self.assertTrue(report["ok"])
        self.assertEqual(report["version"], 1)

        bad = self.root / "bad.json"
        bad.write_text("boom", encoding="utf-8")
        report = verify_json_file(bad)
        self.assertFalse(report["ok"])
        self.assertTrue(report["error"])

        missing = verify_json_file(self.root / "none.json")
        self.assertTrue(missing["ok"])
        self.assertEqual(missing["error"], "missing")


if __name__ == "__main__":
    unittest.main()
