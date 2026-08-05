"""复现：点击书架工具栏空白按钮（theme-btn）是否崩溃。"""
import json
import os
import sys
import tempfile
import threading
import time
from pathlib import Path

import webview

PROJECT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT))

from app.annotations import AnnotationStore
from app.api import Api
from app.book_manager import BookManager
from app.nga_service import NgaService
from app.search import SearchService
from app.settings import Settings
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
    settings = Settings(root / "settings.json")
    settings.load()
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
            cover_rel=shelf.extract_cover(book),
        )
        shelf.upsert(rec)
        shelf.save()
        return book.id

    nga_svc = NgaService(register)
    api = Api(books=books, shelf=shelf, progress=progress, settings=settings,
              search=search, annotations=ann, stats=stats, nga_service=nga_svc)
    book = books.register(str(SAMPLE))
    rec = BookRecord(
        id=book.id, path=book.path, title=book.title, author=book.author,
        language=book.language, chapter_count=len(book.chapters),
        cover_rel=shelf.extract_cover(book),
    )
    shelf.upsert(rec)
    shelf.save()
    token = "ui-test-token"
    port = __import__("app.server", fromlist=["start_server"]).start_server(
        PROJECT / "web", books, covers, api=api, token=token)
    window = webview.create_window(
        "复现主题", f"http://127.0.0.1:{port}/index.html?token={token}",
        width=1000, height=700,
    )

    JS = """
    window.__t = { done: false, log: [] };
    window.addEventListener('error', (e) => {
      window.__t.log.push('JSError:' + (e && e.message));
    });
    (async () => {
      const L = (s) => window.__t.log.push(s);
      try {
        await App.init();
        L('init:1');
        const btns = Array.from(document.querySelectorAll('.toolbar-actions button'))
          .map((b) => b.id + '|' + (b.textContent || '').trim() + '|' + (b.innerHTML || '').slice(0, 40));
        L('buttons:' + btns.join(';'));
        L('before_click:1');
        const btn = document.getElementById('theme-btn');
        btn.click();
        L('after_click_sync:1');
        await new Promise((r) => setTimeout(r, 1000));
        L('theme_after:' + document.documentElement.dataset.theme);
        L('alive:1');
        // 阅读视图主题按钮（theme-btn2）
        const book = (await Bridge.call('get_shelf'))[0];
        if (book) {
          await App.showReader(book.id);
          L('reader_open:1');
          document.getElementById('theme-btn2').click();
          await new Promise((r) => setTimeout(r, 1000));
          L('theme2_after:' + document.documentElement.dataset.theme);
          L('reader_alive:1');
        }
      } catch (e) { L('ERROR:' + (e && e.message)); }
      window.__t.done = true;
    })()
    """

    logs = []
    alive = False
    log_file = Path(tempfile.gettempdir()) / "repro_theme.log"

    def write_logs():
        log_file.write_text("\n".join(logs), encoding="utf-8")

    def run():
        nonlocal alive
        time.sleep(3)
        try:
            window.evaluate_js(JS)
            logs.append("evaluate_js:returned")
            write_logs()
            for _ in range(200):  # 最长 ~30s
                try:
                    st = window.evaluate_js("window.__t ? JSON.stringify(window.__t) : 'none'")
                except Exception:
                    st = None
                if st and st != 'none':
                    data = json.loads(st)
                    for line in data.get("log", []):
                        if line not in logs:
                            logs.append(line)
                    write_logs()
                if st and st != 'none' and '"done":true' in st:
                    break
                time.sleep(0.15)
            if not (st and st != 'none' and '"done":true' in st):
                logs.append("JS_TIMEOUT")
                write_logs()
            alive = True
        except Exception as e:  # noqa: BLE001
            logs.append("HARNESS:" + str(e))
            write_logs()
        finally:
            try:
                window.destroy()
            except Exception:
                pass
            logs.append("destroy:done")
            write_logs()

    window.events.loaded += lambda: threading.Thread(target=run, daemon=True).start()
    webview.start(gui="edgechromium")
    print("=== 主题按钮复现 ===")
    for line in logs:
        print("  " + line)
    print("log file:", log_file)
    ok = any(l == "js_alive:1" for l in logs) and any(l.startswith("theme_after:") for l in logs)
    books.close_all()
    tmp.cleanup()
    return 0 if ok else 1


if __name__ == "__main__":
    rc = main()
    sys.stdout.flush()
    os._exit(rc)
