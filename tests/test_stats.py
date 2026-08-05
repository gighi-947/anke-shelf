"""阅读统计存储单元测试。"""
import tempfile
import unittest
from pathlib import Path

from app.stats import StatsStore


class TestStatsStore(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = StatsStore(Path(self.tmp.name) / "statistics.json")

    def tearDown(self):
        self.tmp.cleanup()

    def test_record_accumulate(self):
        self.store.record_reading("a" * 32, 60, 10)
        b = self.store.get_book("a" * 32)
        self.assertEqual(b["total_seconds"], 60)
        self.assertEqual(b["pages_flipped"], 10)
        self.assertEqual(b["sessions"], 1)
        # 再记录
        self.store.record_reading("a" * 32, 30, 5)
        b = self.store.get_book("a" * 32)
        self.assertEqual(b["total_seconds"], 90)
        self.assertEqual(b["pages_flipped"], 15)

    def test_global(self):
        self.store.record_reading("a" * 32, 60)
        self.store.record_reading("b" * 32, 30)
        self.assertEqual(self.store.get_global()["total_seconds"], 90)

    def test_zero_seconds_no_session(self):
        self.store.record_reading("a" * 32, 0)
        b = self.store.get_book("a" * 32)
        self.assertEqual(b["sessions"], 0)
        self.assertEqual(b["total_seconds"], 0)

    def test_persist_roundtrip(self):
        self.store.record_reading("a" * 32, 45, 3)
        s2 = StatsStore(Path(self.tmp.name) / "statistics.json")
        s2.load()
        self.assertEqual(s2.get_book("a" * 32)["total_seconds"], 45)

    def test_remove_book(self):
        self.store.record_reading("a" * 32, 10)
        self.store.remove_book("a" * 32)
        self.assertEqual(self.store.get_book("a" * 32)["total_seconds"], 0)

    def test_load_corrupt(self):
        self.store._file.write_text("{bad", encoding="utf-8")
        self.store.load()
        self.assertEqual(self.store.get_book("a" * 32)["total_seconds"], 0)


if __name__ == "__main__":
    unittest.main()
