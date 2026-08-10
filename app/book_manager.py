"""EpubBook 实例缓存：严格 LRU（最多 4 本）+ 线程锁。

ZipFile 持有文件句柄，Windows 下必须保证：
- 逐出/关闭书籍时先 close() 再释放引用（否则删除书籍文件失败）
- 用户原地替换 .epub 时旧句柄继续读旧内容，不崩溃（可接受）
"""
import threading

from .domain import Book
from .epub import EpubBook
from .native_book import NativeBook, is_native_dir


class BookManager:
    """book_id → EpubBook 注册表。LRU 逐出，dict 尾部为最近使用。"""

    MAX_CACHE = 4

    def __init__(self) -> None:
        self._books: dict[str, Book] = {}
        self._lock = threading.RLock()

    def register(self, path: str) -> Book:
        """解析并注册一本新书。解析失败抛 EpubError，不注册。"""
        if is_native_dir(path):
            book = NativeBook(path).open()
        else:
            book = EpubBook(path).open()
        with self._lock:
            self._books[book.id] = book
            self._evict_locked()
        return book

    def open(self, book_id: str) -> Book:
        """取已注册的书并提升 LRU 新鲜度。未注册抛 KeyError。"""
        with self._lock:
            book = self._books.pop(book_id, None)
            if book is None:
                raise KeyError(book_id)
            self._books[book_id] = book
            return book

    def has(self, book_id: str) -> bool:
        with self._lock:
            return book_id in self._books

    def close(self, book_id: str) -> None:
        with self._lock:
            book = self._books.pop(book_id, None)
            if book is not None:
                book.close()

    def close_all(self) -> None:
        with self._lock:
            for book in self._books.values():
                book.close()
            self._books.clear()

    def _evict_locked(self) -> None:
        while len(self._books) > self.MAX_CACHE:
            key, book = next(iter(self._books.items()))
            book.close()
            del self._books[key]
