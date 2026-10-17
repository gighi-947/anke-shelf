"""进书更新检查：状态存储、三态分派与批量扫描（不依赖真实网络）。

NGA 侧经 app.update_check._import_nga / load_nga_config 注入桩（对齐
test_nga_service 的既有做法）；骨碌碌侧经 gululu_client_factory 注入
假 fetch_index（对齐 Android BookUpdateCheckerGululuTest）。
"""
import json
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from app.gululu_epub import GululuIndex, GululuSnapshot
from app.gululu_update import write_baseline
from app.shelf import BookRecord, Shelf
from app.update_check import UpdateCheckService
from app.update_checks import UpdateCheckStore

UNAVAILABLE = {"state": "unavailable", "new_floors": 0, "approximate": False}


def _snapshot(floor_ids=(101, 102)) -> GululuSnapshot:
    return GululuSnapshot(
        detail={"bookId": 12, "name": "测试"},
        floor_index=[{"floorId": fid, "floorNum": i + 1} for i, fid in enumerate(floor_ids)],
        chapter_index=[],
        floors=[
            {"id": fid, "floorNum": i + 1, "paragraphContents": []}
            for i, fid in enumerate(floor_ids)
        ],
    )


def _remote_index(floor_ids=(101, 102, 103)) -> GululuIndex:
    return GululuIndex(
        detail={"bookId": 12, "name": "测试新版"},
        floor_index=[{"floorId": fid, "floorNum": i + 1} for i, fid in enumerate(floor_ids)],
        chapter_index=[],
    )


class _FakeGululuClient:
    def __init__(self, remote=None, error=None):
        self._remote = remote
        self._error = error

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def fetch_index(self, source_id):
        if self._error is not None:
            raise self._error
        return self._remote


class UpdateCheckStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "update_checks.json"

    def tearDown(self):
        self.tmp.cleanup()

    def test_defaults_and_persistence_roundtrip(self):
        store = UpdateCheckStore(self.path)
        self.assertTrue(store.is_auto_check_enabled("b1"))  # 未登记默认开
        store.set_auto_check("b1", False)
        store.set_available("b2", 7)
        self.assertFalse(store.is_auto_check_enabled("b1"))
        self.assertEqual(store.available_snapshot(), {"b2": 7})

        reloaded = UpdateCheckStore(self.path)
        self.assertFalse(reloaded.is_auto_check_enabled("b1"))
        self.assertEqual(reloaded.available_snapshot(), {"b2": 7})
        data = json.loads(self.path.read_text(encoding="utf-8"))
        self.assertEqual(
            data, {"version": 1, "auto_check": {"b1": False}, "available": {"b2": 7}}
        )

    def test_disable_clears_available_entry(self):
        store = UpdateCheckStore(self.path)
        store.set_available("b1", 5)
        store.set_auto_check("b1", False)
        self.assertEqual(store.available_snapshot(), {})
        reloaded = UpdateCheckStore(self.path)
        self.assertEqual(reloaded.available_snapshot(), {})

    def test_corrupt_file_falls_back_to_empty(self):
        self.path.write_text("{broken json", encoding="utf-8")
        store = UpdateCheckStore(self.path)
        self.assertEqual(store.available_snapshot(), {})
        self.assertTrue(store.is_auto_check_enabled("b1"))
        self.assertEqual(store.revision, 0)

    def test_set_available_nonpositive_deletes_entry(self):
        store = UpdateCheckStore(self.path)
        store.set_available("b1", 3)
        store.set_available("b1", 0)
        self.assertEqual(store.available_snapshot(), {})
        # 无条目时的清除是幂等无写（revision 不动）
        rev = store.revision
        store.set_available("b1", 0)
        store.set_available("", 5)  # 空 book_id 忽略
        self.assertEqual(store.revision, rev)

    def test_revision_increments_only_on_actual_writes(self):
        store = UpdateCheckStore(self.path)
        self.assertEqual(store.revision, 0)
        store.set_available("b1", 5)
        self.assertEqual(store.revision, 1)
        store.set_available("b1", 5)  # 值未变：跳过
        self.assertEqual(store.revision, 1)
        store.set_available("b1", 6)
        self.assertEqual(store.revision, 2)
        store.set_auto_check("b1", True)  # 默认开再开：无变化跳过
        self.assertEqual(store.revision, 2)

    def test_session_throttle_is_memory_only(self):
        store = UpdateCheckStore(self.path)
        self.assertFalse(store.checked_this_session("b1"))
        store.mark_checked("b1")
        self.assertTrue(store.checked_this_session("b1"))
        reloaded = UpdateCheckStore(self.path)
        self.assertFalse(reloaded.checked_this_session("b1"))


class UpdateCheckServiceTest(unittest.TestCase):
    """check_book 三态分派 + check_all 批量扫描（网络全部注入）。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.nga_lib = self.root / "nga_library"
        self.gululu_lib = self.root / "gululu_library"
        self.nga_lib.mkdir()
        self.gululu_lib.mkdir()
        (self.root / "covers").mkdir()
        self.shelf = Shelf(self.root / "shelf.json", self.root / "covers")
        self.shelf.load()
        self.store = UpdateCheckStore(self.root / "update_checks.json")
        self.svc = UpdateCheckService(
            self.shelf, self.store, gululu_client_factory=lambda: _FakeGululuClient()
        )
        self.patches = [
            patch("app.update_check.nga_library_dir", return_value=self.nga_lib),
            patch("app.update_check.gululu_library_dir", return_value=self.gululu_lib),
        ]
        for p in self.patches:
            p.start()
        self.addCleanup(lambda: [p.stop() for p in self.patches])

    def tearDown(self):
        self.tmp.cleanup()

    def _add_book(self, book_id: str, path: str, nga_tid: int = 0) -> str:
        rec = BookRecord(id=book_id, path=path, title=book_id, nga_tid=nga_tid)
        self.shelf.upsert(rec)
        self.shelf.save()
        return book_id

    def _gululu_book(self, source_id: int = 12) -> str:
        folder = self.gululu_lib / str(source_id)
        folder.mkdir(exist_ok=True)
        write_baseline(folder / "snapshot.json", source_id, _snapshot(), "online")
        return self._add_book("g1", str(folder / "post.epub"))

    # ---------- 分派 ----------

    def test_non_dual_source_book_is_unavailable(self):
        self._add_book("plain", str(self.root / "plain.epub"))
        result = self.svc.check_book("plain")
        self.assertEqual(result, UNAVAILABLE)
        self.assertEqual(self.store.available_snapshot(), {})
        self.assertFalse(self.store.checked_this_session("plain"))

    def test_unknown_book_is_unavailable(self):
        self.assertEqual(self.svc.check_book("missing"), UNAVAILABLE)

    # ---------- 骨碌碌 ----------

    def _gululu_svc(self, client) -> UpdateCheckService:
        return UpdateCheckService(
            self.shelf, self.store, gululu_client_factory=lambda: client
        )

    def test_gululu_available_is_exact_and_writes_badge(self):
        book_id = self._gululu_book()
        svc = self._gululu_svc(_FakeGululuClient(remote=_remote_index()))
        result = svc.check_book(book_id)
        self.assertEqual(result, {"state": "available", "new_floors": 1, "approximate": False})
        self.assertEqual(self.store.available_snapshot(), {"g1": 1})
        self.assertTrue(self.store.checked_this_session(book_id))

    def test_gululu_none_clears_stale_badge(self):
        book_id = self._gululu_book()
        self.store.set_available("g1", 9)
        svc = self._gululu_svc(_FakeGululuClient(remote=_remote_index((101, 102))))
        result = svc.check_book(book_id)
        self.assertEqual(result, {"state": "none", "new_floors": 0, "approximate": False})
        self.assertEqual(self.store.available_snapshot(), {})

    def test_gululu_missing_baseline_is_unavailable_without_store_touch(self):
        # 旧书无增量基线：走手动更新迁移，不提示
        folder = self.gululu_lib / "13"
        folder.mkdir()
        self._add_book("g2", str(folder / "post.epub"))
        self.store.set_available("g2", 9)
        svc = self._gululu_svc(_FakeGululuClient(remote=_remote_index()))
        result = svc.check_book("g2")
        self.assertEqual(result, UNAVAILABLE)
        self.assertEqual(self.store.available_snapshot(), {"g2": 9})  # 瞬态失败不清徽标
        self.assertFalse(self.store.checked_this_session("g2"))

    def test_gululu_conflict_and_network_error_are_unavailable(self):
        book_id = self._gululu_book()
        conflict = self._gululu_svc(
            _FakeGululuClient(remote=_remote_index((102, 101)))  # 旧楼重排
        )
        self.assertEqual(conflict.check_book(book_id), UNAVAILABLE)
        from app.gululu_client import GululuApiError

        failing = self._gululu_svc(_FakeGululuClient(error=GululuApiError("网络失败")))
        self.assertEqual(failing.check_book(book_id), UNAVAILABLE)
        self.assertEqual(self.store.available_snapshot(), {})

    # ---------- NGA ----------

    def _nga_book(self, folder: str = "123(45)", process_ini: str | None = None) -> str:
        target = self.nga_lib / folder
        target.mkdir(parents=True, exist_ok=True)
        if process_ini is not None:
            (target / "process.ini").write_text(process_ini, encoding="utf-8")
        return self._add_book("nga1", str(target / "book"), nga_tid=123)

    def test_nga_unconfigured_credentials_is_unavailable(self):
        self._nga_book(process_ini="[local]\nmax_floor = 40\nmax_page = 3\n")
        with patch("app.update_check.load_nga_config", return_value={"configured": False}):
            result = self.svc.check_book("nga1")
        self.assertEqual(result, UNAVAILABLE)
        self.assertEqual(self.store.available_snapshot(), {})

    def test_nga_missing_process_ini_is_unavailable(self):
        self._nga_book()  # 有书目录但无 process.ini
        with patch(
            "app.update_check.load_nga_config",
            return_value={"configured": True, "uid": "1", "cid": "2", "ua": "UA"},
        ):
            result = self.svc.check_book("nga1")
        self.assertEqual(result, UNAVAILABLE)

    def _fake_nga_import(self, remote_total_page: int, page_lous: list[int]):
        pulled = []

        class FakeTiezi:
            def __init__(self, tid, author_id):
                self.tid = tid
                self.author_id = author_id
                self.floors = []
                self.web_max_page = 0

        nga_mod = types.SimpleNamespace(
            init_nga=lambda client, cfg: None,
            page=lambda tiezi, page_no: (
                pulled.append(page_no),
                setattr(tiezi, "web_max_page", remote_total_page),
                setattr(tiezi, "floors", [types.SimpleNamespace(lou=l) for l in page_lous]),
            ),
        )
        client_mod = types.SimpleNamespace(
            NgaClient=lambda cfg: types.SimpleNamespace(close=lambda: None)
        )
        config_mod = types.SimpleNamespace(
            load_config=lambda path: types.SimpleNamespace(
                thread=1, page_download_limit=0, max_floors=0, no_images=True,
                no_media=True, epub_enabled=False, output_path=str(self.nga_lib),
            )
        )
        return (client_mod, config_mod, nga_mod, FakeTiezi), pulled

    def _configured(self):
        return patch(
            "app.update_check.load_nga_config",
            return_value={"configured": True, "uid": "1", "cid": "2", "ua": "UA"},
        )

    def test_nga_estimates_from_breakpoint_page(self):
        # 断点：max_floor=40, max_page=3；断点页有 42/45 楼、远端共 5 页
        self._nga_book(process_ini="[local]\nmax_floor = 40\nmax_page = 3\n")
        fake, pulled = self._fake_nga_import(5, [40, 41, 42, 45, -1])
        with self._configured(), patch("app.update_check._import_nga", return_value=fake):
            result = self.svc.check_book("nga1")
        self.assertEqual(pulled, [3])  # 只拉断点页
        # 页内 lou>40：41/42/45 共 3 楼 + 超出 (5-3) 页 × 20 = 43
        self.assertEqual(
            result, {"state": "available", "new_floors": 43, "approximate": True}
        )
        self.assertEqual(self.store.available_snapshot(), {"nga1": 43})
        self.assertTrue(self.store.checked_this_session("nga1"))

    def test_nga_up_to_date_is_none(self):
        self._nga_book(process_ini="[local]\nmax_floor = 45\nmax_page = 3\n")
        fake, pulled = self._fake_nga_import(3, [41, 45])
        with self._configured(), patch("app.update_check._import_nga", return_value=fake):
            result = self.svc.check_book("nga1")
        self.assertEqual(pulled, [3])
        self.assertEqual(result, {"state": "none", "new_floors": 0, "approximate": False})

    def test_nga_missing_page_field_estimates_page_from_max_floor(self):
        # process.ini 缺 max_page：按 max_floor//20+1 估页（40//20+1=3）
        self._nga_book(process_ini="[local]\nmax_floor = 40\n")
        fake, pulled = self._fake_nga_import(4, [41])
        with self._configured(), patch("app.update_check._import_nga", return_value=fake):
            result = self.svc.check_book("nga1")
        self.assertEqual(pulled, [3])
        # 页内 1 楼 + (4-3)×20 = 21
        self.assertEqual(result["new_floors"], 21)

    def test_nga_real_page_parse_never_writes_book_directory(self):
        """走真实 ngapost2md nga.page/analyze_floors（仅 client 换桩），断言只读。"""
        from app.update_check import _import_nga as real_import

        self._nga_book(process_ini="[local]\nmax_floor = 40\nmax_page = 3\n")
        folder = self.nga_lib / "123(45)"
        config_ini = self.root / "nga_config.ini"
        config_ini.write_text(
            "[network]\nbase_url = https://bbs.nga.cn\nua = UA\n"
            "ngaPassportUid = 1\nngaPassportCid = 2\n",
            encoding="utf-8",
        )
        _client_mod, config_mod, nga_mod, Tiezi = real_import()

        def fake_post_form(url, data):
            # 断点页（第 3 页）响应：两楼新内容（41/42），远端共 5 页
            floor = {
                "pid": 1001,
                "postdatetimestamp": 1700000000,
                "author": {"username": "u", "uid": 7},
                "content": "x",
                "vote_good": 0,
            }
            return {
                "code": 0,
                "tsubject": "标题",
                "forum_name": "版面",
                "tauthor": "作者",
                "tauthorid": 45,
                "totalPage": 5,
                "vrows": 61,
                "result": [dict(floor, lou=41), dict(floor, lou=42)],
            }

        class FakeClient:
            def __init__(self, cfg):
                pass

            def post_form(self, url, data):
                return fake_post_form(url, data)

            def close(self):
                pass

        def fake_import():
            return (
                types.SimpleNamespace(NgaClient=FakeClient),
                config_mod,
                nga_mod,
                Tiezi,
            )

        def snapshot():
            return sorted(
                (p.name, p.stat().st_size, p.stat().st_mtime_ns)
                for p in folder.iterdir()
            )

        before = snapshot()
        with self._configured(), \
                patch("app.update_check.ensure_nga_config", return_value=config_ini), \
                patch("app.update_check._import_nga", side_effect=fake_import):
            result = self.svc.check_book("nga1")
        self.assertEqual(snapshot(), before)  # 只读探测：书目录零改动
        # 页内 2 楼（41/42 > max_floor=40）+ 超出 (5-3) 页 × 20 = 42
        self.assertEqual(result, {"state": "available", "new_floors": 42, "approximate": True})

    # ---------- check_all ----------

    def _wait_sweep_done(self, svc: UpdateCheckService, timeout: float = 5.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline and svc._sweep_running:
            time.sleep(0.01)
        self.assertFalse(svc._sweep_running, "批量检查线程未在时限内结束")

    def test_check_all_filters_and_writes_store(self):
        self._nga_book(process_ini=None)  # NGA 侧 unavailable（无断点），仍会检查
        self._gululu_book()
        self._add_book("plain", str(self.root / "plain.epub"))
        self._add_book("off", str(self.gululu_lib / "12" / "post.epub"))
        self.store.set_auto_check("off", False)
        self._add_book("done", str(self.nga_lib / "123(45)" / "book"), nga_tid=123)
        self.store.mark_checked("done")

        results = {
            "g1": {"state": "available", "new_floors": 7, "approximate": False},
            "nga1": {"state": "none", "new_floors": 0, "approximate": False},
        }
        calls = []

        def fake_check(book_id):
            calls.append(book_id)
            result = results.get(book_id, UNAVAILABLE)
            # 模拟真实 check_book 的落库语义（available/none 写 store）
            if result["state"] in ("available", "none"):
                self.store.set_available(book_id, result["new_floors"])
                self.store.mark_checked(book_id)
            return result

        with patch.object(self.svc, "check_book", side_effect=fake_check):
            first = self.svc.check_all()
            self.assertTrue(first["started"])
            self._wait_sweep_done(self.svc)
        self.assertEqual(sorted(calls), ["g1", "nga1"])  # 过滤：非可更新/关/已查
        self.assertEqual(self.store.available_snapshot(), {"g1": 7})  # none 不留条目
        self.assertTrue(self.store.checked_this_session("g1"))

    def test_check_all_reentry_returns_not_started(self):
        self._gululu_book()
        started_evt = threading.Event()
        release_evt = threading.Event()
        calls = []

        def fake_check(book_id):
            calls.append(book_id)
            if len(calls) == 1:
                started_evt.set()
                self.assertTrue(release_evt.wait(5))
            return {"state": "none", "new_floors": 0, "approximate": False}

        with patch.object(self.svc, "check_book", side_effect=fake_check):
            self.assertTrue(self.svc.check_all()["started"])
            self.assertTrue(started_evt.wait(2))
            self.assertFalse(self.svc.check_all()["started"])  # 已在跑
            release_evt.set()
            self._wait_sweep_done(self.svc)
        self.assertEqual(calls, ["g1"])


class UpdateApiContractTest(unittest.TestCase):
    """4 个端点的注册名与返回形状（与前端 api-client.js 接线对齐）。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "covers").mkdir()
        self.shelf = Shelf(self.root / "shelf.json", self.root / "covers")
        self.shelf.load()

    def tearDown(self):
        self.tmp.cleanup()

    def _make_api(self):
        import threading

        from app.annotations import AnnotationStore
        from app.api import Api
        from app.book_manager import BookManager
        from app.export_service import ExportService
        from app.floor_export_service import FloorExportService
        from app.gululu_service import GululuService
        from app.nga_login import NgaLoginController
        from app.nga_service import NgaService
        from app.search import SearchService
        from app.settings import Settings
        from app.shelf import ProgressStore
        from app.stats import StatsStore

        return Api(
            books=BookManager(),
            shelf=self.shelf,
            progress=ProgressStore(self.root / "progress.json"),
            settings=Settings(self.root / "settings.json"),
            search=SearchService(),
            annotations=AnnotationStore(self.root / "annotations.json"),
            stats=StatsStore(self.root / "statistics.json"),
            nga_service=NgaService(lambda _path: ""),
            export_service=ExportService(self.shelf),
            gululu_service=GululuService(lambda _path: ""),
            floor_export_service=FloorExportService(self.shelf, BookManager()),
            update_check_service=UpdateCheckService(
                self.shelf, UpdateCheckStore(self.root / "update_checks.json")
            ),
            frontend_ready=threading.Event(),
            window_toggle=lambda _entering: None,
            nga_login=NgaLoginController(),
        )

    def test_endpoint_names_registered(self):
        from app.api import api_manifest

        names = {item["name"] for item in api_manifest()}
        self.assertLessEqual(
            {"check_book_update", "get_update_badges", "set_auto_check", "check_all_updates"},
            names,
        )

    def test_check_book_update_missing_book_is_unavailable(self):
        api = self._make_api()
        self.assertEqual(
            api.check_book_update("missing"),
            {"state": "unavailable", "new_floors": 0, "approximate": False},
        )

    def test_get_update_badges_shape_and_set_auto_check_roundtrip(self):
        api = self._make_api()
        self.assertEqual(
            api.get_update_badges(), {"badges": {}, "auto_check": {}, "revision": 0}
        )
        self.assertEqual(api.set_auto_check("b1", False), {"ok": True})
        badges = api.get_update_badges()
        self.assertEqual(badges["auto_check"], {"b1": False})
        self.assertEqual(badges["badges"], {})
        self.assertEqual(badges["revision"], 1)

    def test_check_all_updates_returns_started(self):
        api = self._make_api()  # 空书架：扫描线程无书可查，不发网络
        result = api.check_all_updates()
        self.assertEqual(result, {"started": True})


if __name__ == "__main__":
    unittest.main()
