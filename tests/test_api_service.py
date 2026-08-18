"""API 服务层单元测试：重启后按书架路径重载书籍、清除 NGA 配置。"""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app.annotations import AnnotationStore
from app.api import Api
from app.book_manager import BookManager
from app.errors import ApiError
from app.nga_config import load_nga_config
from app.search import SearchService
from app.settings import Settings
from app.shelf import BookRecord, ProgressStore, Shelf
from app.stats import StatsStore

SAMPLE = Path(__file__).parent / "sample" / "sample_nav3.epub"
CRYPTOJS_CIPHER = (
    "U2FsdGVkX1+5H7Gx48HorblxhULBPlXtE11y6qTOMa4caaekW4/fZFQlbBlH2/p8"
)


class ApiServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.covers = self.root / "covers"
        self.covers.mkdir()
        self.shelf = Shelf(self.root / "shelf.json", self.covers)
        self.shelf.load()
        self.progress = ProgressStore(self.root / "progress.json")
        self.progress.load()
        self.settings = Settings(self.root / "settings.json")
        self.settings.load()
        self.search = SearchService()
        self.ann = AnnotationStore(self.root / "annotations.json")
        self.ann.load()
        self.stats = StatsStore(self.root / "statistics.json")
        self.stats.load()

    def tearDown(self):
        self.tmp.cleanup()

    def _make_api(self, books: BookManager, window_toggle=None) -> Api:
        return Api(
            books=books,
            shelf=self.shelf,
            progress=self.progress,
            settings=self.settings,
            search=self.search,
            annotations=self.ann,
            stats=self.stats,
            window_toggle=window_toggle,
        )

    def _add_shelf_record(self, books: BookManager, book_id: str, path: str):
        book = books.register(path)
        rec = BookRecord(
            id=book.id,
            path=book.path,
            title=book.title,
            author=book.author,
            language=book.language,
            chapter_count=len(book.chapters),
            cover_rel=self.shelf.extract_cover(book),
        )
        self.shelf.upsert(rec)
        self.shelf.save()
        return book.id

    def test_open_book_reloads_after_restart(self):
        """重启后 BookManager 为空，open_book 应按书架路径重新注册。"""
        first_books = BookManager()
        book_id = self._add_shelf_record(first_books, "", str(SAMPLE))
        first_books.close_all()

        # 模拟重启：全新的 BookManager，书架记录已持久化
        fresh_books = BookManager()
        api = self._make_api(fresh_books)
        data = api.open_book(book_id)
        self.assertNotIn("error", data)
        self.assertEqual(data["id"], book_id)
        self.assertTrue(fresh_books.has(book_id))

    def test_open_book_missing_shelf_record(self):
        api = self._make_api(BookManager())
        with self.assertRaises(ApiError) as cm:
            api.open_book("0" * 32)
        self.assertEqual(cm.exception.code, "BOOK_NOT_FOUND")

    def test_get_stats_lists_books_with_titles(self):
        books = BookManager()
        book_id = self._add_shelf_record(books, "", str(SAMPLE))
        self.stats.record_reading(book_id, 300, 2)
        api = self._make_api(books)

        data = api.get_stats()
        self.assertIn("books", data)
        self.assertIn("global", data)
        self.assertEqual(len(data["books"]), 1)
        rec = self.shelf.get(book_id)
        self.assertEqual(data["books"][0]["id"], book_id)
        self.assertEqual(data["books"][0]["title"], rec.title)
        self.assertEqual(data["books"][0]["stats"]["total_seconds"], 300)
        self.assertEqual(data["global"]["total_seconds"], 300)

        single = api.get_stats(book_id)
        self.assertEqual(single["book"]["total_seconds"], 300)
        self.assertEqual(single["global"]["total_seconds"], 300)

    def test_get_version(self):
        api = self._make_api(BookManager())
        self.assertEqual(api.get_version(), "1.4.0")

    def test_open_book_error_code(self):
        api = self._make_api(BookManager())
        with self.assertRaises(ApiError) as cm:
            api.open_book("missing-book")
        self.assertEqual(cm.exception.code, "BOOK_NOT_FOUND")
        self.assertIn("未加载", cm.exception.message)

    def test_rename_book_updates_shelf_and_returns_record(self):
        books = BookManager()
        book_id = self._add_shelf_record(books, "", str(SAMPLE))
        api = self._make_api(books)

        data = api.rename_book(book_id, "  新标题  ")
        self.assertNotIn("error", data)
        self.assertEqual(data["title"], "新标题")
        rec = self.shelf.get(book_id)
        self.assertEqual(rec.title, "新标题")

    def test_rename_book_empty_or_same_is_noop(self):
        books = BookManager()
        book_id = self._add_shelf_record(books, "", str(SAMPLE))
        original = self.shelf.get(book_id).title
        api = self._make_api(books)

        api.rename_book(book_id, "   ")
        self.assertEqual(self.shelf.get(book_id).title, original)
        api.rename_book(book_id, original)
        self.assertEqual(self.shelf.get(book_id).title, original)

    def test_rename_book_not_found(self):
        api = self._make_api(BookManager())
        with self.assertRaises(ApiError) as cm:
            api.rename_book("0" * 32, "新标题")
        self.assertEqual(cm.exception.code, "BOOK_NOT_FOUND")

    def test_toggle_fullscreen(self):
        calls = []
        api = self._make_api(BookManager(), window_toggle=lambda entering: calls.append(entering))
        self.assertTrue(api.toggle_fullscreen()["ok"])
        self.assertEqual(calls, [True])
        self.assertTrue(api.fullscreen)
        self.assertTrue(api.toggle_fullscreen()["ok"])
        self.assertEqual(calls, [True, False])
        self.assertFalse(api.fullscreen)

        api2 = self._make_api(BookManager())
        with self.assertRaises(ApiError) as cm:
            api2.toggle_fullscreen()
        self.assertEqual(cm.exception.code, "SERVICE_UNAVAILABLE")

    def test_gululu_secret_decrypt_is_explicit(self):
        api = self._make_api(BookManager())
        result = api.gululu_decrypt_secret(
            63299,
            "炉心",
            CRYPTOJS_CIPHER,
            "薪火-63299",
        )
        self.assertEqual(result["plaintext"], "风雪之后，炉火仍在。")
        with self.assertRaises(ApiError) as cm:
            api.gululu_decrypt_secret(63299, "炉心", CRYPTOJS_CIPHER, "错误密码")
        self.assertIn("密码错误或秘密数据损坏", cm.exception.message)

    def test_nga_clear_config_removes_credentials(self):
        with patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config.nga_config_path",
                      return_value=self.root / "nga_config.ini"):
            from app.api import Api as ApiCls

            api = ApiCls(
                books=BookManager(),
                shelf=self.shelf,
                progress=self.progress,
                settings=self.settings,
                search=self.search,
            )
            api.nga_save_config({"uid": "123456", "cid": "secret", "ua": "UA"})
            self.assertTrue(load_nga_config()["configured"])
            cfg = api.nga_clear_config()
            self.assertFalse(cfg["configured"])
            self.assertEqual(cfg["uid"], "")


if __name__ == "__main__":
    unittest.main()
