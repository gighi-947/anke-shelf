"""原生增量书容器测试：写入/读取/追加/重载/EPUB 重建。"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT / "ngapost2md-python"))

from ngapost2md.models import Floor, Tiezi  # noqa: E402

from app.book_manager import BookManager  # noqa: E402
from app.native_book import (  # noqa: E402
    NativeBook,
    append_container,
    is_native_dir,
    load_floors,
    load_meta,
    native_dir_for,
    rebuild_epub_for_native,
    write_container,
)


def _floor(lou: int, pid: int, text: str) -> Floor:
    return Floor(
        lou=lou, pid=pid, timestamp=0, username="u", user_id=1,
        like_num=0, content=text, raw_content=f"<p>{text}</p>",
    )


class NativeBookTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.patcher = patch("app.native_book.nga_library_dir", return_value=self.root)
        self.patcher.start()

    def tearDown(self):
        self.patcher.stop()
        self.tmp.cleanup()

    def _tiezi(self, n_floors: int) -> Tiezi:
        floors = [_floor(1, 0, "main")]
        for lou in range(2, n_floors + 1):
            floors.append(_floor(lou, 1000 + lou, f"floor-{lou}"))
        return Tiezi(
            tid=123, author_id=0, title="标题", username="作者",
            folder_name="123", floors=floors,
            created_time="2026-01-01T00:00:00+08:00",
            updated_time="2026-01-01T00:00:00+08:00",
        )

    def test_write_read_append(self):
        tiezi = self._tiezi(25)
        native_dir = write_container("123", tiezi, tiezi.floors, 20, "online", "light", "bookid123")
        self.assertTrue(is_native_dir(native_dir))
        meta = load_meta(native_dir)
        self.assertEqual(len(meta["chapters"]), 3)  # 主楼 + 20 楼 + 4 楼
        self.assertEqual(meta["last_lou"], 25)

        book = NativeBook(str(native_dir)).open()
        self.assertEqual(book.id, "bookid123")
        self.assertEqual(len(book.chapters), 3)
        self.assertIn("floor-25", book.chapter_text(2))
        old_ch2 = book.chapter_text(2)[:200]

        # 追加 26~30：应填入最后一个章节（余 16 空位）
        new_floors = [_floor(lou, 2000 + lou, f"floor-{lou}") for lou in range(26, 31)]
        count = append_container("123", new_floors, 20, "online", "light", "bookid123")
        self.assertEqual(count, 5)
        meta = load_meta(native_dir)
        self.assertEqual(len(meta["chapters"]), 3)
        self.assertEqual(meta["chapters"][-1]["floor_count"], 9)
        self.assertEqual(len(load_floors(native_dir)), 30)

        # 追加 31~60：填满后开新章
        new_floors2 = [_floor(lou, 3000 + lou, f"floor-{lou}") for lou in range(31, 61)]
        count2 = append_container("123", new_floors2, 20, "online", "light", "bookid123")
        self.assertEqual(count2, 30)
        meta = load_meta(native_dir)
        self.assertEqual(len(meta["chapters"]), 4)
        self.assertEqual(meta["chapters"][-1]["floor_count"], 19)

        book2 = NativeBook(str(native_dir)).open()
        self.assertTrue(book2.chapter_text(2).startswith(old_ch2))
        self.assertIn("floor-60", book2.chapter_text(3))

    def test_book_manager_registers_native(self):
        tiezi = self._tiezi(3)
        native_dir = write_container("123", tiezi, tiezi.floors, 20, "online", "light", "bookid123")
        books = BookManager()
        book = books.register(str(native_dir))
        self.assertEqual(book.id, "bookid123")
        self.assertEqual(len(book.chapters), 2)
        books.close_all()

    def test_rebuild_epub(self):
        tiezi = self._tiezi(3)
        write_container("123", tiezi, tiezi.floors, 20, "online", "light", "bookid123")
        with patch("app.paths.data_dir", return_value=self.root), \
                patch("app.nga_config.data_dir", return_value=self.root), \
                patch("app.nga_config._candidate_source", return_value=Path()):
            epub_path = rebuild_epub_for_native("123")
        self.assertTrue(epub_path.is_file())
        self.assertEqual(epub_path.name, "post.epub")


if __name__ == "__main__":
    unittest.main()
