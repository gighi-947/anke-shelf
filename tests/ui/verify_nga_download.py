"""端到端验证：真实 NGA 下载 → EPUB → 书架注册（无窗口，需本机 Python + 网络）。

运行：python -m tests.ui.verify_nga_download
"""
import os
import sys
import tempfile
import time
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT))

from app.annotations import AnnotationStore
from app.book_manager import BookManager
from app.nga_service import NgaService
from app.search import SearchService
from app.shelf import BookRecord, ProgressStore, Shelf
from app.stats import StatsStore


def main() -> int:
    tmp = tempfile.TemporaryDirectory()
    root = Path(tmp.name)
    covers = root / "covers"
    covers.mkdir()
    shelf = Shelf(root / "shelf.json", covers)
    shelf.load()
    progress = ProgressStore(root / "progress.json")
    progress.load()
    search = SearchService()
    ann = AnnotationStore(root / "annotations.json")
    ann.load()
    stats = StatsStore(root / "statistics.json")
    stats.load()
    books = BookManager()

    def register(path: str) -> str:
        book = books.register(path)
        rec = BookRecord(
            id=book.id, path=book.path, title=book.title, author=book.author,
            language=book.language, chapter_count=len(book.chapters),
            cover_rel=shelf.extract_cover(book), nga_tid=41989465,
        )
        shelf.upsert(rec)
        shelf.save()
        return book.id

    svc = NgaService(register)
    r = svc.start({
        "tid": "41989465",
        "authorid": "62906407",
        "max_floors": "5",
        "image_mode": "online",
        "theme": "light",
        "toc_pid": "0",
        "per_chapter": "20",
    })
    print("start:", r)
    if not r["ok"]:
        tmp.cleanup()
        return 2

    last = None
    for _ in range(300):  # 最长 5 分钟
        s = svc.status()
        if s != last:
            last = dict(s)
            print(f"  [{s['stage']}] {s['detail']}")
        if not s["running"]:
            break
        time.sleep(1)

    s = svc.status()
    print("final:", s)
    if s["stage"] != "done":
        tmp.cleanup()
        return 1

    rec = shelf.get(s["book_id"])
    print("shelf:", rec.title, "|", rec.author, "| tid", rec.nga_tid, "| chapters", rec.chapter_count)
    book = books.open(s["book_id"])
    html = book.chapter_text(min(1, len(book.chapters) - 1)) or ""
    ok_floor = "nga-floor" in html and "style=" in html
    print("inline NGA style in chapter:", "PASS" if ok_floor else "FAIL")
    tmp.cleanup()
    return 0 if ok_floor else 1


if __name__ == "__main__":
    rc = main()
    sys.stdout.flush()
    os._exit(rc)
