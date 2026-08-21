"""真实 NGA 书验证：使用本机书架中带 NGA 特殊排版的提取帖做渲染回归。

运行：python -m tests.ui.verify_nga_real

只读本机书架（%APPDATA%\\AnkeShelf\\shelf.json）；进度/设置/统计等全部写入
临时目录，不修改用户数据。优先使用 tid 41989465 的书，找不到则用第一本 NGA 书。
"""
import json
import argparse
import os
import sys
import tempfile
import threading
import time
from pathlib import Path

import webview

PROJECT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT))

from app.annotations import AnnotationStore  # noqa: E402
from app.api import Api  # noqa: E402
from app.book_manager import BookManager  # noqa: E402
from app.export_service import ExportService  # noqa: E402
from app.gululu_service import GululuService  # noqa: E402
from app.nga_login import NgaLoginController  # noqa: E402
from app.nga_service import NgaService  # noqa: E402
from app.search import SearchService  # noqa: E402
from app.server import start_server  # noqa: E402
from app.settings import Settings  # noqa: E402
from app.shelf import BookRecord, ProgressStore, Shelf  # noqa: E402
from app.stats import StatsStore  # noqa: E402

PREFER_TID = 41989465


def _real_shelf_path() -> Path:
    base = os.environ.get("APPDATA")
    return Path(base) / "AnkeShelf" / "shelf.json" if base else Path.home() / ".ankeshelf" / "shelf.json"


def _pick_book() -> dict:
    path = _real_shelf_path()
    if not path.is_file():
        raise SystemExit(f"未找到书架：{path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    books = [b for b in data.get("books", []) if b.get("nga_tid")]
    if not books:
        raise SystemExit("书架中没有 NGA 帖子")
    for b in books:
        if b["nga_tid"] == PREFER_TID and Path(b["path"]).is_dir():
            return b
    return next(b for b in books if Path(b["path"]).is_dir())


def _table_chapter_index(book_dir: Path) -> int:
    """找第一个含大量行（>40 个 <tr>）长表格且体量适中的章节（双页超界回归用）。"""
    meta = json.loads((book_dir / "meta.json").read_text(encoding="utf-8"))
    chapters = meta.get("chapters", [])
    for i, ch in enumerate(chapters):
        if ch.get("main"):
            continue
        f = book_dir / ch["file"]
        if not f.is_file() or f.stat().st_size > 400_000:
            continue
        try:
            if f.read_bytes().count(b"<tr") > 40:
                return i
        except OSError:
            continue
    return 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chapter", type=int, default=None,
                        help="指定章节索引（默认自动选高表格章节）")
    args = parser.parse_args()
    rec = _pick_book()
    book_dir = Path(rec["path"])
    table_idx = args.chapter if args.chapter is not None else _table_chapter_index(book_dir)
    print(f"测试书籍：{rec['title']}（tid {rec['nga_tid']}，{rec['chapter_count']} 章）")
    print(f"长表格章节索引：{table_idx}")

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

    book = books.register(str(book_dir))
    shelf.upsert(BookRecord(
        id=book.id, path=book.path, title=book.title, author=book.author,
        language=book.language, chapter_count=len(book.chapters),
        nga_tid=int(rec["nga_tid"]),
    ))
    shelf.save()

    nga_svc = NgaService(lambda p: "")
    api = Api(books=books, shelf=shelf, progress=progress, settings=settings,
              search=search, annotations=ann, stats=stats, nga_service=nga_svc,
              export_service=ExportService(shelf),
              gululu_service=GululuService(lambda _path: ""),
              frontend_ready=threading.Event(), nga_login=NgaLoginController(),
              window_toggle=lambda _entering: None)
    port = start_server(PROJECT / "web", books, covers, api=api, token="real-nga")

    window = webview.create_window(
        "真实 NGA 书验证", f"http://127.0.0.1:{port}/index.html?token=real-nga",
        width=1280, height=800,
    )

    JS = """
    window.__t = { done: false, log: [] };
    (async () => {
      const L = (s) => window.__t.log.push(s);
      try {
        await App.init();
        await App.showReader('__BID__');
        await new Promise(r => setTimeout(r, 1800));
        const doc0 = document.getElementById('chapter-frame').contentDocument;
        L('nga_flag:' + (App.state.book && App.state.book.nga ? 1 : 0));
        L('nga_markup:' + (
          doc0.querySelectorAll('.nga-floor').length > 0 &&
          doc0.querySelectorAll('span[style*="color"]').length > 0 ? 1 : 0
        ));
        // 滚动模式：不分页，底栏无上/下一页
        const pp = document.getElementById('page-prev');
        L('scroll_no_pages:' + (!Paged.isActive() && pp && getComputedStyle(pp).display === 'none' ? 1 : 0));
        // 单页分页 + 长表格章节
        await Bridge.call('save_settings', { pagination: true, auto_dual: false, dual_page: false });
        App.state.settings.pagination = true;
        App.state.settings.auto_dual = false;
        App.state.settings.dual_page = false;
        await Reader.loadChapter(__TIDX__, 0);
        await new Promise(r => setTimeout(r, 1800));
        const m = Paged.measure();
        L('paged_pages:' + (m.total > 1 ? 1 : 0));
        const tdoc = document.getElementById('chapter-frame').contentDocument;
        const tTables = tdoc.querySelectorAll('table');
        const tPt = parseFloat(getComputedStyle(tdoc.body).paddingTop) || 0;
        const tPb = parseFloat(getComputedStyle(tdoc.body).paddingBottom) || 0;
        const tPageH = Math.max(1, tdoc.body.clientHeight - tPt - tPb);
        const tMaxH = Math.max(0, ...[...tTables].map((t) => t.scrollHeight));
        L('table_wrapped:' + (
          tdoc.querySelectorAll('.nga-table-scroll').length > 0 || tMaxH <= tPageH + 2 ? 1 : 0
        ));
        L('table_debug:' + JSON.stringify({
          tables: tTables.length,
          wraps: tdoc.querySelectorAll('.nga-table-scroll').length,
          bodyH: tdoc.body.clientHeight,
          pt: tPt,
          pb: tPb,
          pageH: tPageH,
          maxTableH: tMaxH,
        }));
        Paged.gotoPage(m.total - 1);
        await new Promise(r => setTimeout(r, 350));
        const mend = Paged.measure();
        L('paged_last_ok:' + (mend.current === m.total - 1 && !Paged.isPageBlank(mend.current) ? 1 : 0));
        const lastEl = tdoc.body.lastElementChild;
        const lastRect = lastEl ? lastEl.getBoundingClientRect() : null;
        L('paged_debug:' + JSON.stringify({
          scrollW: tdoc.body.scrollWidth,
          clientW: tdoc.body.clientWidth,
          total: m.total,
          lastCurrent: mend.current,
          step: m.step,
          blank: Paged.isPageBlank(mend.current),
          lastTag: lastEl ? lastEl.tagName : null,
          lastText: lastEl ? (lastEl.textContent || '').slice(0, 40) : null,
          lastRectTop: lastRect ? Math.round(lastRect.top) : null,
          lastRectBottom: lastRect ? Math.round(lastRect.bottom) : null,
          bodyScrollLeft: tdoc.body.scrollLeft,
        }));
        const bw = tdoc.body.clientWidth;
        const bh = tdoc.body.clientHeight;
        let bhits = 0;
        let bsamples = 0;
        for (const fy of [0.06, 0.2, 0.4, 0.6, 0.8]) {
          for (const fx of [0.25, 0.5, 0.75]) {
            bsamples++;
            const el = tdoc.elementFromPoint(Math.max(2, Math.min(bw - 2, bw * fx)), Math.round(bh * fy));
            let found = false;
            if (el && el !== tdoc.body && el !== tdoc.documentElement) {
              const walker = tdoc.createTreeWalker(el, NodeFilter.SHOW_TEXT);
              let n;
              while ((n = walker.nextNode())) {
                if (n.data && n.data.trim()) { found = true; break; }
              }
            }
            if (found) bhits++;
          }
        }
        const eTop = tdoc.elementFromPoint(Math.max(2, Math.min(bw - 2, bw * 0.25)), Math.round(bh * 0.06));
        L('blank_debug:' + JSON.stringify({
          hits: bhits,
          samples: bsamples,
          topEl: eTop ? eTop.tagName + '.' + String(eTop.className || '').slice(0, 30) : 'null',
          topText: eTop ? (eTop.textContent || '').slice(0, 50) : '',
          caret: (() => {
            const r = tdoc.caretRangeFromPoint(Math.max(2, Math.min(bw - 2, bw * 0.25)), Math.round(bh * 0.06));
            if (!r || !r.startContainer) return 'null';
            const n = r.startContainer;
            return (n.nodeType === 3 ? n.data : n.textContent || '').slice(0, 50);
          })(),
          lastFloorRects: lastEl ? [...lastEl.getClientRects()].map((r) => ({
            l: Math.round(r.left), t: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height),
          })).slice(0, 5) : [],
          lastFloorRectsCount: lastEl ? lastEl.getClientRects().length : 0,
        }));
        L('paged_no_overflow:' + (
          tdoc.documentElement.scrollWidth <= document.getElementById('chapter-frame').clientWidth + 1 ? 1 : 0
        ));
        // 强制双页
        await Bridge.call('save_settings', { dual_page: true });
        App.state.settings.dual_page = true;
        await Reader.loadChapter(__TIDX__, 0);
        await new Promise(r => setTimeout(r, 1800));
        const dm = Paged.measure();
        L('dual_active:' + (Paged.isDual() && dm.step === 2 ? 1 : 0));
        Paged.gotoPage(dm.total - 1);
        await new Promise(r => setTimeout(r, 350));
        const dmEnd = Paged.measure();
        const ddoc = document.getElementById('chapter-frame').contentDocument;
        L('dual_last_ok:' + (dmEnd.current === dm.total - 1 && !Paged.isPageBlank(dmEnd.current) ? 1 : 0));
        L('dual_no_overflow:' + (
          ddoc.documentElement.scrollWidth <= document.getElementById('chapter-frame').clientWidth + 1 ? 1 : 0
        ));
        // 快速连续切章：最终状态正确且排版已加载；
        // 同时监控加载过程中 iframe 不允许出现 about:blank（白画布来源）
        const allFrames = () => Array.from(document.querySelectorAll('.chapter-frame'));
        let sawBlank = false;
        const urlProbe = setInterval(() => {
          try {
            if (allFrames().some((f) => f.contentWindow.location.href === 'about:blank')) {
              sawBlank = true;
            }
          } catch (e) { /* ignore */ }
        }, 50);
        for (const ci of [1, 3, 5, 2]) Reader.loadChapter(ci, 0);
        await new Promise(r => setTimeout(r, 2200));
        clearInterval(urlProbe);
        const actFrame = document.querySelector('.chapter-frame.active') || document.getElementById('chapter-frame');
        const fdoc = actFrame.contentDocument;
        L('no_about_blank:' + (!sawBlank ? 1 : 0));
        L('frame_bg_dark:' + (
          getComputedStyle(actFrame).backgroundColor !== 'rgb(255, 255, 255)' ? 1 : 0
        ));
        L('rapid_flip_ok:' + (
          App.state.chapterIndex === 2 &&
          App.state.textCtx && App.state.textCtx.text.length > 0 &&
          fdoc.getElementById('__reader_overrides__') ? 1 : 0
        ));
        // ---- 全文检索：高频词不得挤掉靠后章节（用户反馈只显示到 170 楼） ----
        document.getElementById('search-page-btn').click();
        await new Promise(r => setTimeout(r, 80));
        L('search_page:' + (window.FullSearch && FullSearch.isOpen() ? 1 : 0));
        const fsInput = document.getElementById('fs-input');
        fsInput.value = '丰川祥子';
        await FullSearch.query(true);
        for (let i = 0; i < 90 && document.querySelectorAll('#fs-results .fs-group').length === 0; i++) {
          await new Promise(r => setTimeout(r, 250));
        }
        const fsGroups = document.querySelectorAll('#fs-results .fs-group');
        const fsLast = fsGroups.length ? parseInt(fsGroups[fsGroups.length - 1].dataset.chapter, 10) : -1;
        const fsSummaryText = document.getElementById('fs-summary').textContent;
        L('search_has_results:' + (fsGroups.length > 0 ? 1 : 0));
        L('search_multi_chapter:' + (fsGroups.length >= 5 ? 1 : 0));
        L('search_late_chapter:' + (fsLast >= 20 ? 1 : 0));
        L('search_summary:' + (/共 \\d+ 处命中/.test(fsSummaryText) ? 1 : 0));
        L('search_options:' + (!!document.getElementById('fs-case') && !!document.getElementById('fs-word') ? 1 : 0));
        L('search_debug:' + JSON.stringify({
          groups: fsGroups.length,
          last: fsLast,
          summary: fsSummaryText,
          total: fsSummaryText.match(/共 (\\d+) 处命中/) ? fsSummaryText.match(/共 (\\d+) 处命中/)[1] : -1,
        }));
        FullSearch.close();
      } catch (e) { L('ERROR:' + (e && e.message)); }
      window.__t.done = true;
    })()
    """

    results = {}
    logs = []

    def run():
        time.sleep(3)
        try:
            window.evaluate_js(JS.replace('__BID__', book.id).replace('__TIDX__', str(table_idx)))
            for _ in range(300):
                try:
                    st = window.evaluate_js("window.__t ? JSON.stringify(window.__t) : 'none'")
                except Exception:
                    st = None
                if st and st != 'none' and '"done":true' in st:
                    break
                time.sleep(0.15)
            data = json.loads(st) if st and st != 'none' else {"log": []}
            logs[:] = data.get("log", [])
            for line in logs:
                name, _, val = line.partition(':')
                if name and val in ('0', '1'):
                    results[name] = val == '1'
        finally:
            try:
                window.destroy()
            except Exception:
                pass

    window.events.loaded += lambda: threading.Thread(target=run, daemon=True).start()
    webview.start(gui="edgechromium")

    print("=== 真实 NGA 书验证 ===")
    all_ok = True
    for name in ['nga_flag', 'nga_markup', 'scroll_no_pages', 'paged_pages',
                 'table_wrapped', 'paged_last_ok', 'paged_no_overflow',
                 'dual_active', 'dual_last_ok', 'dual_no_overflow',
                 'no_about_blank', 'frame_bg_dark', 'rapid_flip_ok',
                 'search_page', 'search_has_results', 'search_multi_chapter',
                 'search_late_chapter', 'search_summary', 'search_options']:
        ok = results.get(name, False)
        all_ok = all_ok and ok
        print(f"  {name:18s} {'PASS' if ok else 'FAIL'}")
    if not all_ok:
        print("  --- JS 日志 ---")
        for line in logs:
            print("   ", line)
    books.close_all()
    tmp.cleanup()
    return 0 if all_ok else 1


if __name__ == "__main__":
    rc = main()
    sys.stdout.flush()
    os._exit(rc)
