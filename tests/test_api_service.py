"""API 服务层单元测试：重启后按书架路径重载书籍、清除 NGA 配置。"""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app.annotations import AnnotationStore
from app.api import Api
from app.book_manager import BookManager
from app.nga_config import load_nga_config
from app.search import SearchService
from app.settings import Settings
from app.shelf import BookRecord, ProgressStore, Shelf
from app.stats import StatsStore

SAMPLE = Path(__file__).parent / "sample" / "sample_nav3.epub"


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

    def _make_api(self, books: BookManager) -> Api:
        return Api(
            books=books,
            shelf=self.shelf,
            progress=self.progress,
            settings=self.settings,
            search=self.search,
            annotations=self.ann,
            stats=self.stats,
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
        data = api.open_book("0" * 32)
        self.assertIn("error", data)

    def test_get_version(self):
        api = self._make_api(BookManager())
        self.assertEqual(api.get_version(), "1.0.0")

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
