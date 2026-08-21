"""诊断：取消下载的响应性 + 下载进行中导入书籍的并发安全性。

运行：python -m tests.ui.diag_cancel_import
"""
import os
import sys
import tempfile
import threading
import time
from pathlib import Path
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT))

from app.annotations import AnnotationStore
from app.export_service import ExportService
from app.gululu_service import GululuService
from app.nga_login import NgaLoginController
from app.api import Api
from app.book_manager import BookManager
from app.nga_service import NgaService
from app.search import SearchService
from app.shelf import BookRecord, ProgressStore, Shelf
from app.stats import StatsStore

SAMPLE = PROJECT / "tests" / "sample" / "sample_nav3.epub"


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

    nga_svc = NgaService(register)
    api = Api(books=books, shelf=shelf, progress=progress, settings=type(
        "S", (), {"get": lambda self, k: None, "get_all": lambda self: {}, "update": lambda self, p: None})(),
        search=search, annotations=ann, stats=stats, nga_service=nga_svc,
        export_service=ExportService(shelf),
        gululu_service=GululuService(register),
        frontend_ready=threading.Event(), nga_login=NgaLoginController(),
        window_toggle=lambda _entering: None,
        file_dialog=lambda kind: [str(SAMPLE)] if kind == "epub" else [])

    # ---- 测试 A：导入书籍（无下载） ----
    try:
        r = api.import_books()
        print("A import (idle):", "OK" if r and r[0].get("ok") else r)
    except Exception as e:  # noqa: BLE001
        print("A import CRASH:", type(e).__name__, e)
        tmp.cleanup()
        return 1

    # ---- 测试 B：下载中取消 ----
    with patch("app.paths.data_dir", return_value=root), \
            patch("app.nga_config._candidate_source",
                  return_value=PROJECT / "ngapost2md-python" / "config.ini"):
        r = nga_svc.start({
            "tid": "41989465", "authorid": "62906407", "max_floors": "0",
            "page_limit": "60", "image_mode": "online", "theme": "light",
            "toc_pid": "0", "per_chapter": "20",
        })
        print("B start:", r)
        time.sleep(2.5)
        nga_svc.cancel()
        t0 = time.time()
        while nga_svc.status()["running"] and time.time() - t0 < 30:
            time.sleep(0.5)
        s = nga_svc.status()
        print(f"B cancel -> {s['stage']} in {time.time() - t0:.1f}s")

        # ---- 测试 C：下载进行中导入 ----
        if s["stage"] == "cancelled":
            # 再起一个慢任务，期间导入
            nga_svc.start({
                "tid": "41989465", "authorid": "62906407", "max_floors": "0",
                "page_limit": "60", "image_mode": "online", "theme": "light",
                "toc_pid": "0", "per_chapter": "20",
            })
            time.sleep(2)
            try:
                r2 = api.import_books()
                print("C import (during download):", "OK" if r2 and r2[0].get("ok") else r2)
            except Exception as e:  # noqa: BLE001
                print("C import CRASH:", type(e).__name__, e)
                tmp.cleanup()
                return 1
            nga_svc.cancel()

    tmp.cleanup()
    print("DONE")
    return 0


if __name__ == "__main__":
    rc = main()
    sys.stdout.flush()
    os._exit(rc)
