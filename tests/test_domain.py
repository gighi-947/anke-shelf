"""B2 领域模型：Position 值对象与 Book Protocol（EpubBook / NativeBook 双实现）。"""
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from app.book_manager import BookManager
from app.domain import (
    Book,
    Position,
    book_revision,
)
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


class BookRevisionTest(unittest.TestCase):
    def test_native_and_epub_revision_prefix(self):
        nb = NativeBook(str(FIXTURE)).open()
        self.assertTrue(book_revision(nb).startswith("native:"))
        nb.close()
        eb = EpubBook(str(SAMPLE)).open()
        self.assertTrue(book_revision(eb).startswith("epub:"))
        eb.close()

    def test_native_revision_changes_after_update(self):
        with tempfile.TemporaryDirectory() as tmp:
            dst = Path(tmp) / "book"
            shutil.copytree(FIXTURE, dst)
            nb = NativeBook(str(dst)).open()
            r1 = book_revision(nb)
            meta_path = dst / "meta.json"
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            meta["last_lou"] = 10
            meta["updated_time"] = "2026-08-10T12:00:00"
            meta_path.write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
            nb2 = NativeBook(str(dst)).open()
            r2 = book_revision(nb2)
            self.assertNotEqual(r1, r2)
            nb.close()
            nb2.close()


if __name__ == "__main__":
    unittest.main()
