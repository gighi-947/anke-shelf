"""骨碌碌 EPUB 后台导入服务回归测试。"""
import json
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from app.api import Api
from app.book_manager import BookManager
from app.errors import ApiError
from app.gululu_epub import (
    GululuBuildResult,
    GululuCancelled,
    GululuIndex,
    GululuSnapshot,
    build_epub,
)
from app.gululu_service import GululuService
from app.gululu_update import (
    BaselineOk,
    load_baseline,
    replace_and_register,
    write_baseline,
)
from app.search import SearchService
from app.settings import Settings
from app.shelf import ProgressStore, Shelf


FIXTURE_DIR = Path(__file__).parent / "fixtures" / "gululu"


def _fixture(name: str):
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


def _snapshot() -> GululuSnapshot:
    return GululuSnapshot(
        detail=_fixture("detail.json")["data"],
        floor_index=_fixture("floor_index.json")["data"],
        chapter_index=_fixture("chapter_index.json")["data"]["chapterIndex"],
        floors=_fixture("floors.json")["data"],
        comments_by_floor={
            962170: _fixture("comments_floor_962170.json")["data"]["records"],
        },
    )


class _ImmediateThread:
    def __init__(self, *, target, args, **kwargs):
        self.target = target
        self.args = args

    def start(self):
        self.target(*self.args)


class _FakeClient:
    fetch_calls = []

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return None

    def fetch_snapshot(
        self,
        book_id,
        *,
        progress=None,
        cancel=None,
        include_comments=True,
    ):
        self.fetch_calls.append((book_id, include_comments))
        if progress:
            progress("floors", 4, 4, "正在获取楼层 4/4")
        snapshot = _snapshot()
        if include_comments:
            return snapshot
        return GululuSnapshot(
            snapshot.detail,
            snapshot.floor_index,
            snapshot.chapter_index,
            snapshot.floors,
            {},
        )

    def fetch_comments(self, book_id, floor_ids):
        return {
            floor_id: _snapshot().comments_by_floor.get(floor_id, [])
            for floor_id in floor_ids
        }


class _BlockingClient(_FakeClient):
    entered = threading.Event()

    def fetch_snapshot(self, book_id, *, progress=None, cancel=None, include_comments=True):
        self.entered.set()
        while not cancel():
            time.sleep(0.01)
        raise GululuCancelled("cancelled")


class GululuServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.registered = []
        _FakeClient.fetch_calls = []
        self.service = GululuService(
            lambda path: self.registered.append(path) or "book-id",
            folder_picker=lambda: str(self.root / "exports"),
        )

    def tearDown(self):
        self.tmp.cleanup()

    def test_invalid_source_does_not_start(self):
        with self.assertRaises(ApiError):
            self.service.start("not-a-book")
        self.assertFalse(self.service.status()["running"])

    def test_invalid_image_mode_does_not_start(self):
        with self.assertRaises(ApiError) as cm:
            self.service.start("66905", "cached")
        self.assertIn("图片模式", cm.exception.message)
        self.assertFalse(self.service.status()["running"])

    def test_success_writes_atomically_and_registers(self):
        build_args = {}

        def fake_build(**kwargs):
            build_args.update(kwargs)
            path = Path(kwargs["output_path"])
            path.write_bytes(b"new epub")
            return GululuBuildResult(path, kwargs["image_mode"], 3, 2, ("one failed",))

        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=_FakeClient()), \
                patch("app.gululu_service.build_epub", side_effect=fake_build), \
                patch("app.gululu_service.threading.Thread", _ImmediateThread):
            result = self.service.start(
                "https://www.gululu.world/book/66905",
                "embedded",
            )

        self.assertTrue(result["ok"])
        target = self.root / "66905" / "post.epub"
        self.assertEqual(target.read_bytes(), b"new epub")
        self.assertFalse((self.root / "66905" / "post.epub.part").exists())
        self.assertEqual(self.registered, [str(target)])
        self.assertEqual(build_args["comments_by_floor"], {})
        self.assertEqual(build_args["image_mode"], "embedded")
        self.assertEqual(_FakeClient.fetch_calls, [(66905, False)])
        status = self.service.status()
        self.assertEqual(status["stage"], "done")
        self.assertEqual(status["book_id"], "book-id")
        self.assertEqual(status["image_mode"], "embedded")
        self.assertEqual(status["image_total"], 3)
        self.assertEqual(status["image_embedded"], 2)
        self.assertEqual(status["image_failed"], 1)
        self.assertIn("失败 1 张已显示占位", status["detail"])
        baseline = load_baseline(self.root / "66905" / "snapshot.json", 66905)
        self.assertIsInstance(baseline, BaselineOk)
        self.assertEqual(baseline.image_mode, "embedded")

    def test_incremental_update_fetches_only_new_floors_and_replaces_epub(self):
        folder = self.root / "66905"
        folder.mkdir(parents=True)
        target = folder / "post.epub"
        target.write_bytes(b"old epub")
        baseline_snapshot = _snapshot()
        write_baseline(folder / "snapshot.json", 66905, baseline_snapshot, "online")
        new_index_item = {"floorId": 999001, "floorNum": 5, "name": "新增楼层"}
        new_floor = {
            "id": 999001,
            "floorNum": 5,
            "name": "新增楼层",
            "paragraphContents": [{
                "type": "paragraph",
                "content": [{"type": "text", "text": "新增正文"}],
            }],
        }
        remote = GululuIndex(
            detail={**baseline_snapshot.detail, "name": "测试安科·更新"},
            floor_index=[*baseline_snapshot.floor_index, new_index_item],
            chapter_index=baseline_snapshot.chapter_index,
        )
        fetched = []

        class IncrementalClient(_FakeClient):
            def fetch_index(self, book_id, **kwargs):
                return remote

            def fetch_floors(self, book_id, floor_ids, **kwargs):
                fetched.append(list(floor_ids))
                return [new_floor]

        def fake_build(**kwargs):
            self.assertEqual([item["id"] for item in kwargs["floors"]][-1], 999001)
            path = Path(kwargs["output_path"])
            path.write_bytes(b"updated epub")
            return GululuBuildResult(path, kwargs["image_mode"], 0, 0)

        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=IncrementalClient()), \
                patch("app.gululu_service.build_epub", side_effect=fake_build), \
                patch("app.gululu_service.threading.Thread", _ImmediateThread):
            result = self.service.start_update("66905", "online")

        self.assertTrue(result["ok"])
        self.assertEqual(fetched, [[999001]])
        self.assertEqual(target.read_bytes(), b"updated epub")
        self.assertEqual(self.service.status()["action"], "update")
        self.assertEqual(self.service.status()["new_count"], 1)
        self.assertEqual(self.service.status()["detail"], "已更新 1 楼")
        baseline = load_baseline(folder / "snapshot.json", 66905)
        self.assertIsInstance(baseline, BaselineOk)
        self.assertEqual(len(baseline.snapshot.floors), 5)

    def test_incremental_update_without_new_floors_does_not_rebuild(self):
        folder = self.root / "66905"
        folder.mkdir(parents=True)
        target = folder / "post.epub"
        target.write_bytes(b"unchanged epub")
        baseline_snapshot = _snapshot()
        write_baseline(folder / "snapshot.json", 66905, baseline_snapshot, "online")
        remote = GululuIndex(
            baseline_snapshot.detail,
            baseline_snapshot.floor_index,
            baseline_snapshot.chapter_index,
        )
        case = self

        class CurrentClient(_FakeClient):
            def fetch_index(self, book_id, **kwargs):
                return remote

            def fetch_floors(self, book_id, floor_ids, **kwargs):
                case.fail("没有新增楼层时不应请求正文")

        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=CurrentClient()), \
                patch("app.gululu_service.build_epub") as build, \
                patch("app.gululu_service.threading.Thread", _ImmediateThread):
            result = self.service.start_update("66905", "online")

        self.assertTrue(result["ok"])
        build.assert_not_called()
        self.assertEqual(target.read_bytes(), b"unchanged epub")
        self.assertEqual(self.service.status()["new_count"], 0)
        self.assertEqual(self.service.status()["detail"], "已是最新")

    def test_legacy_epub_without_baseline_initializes_once_without_rebuild(self):
        folder = self.root / "66905"
        folder.mkdir(parents=True)
        target = folder / "post.epub"
        snapshot = _snapshot()
        build_epub(
            detail=snapshot.detail,
            floor_index=snapshot.floor_index,
            chapter_index=snapshot.chapter_index,
            floors=snapshot.floors,
            output_path=target,
            image_mode="none",
        )
        original = target.read_bytes()

        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=_FakeClient()), \
                patch("app.gululu_service.build_epub") as rebuild, \
                patch("app.gululu_service.threading.Thread", _ImmediateThread):
            result = self.service.start_update("66905", "none")

        self.assertTrue(result["ok"])
        rebuild.assert_not_called()
        self.assertEqual(target.read_bytes(), original)
        self.assertTrue(self.service.status()["baseline_initialized"])
        self.assertEqual(self.service.status()["detail"], "已是最新；已建立增量基线")
        self.assertIsInstance(load_baseline(folder / "snapshot.json", 66905), BaselineOk)

    def test_failed_registration_restores_previous_epub_and_cache(self):
        folder = self.root / "66905"
        folder.mkdir(parents=True)
        target = folder / "post.epub"
        partial = folder / "post.epub.part"
        target.write_bytes(b"old readable epub")
        partial.write_bytes(b"new invalid epub")
        record = types.SimpleNamespace(id="stable-id", path=str(target))

        class FakeShelf:
            def list_books(self):
                return [record]

        class FakeBooks:
            def __init__(self):
                self.closed = []

            def close(self, book_id):
                self.closed.append(book_id)

        books = FakeBooks()
        calls = []

        def register(path):
            calls.append(Path(path).read_bytes())
            if len(calls) == 1:
                raise RuntimeError("new book invalid")
            return "stable-id"

        with self.assertRaisesRegex(RuntimeError, "new book invalid"):
            replace_and_register(
                target,
                partial,
                "rollback-test",
                book_register=register,
                shelf=FakeShelf(),
                books=books,
            )

        self.assertEqual(target.read_bytes(), b"old readable epub")
        self.assertEqual(calls, [b"new invalid epub", b"old readable epub"])
        self.assertEqual(books.closed, ["stable-id", "stable-id"])
        self.assertFalse((folder / "post.epub.backup-rollback-test").exists())

    def test_failed_registration_reports_recovery_registration_failure(self):
        folder = self.root / "66905"
        folder.mkdir(parents=True)
        target = folder / "post.epub"
        partial = folder / "post.epub.part"
        target.write_bytes(b"old readable epub")
        partial.write_bytes(b"new invalid epub")
        record = types.SimpleNamespace(id="stable-id", path=str(target))

        class FakeShelf:
            def list_books(self):
                return [record]

        def register(path):
            if Path(path).read_bytes() == b"new invalid epub":
                raise RuntimeError("new book invalid")
            raise RuntimeError("old book registration failed")

        with self.assertRaisesRegex(
            RuntimeError,
            "new book invalid.*old book registration failed",
        ):
            replace_and_register(
                target,
                partial,
                "rollback-test",
                book_register=register,
                shelf=FakeShelf(),
                books=None,
            )

        self.assertEqual(target.read_bytes(), b"old readable epub")
        self.assertFalse((folder / "post.epub.backup-rollback-test").exists())

    def test_failure_preserves_previous_complete_epub(self):
        target = self.root / "66905" / "post.epub"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"old epub")

        def failing_build(**kwargs):
            Path(kwargs["output_path"]).write_bytes(b"partial")
            raise RuntimeError("build failed")

        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=_FakeClient()), \
                patch("app.gululu_service.build_epub", side_effect=failing_build), \
                patch("app.gululu_service.threading.Thread", _ImmediateThread):
            result = self.service.start("66905")

        self.assertTrue(result["ok"])
        self.assertEqual(target.read_bytes(), b"old epub")
        self.assertFalse((target.parent / "post.epub.part").exists())
        self.assertEqual(self.service.status()["stage"], "error")
        self.assertIn("build failed", self.service.status()["error"])

    def test_cancel_stops_fetch_and_releases_single_flight_lane(self):
        _BlockingClient.entered.clear()
        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=_BlockingClient()):
            self.assertTrue(self.service.start("66905")["ok"])
            self.assertTrue(_BlockingClient.entered.wait(2))
            with self.assertRaises(ApiError):
                self.service.start("66906")
            self.assertTrue(self.service.cancel()["ok"])
            deadline = time.time() + 2
            while self.service.status()["running"] and time.time() < deadline:
                time.sleep(0.01)

        self.assertEqual(self.service.status()["stage"], "cancelled")
        self.assertFalse((self.root / "66905" / "post.epub.part").exists())
        self.assertTrue(self.service._tasks.start(self.service.LANE, "next-task"))
        self.service._tasks.finish(self.service.LANE, "next-task")

    def test_api_delegates_start_status_and_cancel(self):
        fake = types.SimpleNamespace(
            start=lambda source, image_mode="online": {
                "ok": True, "source": source, "image_mode": image_mode,
            },
            start_export=lambda source, image_mode="online": {
                "ok": True, "export": source, "image_mode": image_mode,
            },
            start_update=lambda source, image_mode="online": {
                "ok": True, "update": source, "image_mode": image_mode,
            },
            get_comments=lambda source, floor_ids, refresh=False: {
                "ok": True,
                "source": source,
                "floor_ids": floor_ids,
                "refresh": refresh,
            },
            status=lambda: {"running": True, "stage": "floors"},
            cancel=lambda: {"ok": True},
        )
        covers = self.root / "covers"
        covers.mkdir()
        shelf = Shelf(self.root / "shelf.json", covers)
        shelf.load()
        progress = ProgressStore(self.root / "progress.json")
        progress.load()
        settings = Settings(self.root / "settings.json")
        settings.load()
        api = Api(
            books=BookManager(),
            shelf=shelf,
            progress=progress,
            settings=settings,
            search=SearchService(),
            gululu_service=fake,
        )

        imported = api.gululu_start_import("66905", "embedded")
        self.assertEqual(imported["source"], "66905")
        self.assertEqual(imported["image_mode"], "embedded")
        exported = api.gululu_start_export("66905", "none")
        self.assertEqual(exported["export"], "66905")
        self.assertEqual(exported["image_mode"], "none")
        updated = api.gululu_start_update("66905", "online")
        self.assertEqual(updated["update"], "66905")
        comments = api.gululu_get_comments(66905, [962170], True)
        self.assertEqual(comments["floor_ids"], [962170])
        self.assertTrue(comments["refresh"])
        self.assertTrue(api.gululu_import_status()["running"])
        self.assertTrue(api.gululu_cancel()["ok"])

    def test_online_comments_use_fresh_cache_and_explicit_stale_fallback(self):
        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=_FakeClient()):
            first = self.service.get_comments(66905, [962170])
            second = self.service.get_comments(66905, [962170])

        self.assertTrue(first["ok"])
        self.assertFalse(first["floors"][0]["cached"])
        self.assertTrue(second["floors"][0]["cached"])
        comment = second["floors"][0]["comments"][0]
        self.assertEqual(comment["author"], "评论者甲")
        self.assertNotIn("fromUser", comment)

        class _FailingCommentClient(_FakeClient):
            def fetch_comments(self, book_id, floor_ids):
                raise RuntimeError("offline")

        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=_FailingCommentClient()):
            stale = self.service.get_comments(66905, [962170], refresh=True)

        self.assertTrue(stale["ok"])
        self.assertTrue(stale["floors"][0]["stale"])
        self.assertIn("offline", stale["floors"][0]["error"])

    def test_full_export_fetches_comments_without_registering_book(self):
        build_args = {}

        def fake_build(**kwargs):
            build_args.update(kwargs)
            path = Path(kwargs["output_path"])
            path.write_bytes(b"full epub")
            return GululuBuildResult(path, kwargs["image_mode"], 0, 0)

        with patch("app.gululu_service.GululuClient", return_value=_FakeClient()), \
                patch("app.gululu_service.build_epub", side_effect=fake_build), \
                patch("app.gululu_service.threading.Thread", _ImmediateThread):
            result = self.service.start_export("66905")

        self.assertTrue(result["ok"])
        self.assertEqual(_FakeClient.fetch_calls, [(66905, True)])
        self.assertEqual(
            build_args["comments_by_floor"][962170][0]["content"],
            "段落评论",
        )
        self.assertEqual(self.registered, [])
        exported = self.root / "exports" / "gululu-66905-comments.epub"
        self.assertEqual(exported.read_bytes(), b"full epub")
        self.assertEqual(self.service.status()["action"], "export")

    def test_online_comments_without_cache_return_explicit_failure(self):
        class _OfflineClient(_FakeClient):
            def fetch_comments(self, book_id, floor_ids):
                raise RuntimeError("network unavailable")

        with patch("app.gululu_service.gululu_library_dir", return_value=self.root), \
                patch("app.gululu_service.GululuClient", return_value=_OfflineClient()):
            result = self.service.get_comments(66905, [962170])

        self.assertFalse(result["ok"])
        self.assertEqual(result["floors"][0]["comments"], [])
        self.assertIn("network unavailable", result["floors"][0]["error"])


if __name__ == "__main__":
    unittest.main()
