"""真实窗口复现：下载面板开/关、下载中取消、取消后关闭、桥接导入。

运行：python -m tests.ui.repro_gui
"""
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
              search=search, annotations=ann, stats=stats, nga_service=nga_svc,
              file_dialog=lambda kind: [str(SAMPLE)] if kind == "epub" else [])
    token = "ui-test-token"
    port = __import__("app.server", fromlist=["start_server"]).start_server(
        PROJECT / "web", books, covers, api=api, token=token)

    window = webview.create_window(
        "复现", f"http://127.0.0.1:{port}/index.html?token={token}",
        width=1000, height=700,
    )

    JS_MAIN = """
    window.__t = { done: false, log: [] };
    (async () => {
      const L = (s) => window.__t.log.push(s);
      try {
        await App.init();
        NgaDownload.open();
        await new Promise(r => setTimeout(r, 120));
        const panel = document.getElementById('download-view');
        L('open:' + (!panel.classList.contains('hidden') && document.getElementById('nga-tid') ? 1 : 0));
        NgaDownload.close();
        await new Promise(r => setTimeout(r, 120));
        L('close:' + (panel.classList.contains('hidden') ? 1 : 0));
        // 小任务下载中取消
        NgaDownload.open();
        document.getElementById('nga-tid').value = '41989465';
        document.getElementById('nga-authorid').value = '62906407';
        document.getElementById('nga-max-floors').value = '5';
        document.getElementById('nga-image-mode').value = 'online';
        const r = await Bridge.call('nga_start_download', {
          tid: '41989465', authorid: '62906407', max_floors: 0, page_limit: 60, per_chapter: 20,
          image_mode: 'online', theme: 'light', toc_pid: 0, open_after: false,
        });
        L('start:' + (r.ok ? 1 : 0));
        await new Promise(r => setTimeout(r, 1500));
        await Bridge.call('nga_cancel');
        let st = null;
        for (let i = 0; i < 30; i++) {
          st = await Bridge.call('nga_download_status');
          if (!st.running) break;
          await new Promise(r => setTimeout(r, 400));
        }
        L('cancelled:' + (st && st.stage === 'cancelled' ? 1 : 0));
        NgaDownload.close();
        await new Promise(r => setTimeout(r, 120));
        L('close_after_cancel:' + (panel.classList.contains('hidden') ? 1 : 0));
      } catch (e) { L('ERROR:' + (e && e.message)); }
      window.__t.done = true;
    })()
    """

    JS_IMPORT = """
    window.__imp = { done: false, log: [] };
    (async () => {
      try {
        const r = await Bridge.call('import_books');
        window.__imp.log.push('import:' + (r && r[0] && r[0].ok ? 1 : 0));
      } catch (e) { window.__imp.log.push('import:0'); window.__imp.log.push('ERR:' + (e && e.message)); }
      window.__imp.done = true;
    })()
    """

    results = {}

    def run():
        time.sleep(3)
        try:
            window.evaluate_js(JS_MAIN)
            for _ in range(250):
                try:
                    st = window.evaluate_js("window.__t ? JSON.stringify(window.__t) : 'none'")
                except Exception:
                    st = None
                if st and st != 'none' and '"done":true' in st:
                    break
                time.sleep(0.15)
            logs = json.loads(st).get("log", []) if st and st != 'none' else []
            for line in logs:
                name, _, val = line.partition(':')
                results[name] = val == '1'
            window.evaluate_js(JS_IMPORT)
            for _ in range(100):
                try:
                    st = window.evaluate_js("window.__imp ? JSON.stringify(window.__imp) : 'none'")
                except Exception:
                    st = None
                if st and st != 'none' and '"done":true' in st:
                    break
                time.sleep(0.15)
            imp = json.loads(st) if st and st != 'none' else {"log": []}
            for line in imp.get("log", []):
                if line.startswith("import:"):
                    results["import_ok"] = line.split(":", 1)[1] == "1"
                if line.startswith("ERR:"):
                    results["import_err"] = line
        finally:
            try:
                window.destroy()
            except Exception:
                pass

    window.events.loaded += lambda: threading.Thread(target=run, daemon=True).start()
    webview.start(gui="edgechromium")
    print("=== 复现结果 ===")
    ok = True
    for name in ["open", "close", "start", "cancelled", "close_after_cancel", "import_ok"]:
        r = results.get(name)
        ok = ok and r
        print(f"  {name:18s} {'PASS' if r else 'FAIL'}")
    if not ok and results.get("import_err"):
        print("  import_err:", results["import_err"])
    books.close_all()
    tmp.cleanup()
    return 0 if ok else 1


if __name__ == "__main__":
    rc = main()
    sys.stdout.flush()
    os._exit(rc)
