"""B2 领域模型：Position 值对象与 Book Protocol（EpubBook / NativeBook 双实现）。"""
import tempfile
import unittest
from pathlib import Path

from app.book_manager import BookManager
from app.domain import Book, Position
from app.epub import EpubBook
from app.native_book import NativeBook
from app.shelf import ProgressStore

PROJECT = Path(__file__).resolve().parent.parent
SAMPLE = PROJECT / "tests" / "sample" / "sample_nav3.epub"
FIXTURE = PROJECT / "contracts" / "fixtures" / "native-book" / "basic-nga"


class PositionTest(unittest.TestCase):
    def test_frozen_value_object(self):
        p = Position(chapter_index=2, text_offset=100)
        self.assertEqual(p.chapter_index, 2)
        self.assertEqual(p.text_offset, 100)
        self.assertEqual(p, Position(2, 100))
        self.assertEqual(p.to_dict(), {"chapter_index": 2, "text_offset": 100})
        with self.assertRaises(Exception):
            p.text_offset = 1  # frozen dataclass 不可变

    def test_progress_store_position_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = ProgressStore(Path(tmp) / "progress.json")
            store.load()
            self.assertIsNone(store.position("b1"))
            store.set_position("b1", Position(3, 456))
            self.assertEqual(store.position("b1"), Position(3, 456))
            reloaded = ProgressStore(Path(tmp) / "progress.json")
            reloaded.load()
            self.assertEqual(reloaded.position("b1"), Position(3, 456))


class BookProtocolTest(unittest.TestCase):
    def test_epub_book_satisfies_protocol(self):
        book = EpubBook(str(SAMPLE)).open()
        self.assertIsInstance(book, Book)
        book.close()

    def test_native_book_satisfies_protocol(self):
        book = NativeBook(str(FIXTURE)).open()
        self.assertIsInstance(book, Book)
        book.close()

    def test_book_manager_register_returns_book(self):
        books = BookManager()
        try:
            registered = books.register(str(FIXTURE))
            self.assertIsInstance(registered, Book)
        finally:
            books.close_all()


if __name__ == "__main__":
    unittest.main()
