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
CONTRACTS = PROJECT / "contracts"


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
              search=search, annotations=ann, stats=stats, nga_service=nga_svc,
              window_toggle=lambda _entering: None)
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
      const activeDoc = () => (document.querySelector('.chapter-frame.active') || document.getElementById('chapter-frame')).contentDocument;
      try {
        await App.init();
        L('init:1');
        L('default_scroll:' + (!App.state.settings.pagination ? 1 : 0));
        // NGA 下载面板
        document.getElementById('nga-download-btn').click();
        await new Promise(r => setTimeout(r, 800));
        const dpanel = document.getElementById('download-view');
        L('nga_panel:' + (!dpanel.classList.contains('hidden') && document.getElementById('nga-tid') ? 1 : 0));
        L('gululu_panel:' + (
          document.getElementById('dl-panel-dl-gululu').classList.contains('active') &&
          document.getElementById('gululu-source') &&
          document.getElementById('gululu-image-mode') &&
          document.getElementById('gululu-start') &&
          document.getElementById('gululu-update') &&
          document.getElementById('gululu-export') ? 1 : 0
        ));
        L('nga_reopen_no_jump:' + (App.state.view === 'shelf' ? 1 : 0));
        const dlTabs = document.querySelectorAll('#download-view .download-tab');
        L('dl_tabs:' + (dlTabs.length === 5 ? 1 : 0));
        const gululuStatus = await Bridge.call('gululu_import_status');
        L('gululu_bridge:' + (gululuStatus && gululuStatus.stage === 'idle' ? 1 : 0));
        document.querySelector('#download-view .download-tab[data-tab="dl-config"]').click();
        await new Promise(r => setTimeout(r, 50));
        L('dl_tab_config:' + (document.getElementById('dl-panel-dl-config').classList.contains('active') ? 1 : 0));
        document.querySelector('#download-view .download-tab[data-tab="dl-download"]').click();
        const uSel = document.getElementById('dl-update-book');
        L('update_panel:' + (uSel && uSel.options.length > 1 ? 1 : 0));
        uSel.value = '__BID__';
        uSel.dispatchEvent(new Event('change'));
        await new Promise(r => setTimeout(r, 200));
        L('update_defaults:' + (
          document.getElementById('dl-update-authorid').value === '0' &&
          document.getElementById('dl-update-theme').value === 'light' ? 1 : 0
        ));
        L('update_btn:' + (!!document.getElementById('dl-update-start') ? 1 : 0));
        L('toc_mode_ui:' + (!!document.getElementById('nga-toc-mode') ? 1 : 0));
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
        const ov = activeDoc().getElementById('__reader_overrides__');
        L('nga_flag:' + (App.state.book && App.state.book.nga ? 1 : 0));
        L('nga_style:' + (ov && ov.textContent.indexOf('color: var(--reader-fg)') === -1 ? 1 : 0));
        // 深色模式稳态：iframe 画布透明、透出深色阅读底（无白画布残留）
        const darkDoc = activeDoc();
        const darkHtmlBg = getComputedStyle(darkDoc.documentElement).backgroundColor;
        const darkBodyBg = getComputedStyle(darkDoc.body).backgroundColor;
        L('dark_bg_ok:' + (
          (darkHtmlBg === 'rgba(0, 0, 0, 0)' || darkHtmlBg === 'transparent') &&
          (darkBodyBg === 'rgba(0, 0, 0, 0)' || darkBodyBg === 'transparent') ? 1 : 0
        ));
        // 快速翻页：连续快速切换章节，最终状态正确且排版已加载
        for (const ci of [0, 2, 4, 1]) { Reader.loadChapter(ci, 0); }
        await new Promise(r => setTimeout(r, 1500));
        const flipDoc = activeDoc();
        L('rapid_flip_ok:' + (
          App.state.chapterIndex === 1 &&
          App.state.textCtx && App.state.textCtx.text.length > 0 &&
          flipDoc.getElementById('__reader_overrides__') ? 1 : 0
        ));
        // 分页（显式开启分页后测试分页逻辑；默认滚动由 default_scroll 断言覆盖）
        await Bridge.call('save_settings', { pagination: true, auto_dual: false });
        App.state.settings.pagination = true;
        App.state.settings.auto_dual = false;
        await Reader.loadChapter(2, 0);
        await new Promise(r => setTimeout(r, 1000));
        const m = Paged.measure();
        L('pages:' + (m.total > 1 ? m.total : 0));
        Paged.nextPage(false);
        await new Promise(r => setTimeout(r, 200));
        const m2 = Paged.measure();
        L('page_next:' + (m2.current === m.current + 1 ? 1 : 0));
        const resizeOffset = Paged.currentAnchorOffset();
        Paged.onResize();
        await new Promise(r => setTimeout(r, 200));
        const resized = Paged.measure();
        const resizedOffset = Paged.currentAnchorOffset();
        L('paged_resize_position:' + (
          resizeOffset > 0 && resized.current > 0 && resizedOffset > 0 ? 1 : 0
        ));
        const pagedBodyStyle = getComputedStyle(activeDoc().body);
        L('paged_edge_clip:' + (
          pagedBodyStyle.clipPath && pagedBodyStyle.clipPath !== 'none' ? 1 : 0
        ));
        const nowrapProbe = activeDoc().createElement('span');
        nowrapProbe.style.whiteSpace = 'nowrap';
        nowrapProbe.textContent = '单行过长文本'.repeat(200);
        activeDoc().body.appendChild(nowrapProbe);
        L('paged_long_line_wrap:' + (
          getComputedStyle(nowrapProbe).whiteSpace === 'normal' ? 1 : 0
        ));
        nowrapProbe.remove();
        // 标注
        await Bridge.call('save_annotation', '__BID__', 2, 500, 560, '引力波源位于遥远的星系中心', 'yellow', '');
        await Annotations.refresh();
        await Reader.loadChapter(2, 0);
        await new Promise(r => setTimeout(r, 1000));
        const marks = activeDoc().querySelectorAll('mark.hl-mark');
        L('marks:' + (marks.length === 1 ? 1 : 0));
        // 书签
        await Bridge.call('add_bookmark', '__BID__', 2, 700, '书签');
        const annData = await Bridge.call('get_annotations', '__BID__');
        L('bm:' + (annData.bookmarks.length === 1 ? 1 : 0));
        // 代码高亮
        const preCode = activeDoc().querySelector('pre code.syntax');
        L('codehl:' + (preCode ? 1 : 0));
        // 统计
        await Bridge.call('record_reading', '__BID__', 60, 3);
        const st = await Bridge.call('get_stats', '__BID__');
        L('stats:' + (st.book.total_seconds === 60 ? 1 : 0));
        await Stats.showDetails();
        await new Promise(r => setTimeout(r, 150));
        const spSel = document.getElementById('stats-book-select');
        L('stats_modal:' + (spSel ? 1 : 0));
        L('stats_default_all:' + (spSel && spSel.value === '' ? 1 : 0));
        L('stats_book_cards:' + (
          document.querySelectorAll('#stats-book-list-wrap .stats-book-card').length > 0 ? 1 : 0
        ));
        const statsOverlay = document.querySelector('#modal-root .modal-overlay');
        if (statsOverlay) statsOverlay.click();
        await new Promise(r => setTimeout(r, 100));
        Sidebar.switchTab('stats');
        await new Promise(r => setTimeout(r, 250));
        L('stats_side_tab:' + (document.getElementById('tab-stats').classList.contains('active') ? 1 : 0));
        L('stats_side_total:' + (document.querySelectorAll('#tab-stats .side-stats-total').length > 0 ? 1 : 0));
        Sidebar.switchTab('toc');
        // 横屏双页模式
        await Bridge.call('save_settings', { pagination: true, dual_page: true });
        App.state.settings.pagination = true;
        App.state.settings.dual_page = true;
        await Reader.loadChapter(2, 0);
        await new Promise(r => setTimeout(r, 1200));
        L('dual_active:' + (Paged.isDual && Paged.isDual() ? 1 : 0));
        // 分页模式：底部章导航不占空间，正文占满舞台；点击翻页热区已移除
        const cwrap = document.querySelector('.chapter-wrap');
        const navRow = cwrap ? cwrap.querySelector('.chapter-nav-row') : null;
        const wrapCs = cwrap ? getComputedStyle(cwrap) : null;
        L('paged_no_bottom_nav:' + (navRow && navRow.offsetParent === null ? 1 : 0));
        L('paged_full_stage:' + (
          wrapCs && parseFloat(wrapCs.paddingTop) === 0 && parseFloat(wrapCs.paddingBottom) === 0 ? 1 : 0
        ));
        L('no_hot_zones:' + (
          !document.getElementById('hot-left') && !document.getElementById('hot-right') ? 1 : 0
        ));
        const navBtn = document.querySelector('.page-nav-btn');
        const navCs = navBtn ? getComputedStyle(navBtn) : null;
        L('nav_strip_shape:' + (
          navCs && parseFloat(navCs.width) < 40 && parseFloat(navCs.height) > 60 &&
          navCs.borderRadius !== '50%' ? 1 : 0
        ));
        // 分页模式 iframe 铺满舞台：文档内边缘 mousemove 也能唤出顶/底栏
        const idoc = activeDoc();
        idoc.dispatchEvent(new MouseEvent('mousemove', { clientX: 240, clientY: 4, bubbles: true }));
        await new Promise(r => setTimeout(r, 60));
        L('paged_edge_show:' + (document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0));
        App.hideBarsForAction();
        L('action_hide_bars:' + (!document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0));
        // 顶栏收起时二级菜单卡片自动收起
        App.setBarsVisible(true);
        document.getElementById('view-menu-btn').click();
        await new Promise(r => setTimeout(r, 120));
        const vmEl2 = document.getElementById('view-menu');
        App.hideBarsForAction();
        L('menu_close_on_hide:' + (vmEl2 && vmEl2.classList.contains('hidden') ? 1 : 0));
        // 固定顶/底栏：固定后中部移动与翻页操作都不应收起
        App.setBarsVisible(true);
        document.getElementById('bars-pin-btn').click();
        await new Promise(r => setTimeout(r, 100));
        L('pin_active:' + (
          document.getElementById('reader-view').classList.contains('bars-pinned') &&
          document.getElementById('bars-pin-btn').classList.contains('active') ? 1 : 0
        ));
        idoc.dispatchEvent(new MouseEvent('mousemove', { clientX: 240, clientY: 300, bubbles: true }));
        App.hideBarsForAction();
        await new Promise(r => setTimeout(r, 750));
        L('pin_keeps_bars:' + (document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0));
        document.getElementById('bars-pin-btn').click();
        await new Promise(r => setTimeout(r, 100));
        L('pin_unpin:' + (!document.getElementById('reader-view').classList.contains('bars-pinned') ? 1 : 0));
        // 固定后点击正文不应解除固定
        document.getElementById('bars-pin-btn').click();
        await new Promise(r => setTimeout(r, 100));
        idoc.dispatchEvent(new MouseEvent('click', { clientX: 300, clientY: 300, bubbles: true }));
        await new Promise(r => setTimeout(r, 50));
        L('pin_survives_click:' + (
          document.getElementById('reader-view').classList.contains('bars-pinned') ? 1 : 0
        ));
        document.getElementById('bars-pin-btn').click();
        // 分页 iframe 尺寸不得超出可视区域（曾因 chapter-wrap 内边距导致错位/裁切）
        const actFrame = document.querySelector('.chapter-frame.active') || document.getElementById('chapter-frame');
        const fr = actFrame.getBoundingClientRect();
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
        // 双页末屏可达且非空白（此前偶数列不足导致最后一跨无法滚动到达/拼接错乱）
        const dm3 = Paged.measure();
        Paged.gotoPage(dm3.total - 1);
        await new Promise(r => setTimeout(r, 200));
        const dmEnd = Paged.measure();
        L('dual_last_reachable:' + (
          dmEnd.current === dm3.total - 1 && !Paged.isPageBlank(dmEnd.current) ? 1 : 0
        ));
        const fdoc = activeDoc();
        const fEl = document.getElementById('chapter-frame');
        L('dual_no_overflow:' + (fdoc.documentElement.scrollWidth <= fEl.clientWidth + 1 ? 1 : 0));
        const fcs = getComputedStyle(fdoc.body);
        const fM = parseFloat(fcs.paddingLeft) || 0;
        const fcol = parseFloat(fcs.columnWidth) || 0;
        const fgap = parseFloat(fcs.columnGap) || 0;
        L('dual_debug:' + JSON.stringify({
          cols: Math.max(1, Math.round((fdoc.body.scrollWidth - 2 * fM + fgap) / (fcol + fgap))),
          scrollW: fdoc.body.scrollWidth,
          clientW: fdoc.body.clientWidth,
          total: dm3.total,
          step: dm3.step,
        }));
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
        const tdoc = activeDoc();
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
        App.setBarsVisible(true);  // 显式前置状态，避免悬停隐藏定时器造成时序抖动
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
        // ---- 独立全文检索页 ----
        // 顶栏按钮打开
        document.getElementById('search-page-btn').click();
        await new Promise(r => setTimeout(r, 80));
        L('fs_btn:' + (window.FullSearch && FullSearch.isOpen() ? 1 : 0));
        // 高频词 "e"：全部 5 章都出结果（修复“只显示靠前结果”），第三章 131 处触发每章限量
        const fsInput = document.getElementById('fs-input');
        fsInput.value = 'e';
        await FullSearch.query(true);
        for (let i = 0; i < 20 && document.querySelectorAll('#fs-results .fs-group').length === 0; i++) {
          await new Promise(r => setTimeout(r, 150));
        }
        const fsGroups = document.querySelectorAll('#fs-results .fs-group');
        const fsLast = fsGroups.length ? fsGroups[fsGroups.length - 1].dataset.chapter : '-1';
        L('fs_groups_all:' + (fsGroups.length === 5 ? 1 : 0));
        L('fs_last_chapter:' + (fsLast === '4' ? 1 : 0));
        const fsSummary = document.getElementById('fs-summary');
        L('fs_summary:' + (/共 \\d+ 处命中 · 5\\/5 章有结果/.test(fsSummary.textContent) ? 1 : 0));
        L('fs_more:' + (document.querySelectorAll('#fs-results .fs-more').length > 0 ? 1 : 0));
        L('fs_history:' + (document.querySelectorAll('#fs-history .fs-history-chip').length > 0 ? 1 : 0));
        // 章节组折叠/展开
        const g3 = document.querySelector('#fs-results .fs-group[data-chapter="2"]');
        g3.querySelector('.fs-group-head').click();
        await new Promise(r => setTimeout(r, 50));
        L('fs_collapse:' + (!g3.classList.contains('open') ? 1 : 0));
        g3.querySelector('.fs-group-head').click();
        await new Promise(r => setTimeout(r, 50));
        L('fs_expand:' + (g3.classList.contains('open') ? 1 : 0));
        // 加载更多：第三章初始 50 条 → 续取后更多
        const beforeMore = g3.querySelectorAll('.fs-hit').length;
        g3.querySelector('.fs-more').click();
        await new Promise(r => setTimeout(r, 700));
        const afterMore = g3.querySelectorAll('.fs-hit').length;
        L('fs_load_more:' + (afterMore > beforeMore ? 1 : 0));
        // 点击结果跳转并自动关闭检索页
        g3.querySelector('.fs-hit').click();
        await new Promise(r => setTimeout(r, 900));
        L('fs_jump:' + (!FullSearch.isOpen() && App.state.chapterIndex === 2 ? 1 : 0));
        // Ctrl+F 打开独立检索页（替代旧侧栏搜索）
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'f', ctrlKey: true, bubbles: true }));
        await new Promise(r => setTimeout(r, 100));
        L('ctrl_f:' + (FullSearch.isOpen() ? 1 : 0));
        FullSearch.close();
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
        // 滚动阅读语义：不分页，底栏不显示上/下一页按钮，整章滚动到底切换章节
        App.state.settings.pagination = false;
        await Bridge.call('save_settings', { pagination: false });
        await App.showReader('__BID__');
        await new Promise(r => setTimeout(r, 800));
        const ppBtn = document.getElementById('page-prev');
        L('scroll_no_page_btn:' + (
          !Paged.isActive() && ppBtn && getComputedStyle(ppBtn).display === 'none' ? 1 : 0
        ));
        // 滚动模式固定顶/底栏同样生效
        App.setBarsVisible(true);
        document.getElementById('bars-pin-btn').click();
        await new Promise(r => setTimeout(r, 100));
        L('pin_scroll_active:' + (
          document.getElementById('reader-view').classList.contains('bars-pinned') ? 1 : 0
        ));
        document.getElementById('chapter-scroll').dispatchEvent(
          new MouseEvent('mousemove', { clientX: 240, clientY: 300, bubbles: true })
        );
        App.hideBarsForAction();
        await new Promise(r => setTimeout(r, 750));
        L('pin_scroll_keeps:' + (
          document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0
        ));
        document.getElementById('bars-pin-btn').click();
        // 滚动模式：边缘触发区横向限为书籍实际显示区域
        App.setBarsVisible(false);
        const swrapRect = document.querySelector('.chapter-wrap').getBoundingClientRect();
        document.getElementById('chapter-scroll').dispatchEvent(new MouseEvent('mousemove', {
          clientX: Math.round(swrapRect.right + 40), clientY: 4, bubbles: true,
        }));
        await new Promise(r => setTimeout(r, 80));
        L('scroll_edge_outside:' + (
          !document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0
        ));
        document.getElementById('chapter-scroll').dispatchEvent(new MouseEvent('mousemove', {
          clientX: Math.round(swrapRect.left + 40), clientY: 4, bubbles: true,
        }));
        await new Promise(r => setTimeout(r, 80));
        L('scroll_edge_inside:' + (
          document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0
        ));
        // 滚动模式：滚轮立即收栏（不等 600ms 延迟）
        document.getElementById('chapter-scroll').dispatchEvent(new WheelEvent('wheel', {
          deltaY: 120, bubbles: true, cancelable: true,
        }));
        await new Promise(r => setTimeout(r, 60));
        L('wheel_hides_bars:' + (
          !document.getElementById('top-bar').classList.contains('bar-visible') ? 1 : 0
        ));
        // 个性化颜色：自定义主题色叠加生效；主题按钮切换主题时保留自定义色
        App.setBarsVisible(true);
        App.state.settings.custom_primary = '#ff0000';
        Theme.applyTheme(App.state.settings.theme, App.state.settings);
        L('custom_primary_applied:' + (
          getComputedStyle(document.documentElement).getPropertyValue('--primary').trim().toLowerCase() === '#ff0000' ? 1 : 0
        ));
        const thBefore = App.state.settings.theme;
        document.getElementById('theme-btn2').click();
        L('theme_btn_cycles:' + (App.state.settings.theme !== thBefore ? 1 : 0));
        L('theme_keeps_custom:' + (
          getComputedStyle(document.documentElement).getPropertyValue('--primary').trim().toLowerCase() === '#ff0000' ? 1 : 0
        ));
        App.state.settings.custom_primary = '';
        Theme.applyTheme(App.state.settings.theme, App.state.settings);
        SettingsPage.open();
        await new Promise(r => setTimeout(r, 120));
        L('custom_colors_ui:' + (
          !!document.getElementById('sp-custom-bg') && !!document.getElementById('sp-custom-text') ? 1 : 0
        ));
        L('settings_tabs:' + (document.querySelectorAll('#settings-view .settings-tab').length === 6 ? 1 : 0));
        L('palette_btns:' + (document.querySelectorAll('#settings-view .sp-palette-btn').length > 0 ? 1 : 0));
        document.querySelector('#settings-view .settings-tab[data-tab="reading"]').click();
        await new Promise(r => setTimeout(r, 80));
        L('settings_tab_switch:' + (document.getElementById('sp-panel-reading').classList.contains('active') ? 1 : 0));
        document.querySelector('#settings-view .settings-tab[data-tab="appearance"]').click();
        await new Promise(r => setTimeout(r, 50));
        const nordBtn = document.querySelector('#settings-view .sp-palette-btn[data-palette="nord-dark"]');
        nordBtn.click();
        await new Promise(r => setTimeout(r, 100));
        L('palette_applied:' + (
          App.state.settings.custom_bg === '#2e3440' && App.state.settings.custom_text === '#eceff4' &&
          getComputedStyle(document.documentElement).getPropertyValue('--reader-bg').trim().toLowerCase() === '#2e3440' ? 1 : 0
        ));
        document.getElementById('sp-palette-reset').click();
        await new Promise(r => setTimeout(r, 80));
        L('palette_reset:' + (App.state.settings.custom_bg === '' ? 1 : 0));
        document.querySelector('#settings-view .sp-theme-mode-btn[data-mode="system"]').click();
        await new Promise(r => setTimeout(r, 80));
        L('theme_mode_system:' + (App.state.settings.theme_mode === 'system' ? 1 : 0));
        document.querySelector('#settings-view .sp-theme-mode-btn[data-mode="dark"]').click();
        await new Promise(r => setTimeout(r, 80));
        L('theme_mode_dark:' + (App.state.settings.theme_mode === 'dark' ? 1 : 0));
        SettingsPage.close();
        // 沉浸式阅读（全屏）：按钮存在，切换 immersive 状态
        const fsBtn = document.getElementById('fullscreen-btn');
        L('fullscreen_btn:' + (!!fsBtn ? 1 : 0));
        await App.toggleImmersive();
        L('immersive_on:' + (document.getElementById('reader-view').classList.contains('immersive') ? 1 : 0));
        L('immersive_toast:' + (
          document.getElementById('toast-container').textContent.indexOf('Esc 或 F11') !== -1 ? 1 : 0
        ));
        await App.toggleImmersive();
        L('immersive_off:' + (!document.getElementById('reader-view').classList.contains('immersive') ? 1 : 0));
        // Esc 退出沉浸式
        await App.toggleImmersive();
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        await new Promise(r => setTimeout(r, 80));
        L('immersive_esc_exit:' + (
          !App.state.immersive && !document.getElementById('reader-view').classList.contains('immersive') ? 1 : 0
        ));
        // 返回书架自动退出沉浸式
        await App.toggleImmersive();
        App.showShelf();
        await new Promise(r => setTimeout(r, 120));
        L('shelf_exits_immersive:' + (
          !App.state.immersive && !document.getElementById('reader-view').classList.contains('immersive') ? 1 : 0
        ));
      } catch (e) { L('ERROR:' + (e && e.message)); }
      window.__test.done = true;
    })()
    """

    results = {}
    _contract_failures = []
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

            # ---- 契约文本用例：真实 WebView 内运行 textpos.js（对照 contracts/text/text-cases.json） ----
            _cases = json.loads((CONTRACTS / "text" / "text-cases.json").read_text(encoding="utf-8"))["cases"]
            JS_CASES = r"""
            (() => {
              const out = [];
              for (const c of window.__cases) {
                const f = document.createElement('iframe');
                f.style.display = 'none';
                document.body.appendChild(f);
                const d = f.contentDocument;
                d.open();
                d.write('<html><body>' + c.html + '</body></html>');
                d.close();
                const ctx = TextPos.build(d);
                const offsets = (c.points || []).map(p => ctx.text.indexOf(p.quote));
                out.push({
                  id: c.id, text: ctx.text, offsets: offsets,
                  textOk: ctx.text === c.expected,
                  pointsOk: (c.points || []).every(
                    (p, pi) => offsets[pi] === p.offset
                  ),
                });
                f.remove();
              }
              return JSON.stringify(out);
            })()
            """
            try:
                raw2 = window.evaluate_js(
                    "window.__cases = " + json.dumps(_cases, ensure_ascii=False) + ";" + JS_CASES
                )
                js_cases_result = json.loads(raw2)
            except Exception:
                js_cases_result = []
            for r, c in zip(js_cases_result, _cases):
                if r.get('text') != c['expected']:
                    _contract_failures.append(
                        f"text {c['id']}: got={r.get('text')!r} expected={c['expected']!r}"
                    )
                for pi, p in enumerate(c.get('points', [])):
                    if r.get('offsets', [])[pi:pi + 1] != [p['offset']]:
                        _contract_failures.append(
                            f"point {c['id']}: got={r.get('offsets')} expected={p['offset']}"
                        )
            results['contract_text_cases'] = (
                len(js_cases_result) == len(_cases) and all(
                    r.get('text') == c['expected'] for r, c in zip(js_cases_result, _cases)
                )
            )
            results['contract_js_points'] = len(js_cases_result) == len(_cases) and all(
                r['offsets'][pi] == p['offset']
                for r, c in zip(js_cases_result, _cases)
                for pi, p in enumerate(c.get('points', []))
            )
            results['contract_js_astral_utf16'] = bool(js_cases_result) and any(
                r.get('id') == 'astral' and r.get('offsets') == [3]
                for r in js_cases_result
            )

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
            results['paged_resize_position'] = bool(int(get('paged_resize_position') or 0))
            results['paged_edge_clip'] = bool(int(get('paged_edge_clip') or 0))
            results['paged_long_line_wrap'] = bool(int(get('paged_long_line_wrap') or 0))
            results['marks'] = bool(int(get('marks') or 0))
            results['bookmark'] = bool(int(get('bm') or 0))
            results['code_highlight'] = bool(int(get('codehl') or 0))
            results['stats'] = bool(int(get('stats') or 0))
            results['stats_modal'] = bool(int(get('stats_modal') or 0))
            results['stats_default_all'] = bool(int(get('stats_default_all') or 0))
            results['stats_book_cards'] = bool(int(get('stats_book_cards') or 0))
            results['stats_side_tab'] = bool(int(get('stats_side_tab') or 0))
            results['stats_side_total'] = bool(int(get('stats_side_total') or 0))
            results['dual_active'] = bool(int(get('dual_active') or 0))
            results['paged_no_bottom_nav'] = bool(int(get('paged_no_bottom_nav') or 0))
            results['paged_full_stage'] = bool(int(get('paged_full_stage') or 0))
            results['no_hot_zones'] = bool(int(get('no_hot_zones') or 0))
            results['nav_strip_shape'] = bool(int(get('nav_strip_shape') or 0))
            results['paged_edge_show'] = bool(int(get('paged_edge_show') or 0))
            results['action_hide_bars'] = bool(int(get('action_hide_bars') or 0))
            results['menu_close_on_hide'] = bool(int(get('menu_close_on_hide') or 0))
            results['pin_active'] = bool(int(get('pin_active') or 0))
            results['pin_keeps_bars'] = bool(int(get('pin_keeps_bars') or 0))
            results['pin_unpin'] = bool(int(get('pin_unpin') or 0))
            results['pin_survives_click'] = bool(int(get('pin_survives_click') or 0))
            results['pin_scroll_active'] = bool(int(get('pin_scroll_active') or 0))
            results['pin_scroll_keeps'] = bool(int(get('pin_scroll_keeps') or 0))
            results['scroll_edge_outside'] = bool(int(get('scroll_edge_outside') or 0))
            results['scroll_edge_inside'] = bool(int(get('scroll_edge_inside') or 0))
            results['wheel_hides_bars'] = bool(int(get('wheel_hides_bars') or 0))
            results['custom_primary_applied'] = bool(int(get('custom_primary_applied') or 0))
            results['theme_btn_cycles'] = bool(int(get('theme_btn_cycles') or 0))
            results['theme_keeps_custom'] = bool(int(get('theme_keeps_custom') or 0))
            results['custom_colors_ui'] = bool(int(get('custom_colors_ui') or 0))
            results['settings_tabs'] = bool(int(get('settings_tabs') or 0))
            results['palette_btns'] = bool(int(get('palette_btns') or 0))
            results['settings_tab_switch'] = bool(int(get('settings_tab_switch') or 0))
            results['palette_applied'] = bool(int(get('palette_applied') or 0))
            results['palette_reset'] = bool(int(get('palette_reset') or 0))
            results['theme_mode_system'] = bool(int(get('theme_mode_system') or 0))
            results['theme_mode_dark'] = bool(int(get('theme_mode_dark') or 0))
            results['fullscreen_btn'] = bool(int(get('fullscreen_btn') or 0))
            results['immersive_on'] = bool(int(get('immersive_on') or 0))
            results['immersive_off'] = bool(int(get('immersive_off') or 0))
            results['immersive_toast'] = bool(int(get('immersive_toast') or 0))
            results['immersive_esc_exit'] = bool(int(get('immersive_esc_exit') or 0))
            results['shelf_exits_immersive'] = bool(int(get('shelf_exits_immersive') or 0))
            results['dual_spread'] = bool(int(get('dual_spread') or 0))
            results['dual_pages'] = bool(int(get('dual_pages') or 0))
            results['dual_next'] = bool(int(get('dual_next') or 0))
            results['dual_last_reachable'] = bool(int(get('dual_last_reachable') or 0))
            results['dual_no_overflow'] = bool(int(get('dual_no_overflow') or 0))
            results['dual_auto'] = bool(int(get('dual_auto') or 0))
            results['dual_off'] = bool(int(get('dual_off') or 0))
            results['table_wrap'] = bool(int(get('table_wrap') or 0))
            results['frame_fit'] = bool(int(get('frame_fit') or 0))
            results['chrome_toggle'] = bool(int(get('chrome_toggle') or 0))
            results['chrome_show'] = bool(int(get('chrome_show') or 0))
            results['help_modal'] = bool(int(get('help_modal') or 0))
            results['fs_btn'] = bool(int(get('fs_btn') or 0))
            results['fs_groups_all'] = bool(int(get('fs_groups_all') or 0))
            results['fs_last_chapter'] = bool(int(get('fs_last_chapter') or 0))
            results['fs_summary'] = bool(int(get('fs_summary') or 0))
            results['fs_more'] = bool(int(get('fs_more') or 0))
            results['fs_history'] = bool(int(get('fs_history') or 0))
            results['fs_collapse'] = bool(int(get('fs_collapse') or 0))
            results['fs_expand'] = bool(int(get('fs_expand') or 0))
            results['fs_load_more'] = bool(int(get('fs_load_more') or 0))
            results['fs_jump'] = bool(int(get('fs_jump') or 0))
            results['ctrl_f'] = bool(int(get('ctrl_f') or 0))
            results['lightbox'] = bool(int(get('lightbox') or 0))
            results['recent'] = bool(int(get('recent') or 0))
            results['list_view'] = bool(int(get('list_view') or 0))
            results['sort_control'] = bool(int(get('sort_control') or 0))
            results['init'] = bool(int(get('init') or 0))
            results['default_scroll'] = bool(int(get('default_scroll') or 0))
            results['nga_panel'] = bool(int(get('nga_panel') or 0))
            results['gululu_panel'] = bool(int(get('gululu_panel') or 0))
            results['gululu_bridge'] = bool(int(get('gululu_bridge') or 0))
            results['nga_reopen_no_jump'] = bool(int(get('nga_reopen_no_jump') or 0))
            results['dl_tabs'] = bool(int(get('dl_tabs') or 0))
            results['dl_tab_config'] = bool(int(get('dl_tab_config') or 0))
            results['update_panel'] = bool(int(get('update_panel') or 0))
            results['update_defaults'] = bool(int(get('update_defaults') or 0))
            results['update_btn'] = bool(int(get('update_btn') or 0))
            results['toc_mode_ui'] = bool(int(get('toc_mode_ui') or 0))
            results['scroll_no_page_btn'] = bool(int(get('scroll_no_page_btn') or 0))
            results['nga_bridge'] = bool(int(get('nga_bridge') or 0))
            results['nga_flag'] = bool(int(get('nga_flag') or 0))
            results['nga_style'] = bool(int(get('nga_style') or 0))
            results['dark_bg_ok'] = bool(int(get('dark_bg_ok') or 0))
            results['rapid_flip_ok'] = bool(int(get('rapid_flip_ok') or 0))
        finally:
            try:
                window.destroy()
            except Exception:
                pass

    window.events.loaded += lambda: threading.Thread(target=run, daemon=True).start()
    webview.start(gui="edgechromium", debug=args.debug)

    print("=== UI 自动化验证 ===")
    all_ok = True
    for name in ['init', 'default_scroll', 'diff_js_py', 'contract_text_cases',
                 'contract_js_points', 'contract_js_astral_utf16',
                  'pages', 'page_next', 'paged_resize_position', 'paged_edge_clip',
                  'paged_long_line_wrap',
                 'marks', 'bookmark',
                 'code_highlight', 'stats', 'stats_modal', 'stats_default_all',
                 'stats_book_cards', 'stats_side_tab', 'stats_side_total',
                 'dual_active', 'paged_no_bottom_nav', 'paged_full_stage',
                 'no_hot_zones', 'nav_strip_shape', 'paged_edge_show',
                 'action_hide_bars', 'menu_close_on_hide', 'pin_active',
                 'pin_keeps_bars', 'pin_unpin', 'pin_survives_click',
                 'pin_scroll_active', 'pin_scroll_keeps',
                 'scroll_edge_outside', 'scroll_edge_inside', 'wheel_hides_bars',
                 'custom_primary_applied', 'theme_btn_cycles', 'theme_keeps_custom',
                 'custom_colors_ui',
                 'settings_tabs', 'palette_btns', 'settings_tab_switch',
                 'palette_applied', 'palette_reset', 'theme_mode_system', 'theme_mode_dark',
                 'fullscreen_btn', 'immersive_on', 'immersive_off',
                 'immersive_toast', 'immersive_esc_exit', 'shelf_exits_immersive',
                 'dual_spread',
                 'dual_pages', 'dual_next', 'dual_last_reachable', 'dual_no_overflow',
                 'chrome_toggle', 'chrome_show',
                 'help_modal', 'fs_btn', 'fs_groups_all', 'fs_last_chapter',
                 'fs_summary', 'fs_more', 'fs_history', 'fs_collapse',
                 'fs_expand', 'fs_load_more', 'fs_jump', 'ctrl_f',
                 'lightbox', 'recent', 'list_view',
                 'sort_control', 'dual_auto', 'dual_off', 'table_wrap', 'frame_fit',
                  'nga_panel', 'gululu_panel', 'gululu_bridge',
                  'nga_reopen_no_jump', 'update_panel', 'update_defaults',
                 'update_btn', 'toc_mode_ui', 'scroll_no_page_btn',
                 'dl_tabs', 'dl_tab_config',
                 'nga_bridge', 'nga_flag', 'nga_style', 'dark_bg_ok', 'rapid_flip_ok']:
        ok = results.get(name, False)
        all_ok = all_ok and ok
        print(f"  {name:14s} {'PASS' if ok else 'FAIL'}")
    # 输出诊断日志（供 FAIL 排查）
    if not all_ok:
        print("  --- JS 日志 ---")
        for line in _last_logs:
            print("   ", line)
    if _contract_failures:
        print("  --- 契约用例细节 ---")
        for line in _contract_failures:
            print("   ", line)

    books.close_all()
    tmp.cleanup()
    return 0 if all_ok else 1


if __name__ == "__main__":
    rc = main()
    sys.stdout.flush()
    os._exit(rc)
