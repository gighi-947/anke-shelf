"""NGA 集成层单元测试（不依赖 httpx/ebooklib，用临时数据目录）。"""
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from app.nga_config import DEFAULT_UA, ensure_nga_config, load_nga_config, save_nga_config
from app.nga_service import NgaService, _parse_tid
from app.server import _CSP
from app.shelf import BookRecord, _record_from_dict


class TestParseTid(unittest.TestCase):
    def test_digit(self):
        self.assertEqual(_parse_tid("41989465"), (41989465, None))

    def test_url(self):
        self.assertEqual(
            _parse_tid("https://bbs.nga.cn/read.php?tid=41989465&page=2"),
            (41989465, None),
        )

    def test_invalid(self):
        tid, err = _parse_tid("hello")
        self.assertEqual(tid, 0)
        self.assertIsNotNone(err)
        tid, err = _parse_tid("")
        self.assertEqual(tid, 0)
        self.assertIsNotNone(err)


class TestNgaConfig(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_ensure_creates_template(self):
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            path = ensure_nga_config()
            self.assertTrue(path.exists())
            cfg = load_nga_config()
            self.assertFalse(cfg["configured"])
            self.assertEqual(cfg["ua"], DEFAULT_UA)

    def test_empty_ua_falls_back_to_default(self):
        path = self.root / "nga_config.ini"
        path.write_text(
            "[network]\n"
            "base_url = https://bbs.nga.cn\n"
            "ua = \n"
            "ngaPassportUid = \n"
            "ngaPassportCid = \n",
            encoding="utf-8",
        )
        with patch("app.nga_config.nga_config_path", return_value=path), \
                patch("app.nga_config.data_dir", return_value=self.root):
            cfg = load_nga_config()
            self.assertEqual(cfg["ua"], DEFAULT_UA)

    def test_import_from_source(self):
        src = self.root / "config.ini"
        src.write_text(
            "[network]\n"
            "base_url = https://bbs.nga.cn\n"
            "ua = `Mozilla/5.0 test`\n"
            "ngaPassportUid = 12345\n"
            "ngaPassportCid = abcdef\n",
            encoding="utf-8",
        )
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=src):
            ensure_nga_config()
            cfg = load_nga_config()
            self.assertTrue(cfg["configured"])
            self.assertEqual(cfg["uid"], "12345")
            self.assertEqual(cfg["cid"], "abcdef")
            self.assertEqual(cfg["ua"], "Mozilla/5.0 test")

    def test_save_roundtrip(self):
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            ensure_nga_config()
            out = save_nga_config({"uid": "999", "cid": "xyz", "ua": "UA/1.0"})
            self.assertTrue(out["configured"])
            again = load_nga_config()
            self.assertEqual(again["uid"], "999")
            self.assertEqual(again["cid"], "xyz")
            self.assertEqual(again["ua"], "UA/1.0")


class TestNgaService(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.svc = NgaService(book_register=lambda path: "bookid")

    def tearDown(self):
        self.tmp.cleanup()

    def test_start_invalid_tid(self):
        r = self.svc.start({"tid": "not-a-tid"})
        self.assertFalse(r["ok"])
        self.assertIn("无法识别", r["error"])

    def test_start_unconfigured(self):
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            ensure_nga_config()
            r = self.svc.start({"tid": "41989465"})
            self.assertFalse(r["ok"])
            self.assertIn("Cookie", r["error"])

    def test_single_flight(self):
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            ensure_nga_config()
            save_nga_config({"uid": "1", "cid": "2", "ua": "UA"})
        with patch("app.nga_service._import_nga", side_effect=RuntimeError("boom")):
            self.svc._set(running=True)  # 模拟已有任务
            r = self.svc.start({"tid": "41989465"})
            self.assertFalse(r["ok"])
            self.assertIn("已有", r["error"])
            self.svc._set(running=False)

    def test_cancel_sets_event(self):
        self.svc.cancel()
        self.assertTrue(self.svc._cancel.is_set())

    def test_cancel_removes_newly_created_folder(self):
        target = self.root / "nga_library" / "123(0)"

        def fake_download(tiezi, **kw):
            target.mkdir(parents=True, exist_ok=True)
            (target / "post.epub").write_bytes(b"partial")
            raise RuntimeError("cancelled")

        fake_nga = types.SimpleNamespace(
            set_cancel_cb=lambda cb: None,
            find_folder_name_by_tid=lambda tid, aid: "",
            init_from_web=lambda t: None,
            init_nga=lambda client, cfg: None,
            download=fake_download,
        )
        fake_cfg = types.SimpleNamespace(
            thread=2, page_download_limit=0, max_floors=0, no_images=False,
            no_media=True, epub_enabled=True, epub_image_mode="embedded",
            epub_per_chapter=20, epub_image_quality=85, epub_image_max_size=1280,
            epub_theme="light", epub_toc_pid=0, output_path=str(self.root / "nga_library"),
        )
        class FakeTiezi:
            def __init__(self, tid, author_id):
                self.tid = tid
                self.author_id = author_id
                self.folder_name = "123(0)"

        fake_import = (
            types.SimpleNamespace(NgaClient=lambda cfg: types.SimpleNamespace(close=lambda: None)),
            types.SimpleNamespace(load_config=lambda p: fake_cfg),
            fake_nga,
            FakeTiezi,
        )
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            ensure_nga_config()
            save_nga_config({"uid": "1", "cid": "2", "ua": "UA"})
            with patch("app.nga_service._import_nga", return_value=fake_import):
                self.svc._cancel.set()
                with self.assertRaises(RuntimeError):
                    self.svc._download(123, {"authorid": 0})
        self.assertFalse(target.exists())

    def test_cancel_keeps_existing_folder(self):
        target = self.root / "nga_library" / "123(0)"
        target.mkdir(parents=True, exist_ok=True)
        (target / "post.epub").write_bytes(b"old-complete")

        def fake_download(tiezi, **kw):
            (target / "post.epub").write_bytes(b"partial-overwrite")
            raise RuntimeError("cancelled")

        fake_nga = types.SimpleNamespace(
            set_cancel_cb=lambda cb: None,
            find_folder_name_by_tid=lambda tid, aid: "123(0)",
            init_from_local=lambda t: None,
            init_nga=lambda client, cfg: None,
            download=fake_download,
        )
        fake_cfg = types.SimpleNamespace(
            thread=2, page_download_limit=0, max_floors=0, no_images=False,
            no_media=True, epub_enabled=True, epub_image_mode="embedded",
            epub_per_chapter=20, epub_image_quality=85, epub_image_max_size=1280,
            epub_theme="light", epub_toc_pid=0, output_path=str(self.root / "nga_library"),
        )
        class FakeTiezi:
            def __init__(self, tid, author_id):
                self.tid = tid
                self.author_id = author_id
                self.folder_name = "123(0)"

        fake_import = (
            types.SimpleNamespace(NgaClient=lambda cfg: types.SimpleNamespace(close=lambda: None)),
            types.SimpleNamespace(load_config=lambda p: fake_cfg),
            fake_nga,
            FakeTiezi,
        )
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            ensure_nga_config()
            save_nga_config({"uid": "1", "cid": "2", "ua": "UA"})
            with patch("app.nga_service._import_nga", return_value=fake_import):
                self.svc._cancel.set()
                with self.assertRaises(RuntimeError):
                    self.svc._download(123, {"authorid": 0})
        self.assertTrue(target.exists())
        self.assertEqual((target / "post.epub").read_bytes(), b"partial-overwrite")

    def test_status_jsonable(self):
        self.assertIsInstance(self.svc.status(), dict)


class TestRecordNgaTid(unittest.TestCase):
    def test_roundtrip(self):
        d = {
            "id": "a" * 32, "path": "/x.epub", "title": "t",
            "nga_tid": 41989465,
        }
        rec = _record_from_dict(d)
        self.assertEqual(rec.nga_tid, 41989465)
        back = _record_from_dict({k: v for k, v in d.items() if k != "nga_tid"})
        self.assertEqual(back.nga_tid, 0)

    def test_default_field(self):
        rec = BookRecord(id="b" * 32, path="/y.epub", title="t")
        self.assertEqual(rec.nga_tid, 0)


class TestServerCsp(unittest.TestCase):
    def test_https_images_allowed(self):
        self.assertIn("img-src 'self' data: https:", _CSP)
        self.assertIn("script-src 'none'", _CSP)


if __name__ == "__main__":
    unittest.main()
