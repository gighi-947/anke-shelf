"""端到端验证：真实骨碌碌公开书 → EPUB → 书架注册（临时目录）。"""
import tempfile
import time
from pathlib import Path
from unittest.mock import patch

from app.book_manager import BookManager
from app.gululu_service import GululuService
from app.paths import file_mtime
from app.shelf import BookRecord, Shelf


SOURCE = "https://www.gululu.world/book/66905"


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        covers = root / "covers"
        covers.mkdir()
        library = root / "gululu_library"
        books = BookManager()
        shelf = Shelf(root / "shelf.json", covers)
        shelf.load()

        def register(path: str) -> str:
            book = books.register(path)
            rec = BookRecord(
                id=book.id,
                path=book.path,
                title=book.title,
                author=book.author,
                language=book.language,
                chapter_count=len(book.chapters),
                file_size=Path(path).stat().st_size,
                file_mtime=file_mtime(path),
                cover_rel=shelf.extract_cover(book),
            )
            shelf.upsert(rec)
            shelf.save()
            return book.id

        service = GululuService(register)
        with patch("app.gululu_service.gululu_library_dir", return_value=library):
            started = service.start(SOURCE)
            if not started.get("ok"):
                raise RuntimeError(started.get("error") or "启动失败")
            deadline = time.time() + 90
            while service.status()["running"] and time.time() < deadline:
                time.sleep(0.1)

        status = service.status()
        if status["stage"] != "done":
            raise RuntimeError(status.get("error") or f"任务未完成：{status['stage']}")
        rec = shelf.get(status["book_id"])
        if rec is None:
            raise RuntimeError("EPUB 已生成但没有书架记录")
        book = books.open(rec.id)
        embedded_comments = sum(
            book.chapter_text(index).count("gululu-comment")
            for index in range(len(book.chapters))
        )
        if embedded_comments:
            raise RuntimeError(f"紧凑 EPUB 仍包含评论标记：{embedded_comments}")
        print(f"title={book.title}")
        print(f"author={book.author}")
        print(f"chapters={len(book.chapters)}")
        print(f"embedded_comments={embedded_comments}")
        print(f"path={rec.path}")
        books.close_all()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
