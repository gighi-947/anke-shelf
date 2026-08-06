"""UI 自动化验证 harness（需真实窗口，独立于 unittest）。

运行：python -m tests.ui.runner [--debug]
覆盖：JS/Python 文本差分、分页、标注、书签、辅助、统计。
每项断言 PASS/FAIL，汇总退出码（全 PASS=0）。

注意：pywebview 需要桌面会话；CI 无头环境请跳过。
"""
import argparse
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
from app.server import start_server
from app.settings import Settings
from app.shelf import ProgressStore, Shelf, BookRecord
from app.stats import StatsStore
from app.epub import EpubBook
from app.text import extract_dom_text

SAMPLE = PROJECT / "tests" / "sample" / "sample_nav3.epub"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()

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
    books = BookManager()
    search = SearchService()
    ann = AnnotationStore(root / "annotations.json")
    ann.load()
    stats = StatsStore(root / "statistics.json")
    stats.load()

    def _register_nga_book(path: str) -> str:
        book = books.register(path)
        nrec = BookRecord(
            id=book.id, path=book.path, title=book.title, author=book.author,
            language=book.language, chapter_count=len(book.chapters),
            cover_rel=shelf.extract_cover(book),
        )
        shelf.upsert(nrec)
        shelf.save()
        return book.id

    nga_svc = NgaService(_register_nga_book)
    api = Api(books=books, shelf=shelf, progress=progress, settings=settings,
              search=search, annotations=ann, stats=stats, nga_service=nga_svc)
    token = "ui-test-token"
    port = start_server(PROJECT / "web", books, covers, api=api, token=token)
    book = books.register(str(SAMPLE))
    rec = BookRecord(id=book.id, path=book.path, title=book.title, author=book.author,
                     language=book.language, chapter_count=len(book.chapters),
                     cover_rel=shelf.extract_cover(book))
    shelf.upsert(rec)
    shelf.save()
    # 模拟 NGA 书（验证阅读器原版样式开关）
    rec.nga_tid = 41989465
    shelf.upsert(rec)
    shelf.save()

    # Python 差分基准（5 章纯文本长度）
    b2 = EpubBook(str(SAMPLE)).open()
    py_lens = [len(extract_dom_text(b2.chapter_text(i))) for i in range(5)]
    b2.close()

    window = webview.create_window(
        "UI 自动化验证", f"http://127.0.0.1:{port}/index.html?token={token}",
        width=1000, height=700,
    )

    JS = """
    window.__test = { done: false, log: [] };
    (async () => {
      const L = (s) => window.__test.log.push(s);
      try {
        await App.init();
        L('init:1');
        // NGA 下载面板
        document.getElementById('nga-download-btn').click();
        await new Promise(r => setTimeout(r, 800));
        const dpanel = document.getElementById('download-view');
        L('nga_panel:' + (!dpanel.classList.contains('hidden') && document.getElementById('nga-tid') ? 1 : 0));
        L('nga_reopen_no_jump:' + (App.state.view === 'shelf' ? 1 : 0));
        const ngaCfg = await Bridge.call('nga_get_config');
        L('nga_bridge:' + (ngaCfg && typeof ngaCfg === 'object' ? 1 : 0));
        NgaDownload.close();
        await App.showReader('__BID__');
        await new Promise(r => setTimeout(r, 1000));
        // 差分：逐章 JS 文本长度
        const lens = [];
        for (let ci = 0; ci < 5; ci++) {
          await Promise.race([
            Reader.loadChapter(ci, 0),
            new Promise(r => setTimeout(() => { L('ch_timeout:' + ci); r(); }, 4000)),
          ]);
          await new Promise(r => setTimeout(r, 300));
          const ctx = App.state.textCtx;
          lens.push(ctx ? ctx.text.length : -1);
          L('ch:' + ci + ':' + (ctx ? ctx.text.length : -1));
        }
        L('lens:' + lens.join(','));
        // NGA 原版样式：覆盖层不应强制正文颜色
        const ov = document.getElementById('chapter-frame').contentDocument.getElementById('__reader_overrides__');
        L('nga_flag:' + (App.state.book && App.state.book.nga ? 1 : 0));
        L('nga_style:' + (ov && ov.textContent.indexOf('color: var(--reader-fg)') === -1 ? 1 : 0));
        // 分页
        await Reader.loadChapter(2, 0);
        await new Promise(r => setTimeout(r, 1000));
        const m = Paged.measure();
        L('pages:' + (m.total > 1 ? m.total : 0));
        Paged.nextPage(false);
        await new Promise(r => setTimeout(r, 200));
        const m2 = Paged.measure();
        L('page_next:' + (m2.current === m.current + 1 ? 1 : 0));
        // 标注
        await Bridge.call('save_annotation', '__BID__', 2, 500, 560, '引力波源位于遥远的星系中心', 'yellow', '');
        await Annotations.refresh();
        await Reader.loadChapter(2, 0);
        await new Promise(r => setTimeout(r, 1000));
        const marks = document.getElementById('chapter-frame').contentDocument.querySelectorAll('mark.hl-mark');
        L('marks:' + (marks.length === 1 ? 1 : 0));
        // 书签
        await Bridge.call('add_bookmark', '__BID__', 2, 700, '书签');
        const annData = await Bridge.call('get_annotations', '__BID__');
        L('bm:' + (annData.bookmarks.length === 1 ? 1 : 0));
        // 代码高亮
        const preCode = document.getElementById('chapter-frame').contentDocument.querySelector('pre code.syntax');
        L('codehl:' + (preCode ? 1 : 0));
        // 统计
        await Bridge.call('record_reading', '__BID__', 60, 3);
        const st = await Bridge.call('get_stats', '__BID__');
        L('stats:' + (st.book.total_seconds === 60 ? 1 : 0));
        // 横屏双页模式
        await Bridge.call('save_settings', { pagination: true, dual_page: true });
        App.state.settings.pagination = true;
        App.state.settings.dual_page = true;
        await Reader.loadChapter(2, 0);
        await new Promise(r => setTimeout(r, 1200));
        L('dual_active:' + (Paged.isDual && Paged.isDual() ? 1 : 0));
        // 分页 iframe 尺寸不得超出可视区域（曾因 chapter-wrap 内边距导致错位/裁切）
        const fr = document.getElementById('chapter-frame').getBoundingClientRect();
        const wrapR = document.querySelector('.chapter-wrap').getBoundingClientRect();
        const scR = document.getElementById('chapter-scroll').getBoundingClientRect();
        L('frame_fit:' + (
          fr.left >= wrapR.left - 1 && fr.right <= wrapR.right + 1 && fr.bottom <= scR.bottom + 1 ? 1 : 0
        ));
        const dm = Paged.measure();
        L('dual_spread:' + (dm.step === 2 ? 1 : 0));
        L('dual_pages:' + (dm.total >= 1 ? 1 : 0));
        Paged.nextPage(false);
        await new Promise(r => setTimeout(r, 250));
        const dm2 = Paged.measure();
        L('dual_next:' + (dm2.current > dm.current ? 1 : 0));
        // flow 式自动双页：横屏宽窗 + auto_dual（默认）→ 自动双页
        await Bridge.call('save_settings', { pagination: true, dual_page: false, auto_dual: true });
        App.state.settings.pagination = true;
        App.state.settings.dual_page = false;
        App.state.settings.auto_dual = true;
        await Reader.loadChapter(2, 0);
        await new Promise(r => setTimeout(r, 1000));
        L('dual_auto:' + (Paged.isDual() && Paged.measure().step === 2 ? 1 : 0));
        // 强制单页：auto_dual=false 时不双页
        App.state.settings.auto_dual = false;
        await Reader.loadChapter(2, 0);
        await new Promise(r => setTimeout(r, 1000));
        L('dual_off:' + (!Paged.isDual() ? 1 : 0));
        App.state.settings.auto_dual = true;
        // NGA 长表格 → 限高滚动容器，防止页面出界错位
        const tdoc = document.getElementById('chapter-frame').contentDocument;
        const tblEl = tdoc.createElement('table');
        let trs = '';
        for (let i = 0; i < 90; i++) trs += '<tr><td>测试行内容 ' + i + '</td></tr>';
        tblEl.innerHTML = '<tr><td rowspan="90">跨行单元格</td></tr>' + trs;
        tblEl.style.width = '99%';
        tblEl.style.height = '3000px';  // 模拟 NGA 带 rowspan 的整体化长表格
        tdoc.body.appendChild(tblEl);
        await new Promise(r => setTimeout(r, 300));
        Paged.normalizeTallTables(tdoc);
        await new Promise(r => setTimeout(r, 300));
        const tblWraps = tdoc.querySelectorAll('.nga-table-scroll');
        L('table_wrap:' + (tblWraps.length > 0 && tblWraps[0].scrollHeight > tblWraps[0].clientHeight ? 1 : 0));
        L('table_debug:' + JSON.stringify({
          tblH: Math.round(tblEl.getBoundingClientRect().height),
          bodyH: tdoc.body.clientHeight,
          wraps: tblWraps.length,
          ch: tblWraps[0] ? tblWraps[0].clientHeight : -1,
          sh: tblWraps[0] ? tblWraps[0].scrollHeight : -1,
          scrollW: tdoc.body.scrollWidth,
        }));
        // ---- Readest 借鉴第一批 ----
        // 点击页面中央切换顶/底栏
        Reader._lastChromeToggle = 0;
        Reader.toggleChrome();
        L('chrome_toggle:' + (!document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0));
        Reader._lastChromeToggle = 0;
        Reader.toggleChrome();
        L('chrome_show:' + (document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0));
        // ? 快捷键帮助弹窗
        Reader.showShortcuts();
        L('help_modal:' + (!document.getElementById('shortcut-help').classList.contains('hidden') ? 1 : 0));
        Reader.closeShortcuts();
        // Ctrl+F 打开侧栏搜索
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'f', ctrlKey: true, bubbles: true }));
        await new Promise(r => setTimeout(r, 100));
        L('ctrl_f:' + (Sidebar.isOpen() && document.getElementById('tab-search').classList.contains('active') ? 1 : 0));
        Sidebar.close();
        // 图片点击放大
        Reader.openImage('/cover/__BID__');
        await new Promise(r => setTimeout(r, 100));
        L('lightbox:' + (!document.getElementById('image-lightbox').classList.contains('hidden') ? 1 : 0));
        Reader.closeImage();
        // 最近阅读横条 / 列表视图 / 排序
        App.showShelf();
        await new Promise(r => setTimeout(r, 200));
        await Bridge.call('save_progress', '__BID__', 2, 300);
        await Shelf.render();
        L('recent:' + (document.querySelectorAll('#recent-strip .recent-card').length > 0 ? 1 : 0));
        App.state.settings.shelf_view = 'list';
        await Shelf.render();
        L('list_view:' + (document.getElementById('book-grid').classList.contains('list-view') ? 1 : 0));
        App.state.settings.shelf_view = 'grid';
        App.state.settings.shelf_sort = 'title';
        await Shelf.render();
        L('sort_control:' + (document.getElementById('shelf-sort').value === 'title' ? 1 : 0));
        App.state.settings.shelf_sort = 'recent';
      } catch (e) { L('ERROR:' + (e && e.message)); }
      window.__test.done = true;
    })()
    """

    results = {}
    _last_logs = []

    def run():
        time.sleep(3)
        st = None
        try:
            # simulate a leftover completed download (regression: reopening the panel must not jump to reader)
            nga_svc._set(running=False, stage="done", detail="done",
                         book_id=book.id, action="download")
            window.evaluate_js(JS.replace('__BID__', book.id))
            for _ in range(250):
                try:
                    st = window.evaluate_js("window.__test ? JSON.stringify(window.__test) : 'none'")
                except Exception:
                    st = None
                if st and st != 'none' and '"done":true' in st:
                    break
                time.sleep(0.15)
            data = json.loads(st) if st and st != 'none' else {"log": []}
            logs = data.get('log', [])
            _last_logs[:] = logs

            def get(name):
                for line in logs:
                    if line.startswith(name + ':'):
                        return line.split(':', 1)[1]
                return None

            # 差分断言
            lens_str = get('lens')
            results['diff_js_py'] = bool(lens_str) and [int(x) for x in lens_str.split(',')] == py_lens
            results['pages'] = bool(int(get('pages') or 0))
            results['page_next'] = bool(int(get('page_next') or 0))
            results['marks'] = bool(int(get('marks') or 0))
            results['bookmark'] = bool(int(get('bm') or 0))
            results['code_highlight'] = bool(int(get('codehl') or 0))
            results['stats'] = bool(int(get('stats') or 0))
            results['dual_active'] = bool(int(get('dual_active') or 0))
            results['dual_spread'] = bool(int(get('dual_spread') or 0))
            results['dual_pages'] = bool(int(get('dual_pages') or 0))
            results['dual_next'] = bool(int(get('dual_next') or 0))
            results['dual_auto'] = bool(int(get('dual_auto') or 0))
            results['dual_off'] = bool(int(get('dual_off') or 0))
            results['table_wrap'] = bool(int(get('table_wrap') or 0))
            results['frame_fit'] = bool(int(get('frame_fit') or 0))
            results['chrome_toggle'] = bool(int(get('chrome_toggle') or 0))
            results['chrome_show'] = bool(int(get('chrome_show') or 0))
            results['help_modal'] = bool(int(get('help_modal') or 0))
            results['ctrl_f'] = bool(int(get('ctrl_f') or 0))
            results['lightbox'] = bool(int(get('lightbox') or 0))
            results['recent'] = bool(int(get('recent') or 0))
            results['list_view'] = bool(int(get('list_view') or 0))
            results['sort_control'] = bool(int(get('sort_control') or 0))
            results['init'] = bool(int(get('init') or 0))
            results['nga_panel'] = bool(int(get('nga_panel') or 0))
            results['nga_reopen_no_jump'] = bool(int(get('nga_reopen_no_jump') or 0))
            results['nga_bridge'] = bool(int(get('nga_bridge') or 0))
            results['nga_flag'] = bool(int(get('nga_flag') or 0))
            results['nga_style'] = bool(int(get('nga_style') or 0))
        finally:
            try:
                window.destroy()
            except Exception:
                pass

    window.events.loaded += lambda: threading.Thread(target=run, daemon=True).start()
    webview.start(gui="edgechromium", debug=args.debug)

    print("=== UI 自动化验证 ===")
    all_ok = True
    for name in ['init', 'diff_js_py', 'pages', 'page_next', 'marks', 'bookmark',
                 'code_highlight', 'stats', 'dual_active', 'dual_spread',
                 'dual_pages', 'dual_next', 'chrome_toggle', 'chrome_show',
                 'help_modal', 'ctrl_f', 'lightbox', 'recent', 'list_view',
                 'sort_control', 'dual_auto', 'dual_off', 'table_wrap', 'frame_fit',
                 'nga_panel', 'nga_reopen_no_jump', 'nga_bridge', 'nga_flag', 'nga_style']:
        ok = results.get(name, False)
        all_ok = all_ok and ok
        print(f"  {name:14s} {'PASS' if ok else 'FAIL'}")
    # 输出诊断日志（供 FAIL 排查）
    if not all_ok:
        print("  --- JS 日志 ---")
        for line in _last_logs:
            print("   ", line)

    books.close_all()
    tmp.cleanup()
    return 0 if all_ok else 1


if __name__ == "__main__":
    rc = main()
    sys.stdout.flush()
    os._exit(rc)
