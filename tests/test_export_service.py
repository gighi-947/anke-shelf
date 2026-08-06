"""导出服务单元测试：后台复制、状态、单飞、打开目标文件夹。"""
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from app.export_service import ExportService


class _FakeRec:
    id = "a" * 32
    nga_tid = 41989465
    title = "我的安科"

    def __init__(self, path):
        self.path = path


class _FakeShelf:
    def __init__(self, rec):
        self._rec = rec

    def get(self, book_id):
        return self._rec if book_id == self._rec.id else None


class ExportServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.src = self.root / "src"
        self.src.mkdir()
        (self.src / "post.epub").write_bytes(b"epub-data")
        (self.src / "post.md").write_bytes("# 帖子".encode("utf-8"))
        self.rec = _FakeRec(str(self.src / "post.epub"))
        self.shelf = _FakeShelf(self.rec)
        self.dest = self.root / "out"
        self.dest.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def _wait_done(self, svc, timeout=3.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            st = svc.status()
            if not st["running"] and st["stage"] != "idle":
                return st
            time.sleep(0.02)
        return svc.status()

    def test_export_copies_both_formats(self):
        svc = ExportService(self.shelf, folder_picker=lambda: str(self.dest))
        r = svc.start(self.rec.id, "both")
        self.assertTrue(r["ok"])
        st = self._wait_done(svc)
        self.assertEqual(st["stage"], "done")
        self.assertEqual(set(st["files"]), {"我的安科.epub", "我的安科.md"})
        self.assertTrue((self.dest / "我的安科.epub").exists())
        self.assertTrue((self.dest / "我的安科.md").exists())

    def test_export_md_only(self):
        svc = ExportService(self.shelf, folder_picker=lambda: str(self.dest))
        svc.start(self.rec.id, "md")
        st = self._wait_done(svc)
        self.assertEqual(st["stage"], "done")
        self.assertEqual(st["files"], ["我的安科.md"])
        self.assertFalse((self.dest / "我的安科.epub").exists())

    def test_export_filename_sanitized(self):
        self.rec.title = '安科《测试》: 第1章/第二篇?'
        svc = ExportService(self.shelf, folder_picker=lambda: str(self.dest))
        svc.start(self.rec.id, "epub")
        st = self._wait_done(svc)
        self.assertEqual(st["stage"], "done")
        self.assertEqual(st["files"], ["安科《测试》 第1章第二篇.epub"])

    def test_export_empty_title_fallback(self):
        self.rec.title = ""
        svc = ExportService(self.shelf, folder_picker=lambda: str(self.dest))
        svc.start(self.rec.id, "epub")
        st = self._wait_done(svc)
        self.assertEqual(st["stage"], "done")
        self.assertEqual(st["files"], ["安科-tid41989465.epub"])

    def test_export_single_flight(self):
        svc = ExportService(self.shelf, folder_picker=lambda: str(self.dest))
        svc._set(running=True, stage="prepare")
        r = svc.start(self.rec.id, "both")
        self.assertFalse(r["ok"])
        self.assertIn("已有", r["error"])

    def test_export_cancel_picker_marks_cancelled(self):
        svc = ExportService(self.shelf, folder_picker=lambda: "")
        svc.start(self.rec.id, "both")
        st = self._wait_done(svc)
        self.assertEqual(st["stage"], "cancelled")

    def test_open_dest(self):
        svc = ExportService(self.shelf, folder_picker=lambda: str(self.dest))
        svc.start(self.rec.id, "both")
        self._wait_done(svc)
        with patch("os.startfile") as startfile:
            r = svc.open_dest()
        self.assertTrue(r["ok"])
        startfile.assert_called_once_with(str(self.dest))


if __name__ == "__main__":
    unittest.main()
