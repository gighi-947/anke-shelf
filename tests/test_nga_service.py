"""NGA 集成层单元测试（不依赖 httpx/ebooklib，用临时数据目录）。"""
import hashlib
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT / "ngapost2md-python"))

from app.errors import ApiError
from app.nga_config import DEFAULT_UA, ensure_nga_config, load_nga_config, save_nga_config
from app.nga_login import NgaLoginController, parse_nga_cookie_text
from app.nga_service import NgaService, _parse_tid
from app.server import _CSP
from app.shelf import BookRecord, Shelf, _record_from_dict


class TestParseNgaCookieText(unittest.TestCase):
    def test_full_cookie_header(self):
        parsed = parse_nga_cookie_text(
            "ngaPassportUid=12345; ngaPassportCid=abcdef; other=1"
        )
        self.assertEqual(parsed["uid"], "12345")
        self.assertEqual(parsed["cid"], "abcdef")

    def test_quoted_and_spaced(self):
        parsed = parse_nga_cookie_text(
            "ngaPassportUid = '111'; ngaPassportCid=\"xyz\""
        )
        self.assertEqual(parsed["uid"], "111")
        self.assertEqual(parsed["cid"], "xyz")

    def test_missing_is_empty(self):
        parsed = parse_nga_cookie_text("a=b; c=d")
        self.assertEqual(parsed["uid"], "")
        self.assertEqual(parsed["cid"], "")


class TestNgaLoginControllerStatus(unittest.TestCase):
    def test_idle_status_shape(self):
        ctl = NgaLoginController()
        with patch("app.nga_login.load_nga_config", return_value={"configured": False}):
            st = ctl.status()
        self.assertEqual(st["state"], "idle")
        self.assertFalse(st["open"])
        self.assertFalse(st["configured"])
        self.assertEqual(st["error"], "")


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
        with self.assertRaises(ApiError) as cm:
            self.svc.start({"tid": "not-a-tid"})
        self.assertIn("无法识别", cm.exception.message)

    def test_start_unconfigured(self):
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            ensure_nga_config()
            with self.assertRaises(ApiError) as cm:
                self.svc.start({"tid": "41989465"})
            self.assertIn("Cookie", cm.exception.message)

    def test_single_flight(self):
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            ensure_nga_config()
            save_nga_config({"uid": "1", "cid": "2", "ua": "UA"})
        with patch("app.nga_service._import_nga", side_effect=RuntimeError("boom")):
            self.svc._set(running=True)  # 模拟已有任务
            with self.assertRaises(ApiError) as cm:
                self.svc.start({"tid": "41989465"})
            self.assertIn("已有", cm.exception.message)
            self.svc._set(running=False)

    def test_cancel_marks_current_task(self):
        self.svc._current_task = "task-1"
        self.svc.cancel()
        self.assertTrue(self.svc._tasks.is_cancelled("task-1"))

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
                with self.assertRaises(RuntimeError):
                    self.svc._download(123, {"authorid": 0}, cancelled=lambda: True)
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
                with self.assertRaises(RuntimeError):
                    self.svc._download(123, {"authorid": 0}, cancelled=lambda: True)
        self.assertTrue(target.exists())
        self.assertEqual((target / "post.epub").read_bytes(), b"partial-overwrite")

    def test_download_registers_native_container(self):
        folder_name = "123(0)"
        target = self.root / "nga_library" / folder_name
        target.mkdir(parents=True)
        (target / "post.epub").write_bytes(b"epub")

        class FakeFloor:
            lou = 1
            pid = 0
            timestamp = 0
            username = "u"
            user_id = 1
            like_num = 0
            raw_content = "<p>main</p>"
            comments = []

        class FakeTiezi:
            def __init__(self, tid, author_id):
                self.tid = tid
                self.author_id = author_id
                self.folder_name = folder_name
                self.max_lou = -1
                self.floors = [FakeFloor()]
                self.title = "标题"
                self.username = "作者"
                self.toc_chapters = [{"title": "第一卷", "lead": [("起点", 1)], "days": []}]

        fake_nga = types.SimpleNamespace(
            set_cancel_cb=lambda cb: None,
            find_folder_name_by_tid=lambda tid, aid: "",
            init_from_web=lambda t: None,
            init_nga=lambda client, cfg: None,
            download=lambda tiezi, **kw: None,
        )
        fake_cfg = types.SimpleNamespace(
            thread=2, page_download_limit=0, max_floors=0, no_images=False,
            no_media=True, epub_enabled=True, epub_image_mode="embedded",
            epub_per_chapter=20, epub_image_quality=85, epub_image_max_size=1280,
            epub_theme="light", epub_toc_pid=0, output_path=str(self.root / "nga_library"),
        )
        fake_import = (
            types.SimpleNamespace(NgaClient=lambda cfg: types.SimpleNamespace(close=lambda: None)),
            types.SimpleNamespace(load_config=lambda p: fake_cfg),
            fake_nga,
            FakeTiezi,
        )
        registered = []
        svc = NgaService(book_register=lambda path: registered.append(path) or "bookid")
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            ensure_nga_config()
            save_nga_config({"uid": "1", "cid": "2", "ua": "UA"})
            with patch("app.nga_service._import_nga", return_value=fake_import):
                bid = svc._download(123, {
                    "authorid": 0, "theme": "dark", "image_mode": "none",
                    "per_chapter": 30, "toc_mode": "split",
                })
        self.assertEqual(bid, "bookid")
        self.assertEqual(len(registered), 1)
        native = self.root / "nga_library" / folder_name / "book"
        self.assertEqual(Path(registered[0]), native)
        self.assertTrue((native / "meta.json").is_file())
        meta = json.loads((native / "meta.json").read_text(encoding="utf-8"))
        self.assertEqual(meta["theme"], "dark")
        self.assertEqual(meta["image_mode"], "none")
        self.assertEqual(meta["per_chapter"], 30)
        self.assertEqual(meta["toc_mode"], "split")
        self.assertEqual(meta["toc"][0]["title"], "第一卷")
        self.assertEqual(meta["book_id"], hashlib.md5(str(native).encode("utf-8")).hexdigest())
        self.assertTrue((native / "floors.json").is_file())
        settings = json.loads((target / "download_settings.json").read_text(encoding="utf-8"))
        self.assertEqual(settings["theme"], "dark")
        self.assertEqual(settings["image_mode"], "none")
        self.assertEqual(settings["per_chapter"], 30)
        self.assertEqual(settings["toc_mode"], "split")

    def test_status_jsonable(self):
        self.assertIsInstance(self.svc.status(), dict)

    def _shelf_with(self, rec: BookRecord) -> Shelf:
        (self.root / "covers").mkdir(exist_ok=True)
        shelf = Shelf(self.root / "shelf.json", self.root / "covers")
        shelf.load()
        shelf.upsert(rec)
        shelf.save()
        return shelf

    def test_update_defaults_from_manifest(self):
        folder = self.root / "nga_library" / "41989465(62906407)"
        folder.mkdir(parents=True)
        (folder / "download_settings.json").write_text(json.dumps({
            "tid": 41989465, "author_id": 62906407, "theme": "dark",
            "image_mode": "none", "per_chapter": 30, "toc_pid": 123,
        }), encoding="utf-8")
        rec = BookRecord(
            id="a" * 32, path=str(folder / "post.epub"),
            title="t", nga_tid=41989465,
        )
        svc = NgaService(book_register=lambda p: "bid", shelf=self._shelf_with(rec))
        with patch("app.paths.data_dir", return_value=self.root):
            d = svc.update_defaults("a" * 32)
        self.assertTrue(d["ok"])
        self.assertEqual(d["author_id"], 62906407)
        self.assertEqual(d["theme"], "dark")
        self.assertEqual(d["image_mode"], "none")
        self.assertEqual(d["per_chapter"], 30)
        self.assertEqual(d["toc_pid"], 123)

    def test_update_defaults_fallback_native_meta(self):
        folder = self.root / "nga_library" / "41989465(123)"
        native = folder / "book"
        native.mkdir(parents=True)
        (native / "meta.json").write_text(json.dumps({
            "tid": 41989465, "author_id": 123, "theme": "dark",
            "image_mode": "embedded", "per_chapter": 50,
        }), encoding="utf-8")
        rec = BookRecord(
            id="b" * 32, path=str(native), title="t", nga_tid=41989465,
        )
        svc = NgaService(book_register=lambda p: "bid", shelf=self._shelf_with(rec))
        with patch("app.paths.data_dir", return_value=self.root):
            d = svc.update_defaults("b" * 32)
        self.assertEqual(d["author_id"], 123)
        self.assertEqual(d["theme"], "dark")
        self.assertEqual(d["image_mode"], "embedded")
        self.assertEqual(d["per_chapter"], 50)

    def test_update_defaults_sniffs_epub_theme(self):
        import zipfile

        folder = self.root / "nga_library" / "41989465"
        folder.mkdir(parents=True)
        epub = folder / "post.epub"
        with zipfile.ZipFile(epub, "w") as z:
            z.writestr("style.css", "body { background:#1e1e1e; color:#d0d0d0; }")
        rec = BookRecord(
            id="c" * 32, path=str(epub), title="t", nga_tid=41989465,
        )
        svc = NgaService(book_register=lambda p: "bid", shelf=self._shelf_with(rec))
        with patch("app.paths.data_dir", return_value=self.root):
            d = svc.update_defaults("c" * 32)
        self.assertEqual(d["theme"], "dark")
        self.assertEqual(d["author_id"], 0)

    def test_update_defaults_rejects_non_nga_book(self):
        rec = BookRecord(id="d" * 32, path=str(self.root / "x.epub"), title="t")
        svc = NgaService(book_register=lambda p: "bid", shelf=self._shelf_with(rec))
        with self.assertRaises(ApiError) as cm:
            svc.update_defaults("d" * 32)
        self.assertIn("仅支持更新", cm.exception.message)

    def test_update_book_fills_stored_defaults(self):
        folder = self.root / "nga_library" / "41989465(62906407)"
        folder.mkdir(parents=True)
        (folder / "download_settings.json").write_text(json.dumps({
            "tid": 41989465, "author_id": 62906407, "theme": "dark",
            "image_mode": "none", "per_chapter": 30, "toc_pid": 123,
        }), encoding="utf-8")
        rec = BookRecord(
            id="e" * 32, path=str(folder / "post.epub"),
            title="t", nga_tid=41989465,
        )
        svc = NgaService(book_register=lambda p: "bid", shelf=self._shelf_with(rec))
        captured = {}

        def fake_thread(*args, **kwargs):
            target = kwargs.get("target")
            return types.SimpleNamespace(start=lambda: target())

        def fake_run_update(*args):
            captured["params"] = args[4]

        with patch.object(svc, "_run_update", fake_run_update), \
                patch("app.nga_service.threading.Thread", fake_thread), \
                patch("app.paths.data_dir", return_value=self.root):
            r = svc.update_book("e" * 32, {})
        self.assertTrue(r["ok"])
        effective = captured["params"]
        self.assertEqual(effective["theme"], "dark")
        self.assertEqual(effective["authorid"], 62906407)
        self.assertEqual(effective["image_mode"], "none")
        self.assertEqual(effective["per_chapter"], 30)
        self.assertEqual(effective["toc_pid"], 123)


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
