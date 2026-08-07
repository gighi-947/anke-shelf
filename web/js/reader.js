/**
 * Reader core: iframe chapter rendering, paginated/scroll modes, text_offset
 * progress tracking and the Readest-style footer slider.
 */
(function () {
  'use strict';

  const BASE_OVERRIDE = `
    body {
      font-family: var(--reader-font-family, "Segoe UI", "Microsoft YaHei", serif) !important;
      font-size: var(--reader-font-size, 18px) !important;
      line-height: var(--reader-line-height, 1.8) !important;
      color: var(--reader-fg, #222) !important;
      background: transparent !important;
      margin: 0 !important;
      padding: 0 !important;
      overflow-wrap: anywhere !important;
      word-break: break-word !important;
    }
    p { margin: 0.6em 0; }
    img { max-width: 100% !important; height: auto !important; }
    a { color: var(--reader-accent, #77bbee) !important; }
    h1, h2, h3, h4 { line-height: 1.4 !important; }
    pre { overflow-x: auto; }
    table { max-width: 100%; }
  `;

  // NGA books keep their original thread styles (floor cards, quotes, colors);
  // only typography and image responsiveness are managed here.
  const NGA_OVERRIDE = `
    body {
      font-family: var(--reader-font-family, "Segoe UI", "Microsoft YaHei", serif) !important;
      font-size: var(--reader-font-size, 18px) !important;
      line-height: var(--reader-line-height, 1.8) !important;
      /* 默认文字色跟随阅读器主题：深色页→浅色字，浅色页→深色字。
         仅作用于 body 继承的默认色；楼层内的彩色/灰色字体不受影响。 */
      color: var(--reader-fg, #222) !important;
      background: transparent !important;
      overflow-wrap: anywhere !important;
      word-break: break-word !important;
    }
    img { max-width: 100% !important; height: auto !important; }
    pre { overflow-x: auto; }
    table { max-width: 100%; }
  `;

  const PAGINATION_OVERRIDE = `
    html, body {
      height: 100% !important;
      width: 100% !important;
      margin: 0 !important;
      overflow: hidden !important;
      box-sizing: border-box !important;
    }
    body {
      padding: 20px var(--margin-px, 40px) !important;
      column-width: var(--col-px, 600px) !important;
      column-gap: var(--gap-px, 28px) !important;
      column-fill: auto !important;
    }
    pre { white-space: pre-wrap !important; word-break: break-all; }
    img, video {
      max-width: 100% !important;
      max-height: 72vh !important;
      object-fit: contain;
      break-inside: avoid;
    }
    p { margin: 0.45em 0 !important; }
    /* NGA 楼层/表格/引用等必须允许跨页拆分：楼层里的长表格常超过一页高度，
       若整栋楼禁止分页，表格会整体溢出列边界，导致页面出界与错位。 */
    .nga-floor, .nga-quote, .nga-comment, blockquote, table, details {
      margin: 10px 0 !important;
      break-inside: auto !important;
    }
    .nga-floor { padding: 10px 12px !important; }
    table { max-width: 100% !important; }
    td, th {
      max-width: 100% !important;
      overflow-wrap: anywhere !important;
      word-break: break-word !important;
    }
    .nga-table-scroll {
      max-width: 100% !important;
      overflow: auto !important;
    }
  `;

  const scroller = () => document.getElementById('chapter-scroll');
  const frameEl = () => document.getElementById('chapter-frame');
  const overlayRoot = () => {
    let root = document.getElementById('overlay-root');
    if (!root) {
      root = document.createElement('div');
      root.id = 'overlay-root';
      document.body.appendChild(root);
    }
    return root;
  };

  // 超过该纯文本长度的章节不启用分页（CSS 多栏渲染超大章节会占用数 GB 内存），
  // 自动退回滚动阅读；阅读进度与标注仍按章节级工作。
  const MAX_PAGED_TEXT = 800000;

  // 快捷键帮助弹窗内容（与 settings.js 的默认值保持一致；此处只做展示兜底）
  const HELP_SHORTCUTS = {
    next_page: 'ArrowRight',
    prev_page: 'ArrowLeft',
    next_chapter: 'ArrowDown',
    prev_chapter: 'ArrowUp',
    toggle_theme: 't',
    toggle_sidebar: 's',
    toggle_bars: 'b',
    bookmark: 'm',
    help: '?',
    toggle_fullscreen: 'F11',
  };
  const HELP_ACTIONS = [
    ['next_page', '下一页 / 下一章'],
    ['prev_page', '上一页 / 上一章'],
    ['next_chapter', '下一章'],
    ['prev_chapter', '上一章'],
    ['toggle_theme', '切换主题'],
    ['toggle_sidebar', '开/关侧栏'],
    ['toggle_bars', '固定顶底栏'],
    ['bookmark', '书签'],
    ['help', '快捷键帮助'],
    ['toggle_fullscreen', '沉浸式阅读（全屏）'],
  ];

  function activeFontKey() {
    const s = App.state.settings || {};
    if (App.state.bookId && s.book_fonts && s.book_fonts[App.state.bookId]) {
      return s.book_fonts[App.state.bookId];
    }
    return s.custom_font || '';
  }

  function fontFaceCss() {
    const key = activeFontKey();
    if (!key) return '';
    let url = '';
    if (key.startsWith('sys:')) url = '/font/system/' + key.slice(4);
    else if (key.startsWith('custom:')) url = '/font/custom/' + key.slice(7);
    if (!url) return '';
    return '@font-face { font-family: "AnkeCustomFont"; src: url("' + url + '"); font-display: swap; }\n';
  }

  function resolveFamily() {
    const key = activeFontKey();
    if (key) return '"AnkeCustomFont", "Segoe UI", "Microsoft YaHei", serif';
    return '"Segoe UI", "Microsoft YaHei", serif';
  }

  function readVars() {
    const cs = getComputedStyle(document.getElementById('reader-root'));
    return {
      family: resolveFamily(),
      fontSize: cs.getPropertyValue('--reader-font-size').trim() || '18px',
      lineHeight: cs.getPropertyValue('--reader-line-height').trim() || '1.8',
      fg: cs.getPropertyValue('--reader-fg').trim() || '#e0e0e0',
      accent: cs.getPropertyValue('--reader-accent').trim() || '#77bbee',
    };
  }

  function applyOverrides(doc) {
    if (!doc || !doc.head) return;
    let el = doc.getElementById('__reader_overrides__');
    if (!el) {
      el = doc.createElement('style');
      el.id = '__reader_overrides__';
      doc.head.appendChild(el);
    }
    const v = readVars();
    const isNga = !!(App.state.book && App.state.book.nga);
    let css = isNga ? NGA_OVERRIDE : BASE_OVERRIDE;
    if (Paged.isActive()) css += PAGINATION_OVERRIDE;
    css = fontFaceCss() + css;
    el.textContent = css
      .replace('var(--reader-font-family, "Segoe UI", "Microsoft YaHei", serif)', v.family)
      .replace('var(--reader-font-size, 18px)', v.fontSize)
      .replace('var(--reader-line-height, 1.8)', v.lineHeight)
      .replace('var(--reader-fg, #222)', v.fg)
      .replace('var(--reader-accent, #77bbee)', v.accent);
  }

  function syncHeight() {
    const frame = frameEl();
    const doc = frame.contentDocument;
    if (!doc || !doc.body) return;
    // 滚动模式宽度回到 100%（分页模式写入的内联宽度只在分页时有效，
    // 否则切换回滚动模式后会残留，与窗口宽度不一致）。
    frame.style.width = '';
    const h = doc.documentElement.scrollHeight;
    if (frame.style.height !== h + 'px') frame.style.height = h + 'px';
  }

  function bindLinkHandler(doc) {
    doc.addEventListener('click', (ev) => {
      const a = ev.target && ev.target.closest ? ev.target.closest('a') : null;
      if (!a) return;
      const href = a.getAttribute('href') || '';
      if (!href || href.startsWith('#') || href.startsWith('javascript:')) return;
      ev.preventDefault();
      try {
        const abs = new URL(href, a.baseURI).pathname;
        const prefix = '/book/' + App.state.bookId + '/';
        if (abs.startsWith(prefix)) {
          const target = decodeURIComponent(abs.slice(prefix.length));
          const idx = App.state.book.chapters.findIndex((c) => c.href === target);
          if (idx !== -1) Reader.loadChapter(idx, 0);
        }
      } catch (e) { /* ignore non-URL hrefs */ }
    });
  }

  /** iframe 章节内的交互：图片点击放大；点击页面中央切换顶/底栏。 */
  function bindDocInteractions(doc) {
    doc.addEventListener('click', (ev) => {
      const t = ev.target;
      const img = t && t.closest ? t.closest('img') : null;
      const a = t && t.closest ? t.closest('a') : null;
      if (img) {
        // 图片链接（href 指向图片本身）也直接放大，不拦截普通外链。
        const href = a ? (a.getAttribute('href') || '') : '';
        let imgHref = img.currentSrc || img.src || '';
        let absHref = href;
        try {
          imgHref = new URL(imgHref, doc.baseURI).href;
          absHref = new URL(href, doc.baseURI).href;
        } catch (e) { /* 保留原值 */ }
        const linkToImg = href &&
          (href === img.getAttribute('src') || absHref === imgHref ||
           /\.(png|jpe?g|gif|webp|bmp|svg)(\?|#|$)/i.test(href));
        if (!a || linkToImg) {
          ev.preventDefault();
          Reader.openImage(imgHref);
          return;
        }
        return;
      }
      if (a || (t && t.closest && t.closest('button, input, textarea, select, mark.hl-mark, .nga-table-scroll'))) return;
      if (doc.getSelection && doc.getSelection().toString()) return;
      const w = doc.documentElement.clientWidth || 1;
      const h = doc.documentElement.clientHeight || 1;
      const x = ev.clientX / w;
      const y = ev.clientY / h;
      if (x >= 0.25 && x <= 0.75 && y >= 0.08 && y <= 0.92) Reader.toggleChrome();
    });
  }

  /** 把章节内“黑/白类”的显式内联文字色重映射为主题默认色。
   *
   * NGA 帖子转 EPUB 时，彩色字（[color=red] 等）和默认字是分离的：
   * 默认字由 body 继承，彩色字是独立的内联颜色。这里只处理计算后
   * 接近纯黑/纯白（无色相）的内联颜色；红色/蓝色等彩色和中间灰
   * （如 NGA 的 gray/silver）一律保留，保证安科彩色精髓不被破坏。
   */
  function remapNgaDefaultColors(doc) {
    if (!doc || !doc.body) return;
    const target = 'var(--reader-fg)';

    function classify(r, g, b) {
      const mx = Math.max(r, g, b);
      const mn = Math.min(r, g, b);
      if (mx - mn > 32) return null; // 有彩色相，保留
      const lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
      if (lum < 0.25) return 'black';
      if (lum > 0.75) return 'white';
      return null; // 中间灰，保留
    }

    const styled = doc.querySelectorAll('[style]');
    for (const el of styled) {
      if (!el.style || !el.style.color) continue;
      if (el.classList && (el.classList.contains('hl-mark') || el.classList.contains('syntax'))) continue;
      let cs;
      try {
        cs = doc.defaultView.getComputedStyle(el);
      } catch (e) {
        continue;
      }
      const m = cs.color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
      if (!m) continue;
      const kind = classify(parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10));
      if (kind) el.style.color = target;
    }
  }

  function restoreOffset(offset, doc) {
    const ctx = App.state.textCtx;
    const el = scroller();
    if (!ctx) { el.scrollTop = 0; return; }
    const point = TextPos.plainToPoint(ctx, offset);
    if (!point) { el.scrollTop = 0; return; }
    const range = doc.createRange();
    range.setStart(point.node, point.charIndex);
    range.collapse(true);
    const rect = range.getBoundingClientRect();
    el.scrollTop = Math.max(0, rect.top);
  }

  let scrollDebounceTimer = null;
  let sliderTimer = null;
  let loadToken = 0;
  let loadResolve = null;
  let lightboxScale = 1;

  window.Reader = {
    async loadChapter(index, textOffset) {
      const book = App.state.book;
      if (!book || index < 0 || index >= book.chapters.length) return;

      const prevBook = App.state.bookId;
      const prevIndex = App.state.chapterIndex;
      if (prevBook && prevIndex >= 0 && prevIndex !== index) {
        Bridge.call('save_progress', prevBook, prevIndex, this.currentOffset());
      }
      App.state.chapterIndex = index;
      App.state.textCtx = null;
      const token = ++loadToken;

      const ch = book.chapters[index];
      const frame = frameEl();
      const href = ch.href.split('#')[0].split('/').map(encodeURIComponent).join('/');
      const url = `/book/${App.state.bookId}/${href}`;

      // ????????????/?????????????
      // ???? about:blank???????????????????
      let samePath = false;
      try {
        samePath = frame.contentWindow.location.pathname ===
          new URL(url, location.href).pathname;
      } catch (e) { /* ignore cross-origin / initial state */ }
      const target = samePath ? url + '?_=' + Date.now() : url;

      await new Promise((resolve) => {
        const timer = setTimeout(() => {
          if (loadResolve === resolve) loadResolve = null;
          resolve();
        }, 15000);
        // ?????????????????????? Promise ??
        if (loadResolve) loadResolve();
        loadResolve = resolve;
        frame.onload = () => {
          clearTimeout(timer);
          if (loadResolve === resolve) loadResolve = null;
          if (token !== loadToken) { resolve(); return; }
          const doc = frame.contentDocument;
          if (!doc) { resolve(); return; }
          // ????????????????????/????????
          window.__readerHugeChapter__ = false;
          App.state.textCtx = TextPos.build(doc);
          window.__readerHugeChapter__ = App.state.textCtx.text.length > MAX_PAGED_TEXT;
          applyOverrides(doc);
          const paged = Paged.isActive();
          if (!paged && window.__readerHugeChapter__) {
            try { Toast.show('?????????????????'); } catch (e) { /* ignore */ }
          }
          try {
            if (window.CodeHighlight) CodeHighlight.highlightBlocks(doc);
          } catch (e) { /* ignore */ }
          try {
            if (window.Annotations) Annotations.injectForChapter(doc);
          } catch (e) { /* ignore */ }
          // ????/???? span ???????? text_offset ????? DOM ???
          App.state.textCtx = TextPos.build(doc);
          try {
            if (window.Annotations) Annotations.bindSelection(doc);
          } catch (e) { /* ignore */ }
          bindLinkHandler(doc);
          bindDocInteractions(doc);
          // ??? iframe ????????????????????????
          doc.addEventListener('keydown', Reader.onKeyDown);
          if (App.state.book && App.state.book.nga) remapNgaDefaultColors(doc);
          if (paged) Paged.prepare(doc);
          const onImgChange = () => { if (Paged.isActive()) Paged.onResize(); else syncHeight(); };
          doc.querySelectorAll('img').forEach((img) => {
            img.addEventListener('load', onImgChange);
          });
          // ?????????????????????????????????
          doc.addEventListener('error', (e) => {
            const t = e.target;
            if (t && t.tagName === 'IMG') {
              t.style.display = 'none';
              onImgChange();
            }
          }, true);
          const fonts = doc.fonts ? doc.fonts.ready : Promise.resolve();
          fonts.then(() => {
            if (token !== loadToken) return;
            this.applyLayout();
            if (paged) {
              Paged.normalizeTallTables(doc);
              Paged.setupInteraction(doc);
              if (textOffset && textOffset > 0) Paged.gotoOffset(textOffset);
              else Paged.gotoPage(Paged.firstContentPage());
            } else {
              syncHeight();
              if (textOffset && textOffset > 0) restoreOffset(textOffset, doc);
              else scroller().scrollTop = 0;
            }
            Toc.highlight(index);
            document.getElementById('reader-chapter-label').textContent = ch.title || '';
            this.updateProgressUI();
            resolve();
          });
        };
        frame.onerror = () => {
          clearTimeout(timer);
          if (loadResolve === resolve) loadResolve = null;
          if (token !== loadToken) return;
          resolve();
        };
        frame.src = target;
      });
    },

    applyMode() {
      if (App.state.chapterIndex < 0) return;
      const offset = this.currentOffset();
      this.loadChapter(App.state.chapterIndex, offset);
    },

    updateOverrides() {
      const doc = frameEl().contentDocument;
      if (doc) {
        applyOverrides(doc);
        if (App.state.book && App.state.book.nga) remapNgaDefaultColors(doc);
      }
      this.applyLayout();
      requestAnimationFrame(() => {
        if (Paged.isActive()) {
          Paged.onResize();
        } else {
          syncHeight();
          this.updateProgressUI();
        }
      });
    },

    currentOffset() {
      if (Paged.isActive()) return Paged.currentOffset();
      const doc = frameEl().contentDocument;
      const ctx = App.state.textCtx;
      if (!doc || !ctx) return 0;
      const x = Math.max(2, scroller().clientWidth / 2);
      const y = scroller().scrollTop + 8;
      const off = TextPos.currentOffsetFromPoint(ctx, x, y);
      return off === null ? 0 : off;
    },

    seekToOffset(offset) {
      const doc = frameEl().contentDocument;
      const ctx = App.state.textCtx;
      if (!doc || !ctx) return;
      if (Paged.isActive()) {
        Paged.gotoOffset(offset);
      } else {
        const point = TextPos.plainToPoint(ctx, offset);
        if (point) {
          const range = doc.createRange();
          range.setStart(point.node, point.charIndex);
          range.collapse(true);
          const rect = range.getBoundingClientRect();
          scroller().scrollTop = Math.max(0, rect.top);
        }
      }
      this.saveProgress();
      this.updateProgressUI();
      window.dispatchEvent(new Event('reader-updated'));
    },

    saveProgress() {
      if (!App.state.bookId || App.state.view !== 'reader' || App.state.chapterIndex < 0) return;
      Bridge.call('save_progress', App.state.bookId, App.state.chapterIndex, this.currentOffset());
    },

    onPageTurned() {
      this.saveProgress();
      this.updateProgressUI();
      if (window.Stats) Stats.addPage();
      window.dispatchEvent(new Event('reader-updated'));
    },

    updateProgressUI() {
      const book = App.state.book;
      if (!book) return;
      const total = book.chapters.length;
      const ctx = App.state.textCtx;
      const clen = ctx && ctx.text.length ? ctx.text.length : 0;
      const pct = total > 0
        ? (App.state.chapterIndex + (clen ? this.currentOffset() / clen : 0)) / total
        : 0;
      const slider = document.getElementById('progress-slider');
      if (slider) slider.value = Math.round(Math.max(0, Math.min(1, pct)) * 1000);
      const text = document.getElementById('progress-text');
      if (text) {
        text.textContent = Math.round(pct * 100) + '%';
        text.title = 'Chapter ' + (App.state.chapterIndex + 1) + ' / ' + total;
      }
    },

    jumpToFraction(fraction) {
      const book = App.state.book;
      if (!book || !book.chapters.length) return;
      fraction = Math.max(0, Math.min(1, fraction));
      const total = book.chapters.length;
      const ci = Math.min(total - 1, Math.floor(fraction * total));
      if (ci === App.state.chapterIndex) {
        const ctx = App.state.textCtx;
        const clen = ctx && ctx.text.length ? ctx.text.length : 0;
        let offset = 0;
        if (clen) {
          const chStart = ci / total;
          const chEnd = (ci + 1) / total;
          const span = Math.max(0.0001, chEnd - chStart);
          offset = Math.floor(((fraction - chStart) / span) * clen);
        }
        this.seekToOffset(offset);
        return;
      }
      this.loadChapter(ci, 0);
    },

    onKeyDown(ev) {
      if (App.state.view !== 'reader') return;
      if (ev.key === 'Escape') {
        if (Reader.closeImage()) { ev.preventDefault(); return; }
        if (Reader.closeShortcuts()) { ev.preventDefault(); return; }
        if (window.FullSearch && FullSearch.isOpen()) {
          FullSearch.close();
          ev.preventDefault();
          return;
        }
        const vm = document.getElementById('view-menu');
        if (vm && !vm.classList.contains('hidden')) { ViewMenu.close(); ev.preventDefault(); return; }
        const sp = document.getElementById('settings-view');
        if (sp && !sp.classList.contains('hidden')) {
          if (window.SettingsPage) SettingsPage.close();
          ev.preventDefault();
          return;
        }
        if (Sidebar.isOpen()) { Sidebar.close(); ev.preventDefault(); return; }
        if (App.state.immersive) {
          ev.preventDefault();
          App.exitImmersive();
          return;
        }
        return;
      }
      if ((ev.ctrlKey || ev.metaKey) && (ev.key === 'f' || ev.key === 'F')) {
        ev.preventDefault();
        if (window.FullSearch) FullSearch.open();
        return;
      }
      const sp = document.getElementById('settings-view');
      if (sp && !sp.classList.contains('hidden')) return;
      const vm = document.getElementById('view-menu');
      if (vm && !vm.classList.contains('hidden')) return;
      if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
      const tag = ev.target.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || ev.target.isContentEditable) return;
      const s = (App.state.settings && App.state.settings.shortcuts) || {};
      const actions = [
        [s.next_page || 'ArrowRight', () => {
          ev.preventDefault();
          if (Paged.isActive()) Paged.nextPage(true);
          else Reader.nextChapter();
        }],
        [s.prev_page || 'ArrowLeft', () => {
          ev.preventDefault();
          if (Paged.isActive()) Paged.prevPage(true);
          else Reader.prevChapter();
        }],
        [s.next_chapter || 'ArrowDown', () => { ev.preventDefault(); Reader.nextChapter(); }],
        [s.prev_chapter || 'ArrowUp', () => { ev.preventDefault(); Reader.prevChapter(); }],
        [s.toggle_theme || 't', () => {
          const btn = document.getElementById('theme-btn2') || document.getElementById('theme-btn');
          if (btn) btn.click();
        }],
        [s.toggle_sidebar || 's', () => Sidebar.toggle()],
        [s.toggle_bars || 'b', () => App.toggleBarsPinned()],
        [s.bookmark || 'm', () => Reader.toggleBookmarkAtCurrent()],
        [s.help || '?', () => Reader.showShortcuts()],
        [s.toggle_fullscreen || 'F11', () => {
          ev.preventDefault();
          App.toggleImmersive();
        }],
      ];
      for (const [key, fn] of actions) {
        if (key && ev.key === key) { fn(); return; }
      }
    },

    prevChapter() {
      const i = App.state.chapterIndex - 1;
      if (i >= 0) this.loadChapter(i, 0);
    },

    nextChapter() {
      const book = App.state.book;
      if (!book) return;
      const i = App.state.chapterIndex + 1;
      if (i < book.chapters.length) this.loadChapter(i, 0);
    },

    pageOrChapter(delta) {
      if (Paged.isActive()) {
        if (delta > 0) Paged.nextPage(true);
        else Paged.prevPage(true);
      } else if (delta > 0) {
        this.nextChapter();
      } else {
        this.prevChapter();
      }
    },

    fontSize(delta) {
      const s = App.state.settings;
      s.font_size = Math.max(12, Math.min(36, s.font_size + delta));
      Theme.applyReaderPrefs(s.font_size, s.line_height);
      this.updateOverrides();
      Bridge.call('save_settings', { font_size: s.font_size });
      if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
    },

    lineHeight(delta) {
      const s = App.state.settings;
      s.line_height = Math.max(1.2, Math.min(3, +(s.line_height + delta).toFixed(1)));
      Theme.applyReaderPrefs(s.font_size, s.line_height);
      this.updateOverrides();
      Bridge.call('save_settings', { line_height: s.line_height });
      if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
    },

    onPaginationChange() {
      this.applyLayout();
      this.applyMode();
    },

    applyLayout() {
      const rv = document.getElementById('reader-view');
      rv.classList.toggle('paged', Paged.isActive());
      const root = document.getElementById('reader-root');
      if (root) root.classList.toggle('dual', !!(Paged.isDual && Paged.isDual()));
      const wrap = document.querySelector('.chapter-wrap');
      if (wrap && !Paged.isActive()) {
        wrap.style.maxWidth = (46 * (App.state.settings.page_width || 1)) + 'em';
      }
    },

    setPageWidth(v) {
      const s = App.state.settings;
      s.page_width = Math.max(0.5, Math.min(1.5, v || 1));
      const wrap = document.querySelector('.chapter-wrap');
      if (wrap && !Paged.isActive()) {
        wrap.style.maxWidth = (46 * s.page_width) + 'em';
      }
      if (Paged.isActive()) {
        s.margin_px = Math.max(8, Math.min(160, Math.round(40 / s.page_width)));
        Paged.onResize();
      }
      this.updateProgressUI();
    },

    toggleBookmarkAtCurrent() {
      if (!App.state.bookId || App.state.chapterIndex < 0) return;
      const offset = this.currentOffset();
      if (window.Annotations && Annotations.toggleBookmark) {
        Annotations.toggleBookmark(App.state.chapterIndex, offset);
      } else {
        Toast.show('Bookmarks are available after the annotations module loads');
      }
    },

    /** Readest 风格：点击页面中央切换顶/底栏显示状态。 */
    toggleChrome() {
      const now = Date.now();
      if (now - (this._lastChromeToggle || 0) < 350) return;
      this._lastChromeToggle = now;
      const pinned = document.getElementById('reader-view').classList.contains('bars-pinned');
      if (pinned) {
        // 固定模式下点击正文不解除固定（阅读时误触点击不会“破防”），
        // 取消固定请用顶栏固定按钮或快捷键 b。
        return;
      }
      const topBar = document.getElementById('top-bar');
      App.setBarsVisible(!topBar.classList.contains('bar-visible'));
    },

    /** 快捷键帮助弹窗（按 ? 或顶栏帮助按钮打开，Esc / 点击空白关闭）。 */
    showShortcuts() {
      let ov = document.getElementById('shortcut-help');
      if (!ov) {
        ov = document.createElement('div');
        ov.className = 'modal-overlay hidden';
        ov.id = 'shortcut-help';
        const box = document.createElement('div');
        box.className = 'help-modal';
        const title = document.createElement('div');
        title.className = 'help-modal-title';
        title.textContent = '快捷键';
        const close = document.createElement('button');
        close.className = 'vm-btn';
        close.textContent = '关闭 (Esc)';
        close.addEventListener('click', () => Reader.closeShortcuts());
        title.appendChild(close);
        const list = document.createElement('div');
        list.className = 'help-modal-list';
        list.id = 'shortcut-help-list';
        const hint = document.createElement('p');
        hint.className = 'help-hint';
        hint.textContent = 'Ctrl+F 打开全文搜索；滚动阅读模式下左右方向键直接切换章节；点击页面中央可切换顶栏/底栏；Esc 关闭弹窗或侧栏。';
        box.append(title, list, hint);
        ov.appendChild(box);
        ov.addEventListener('click', (e) => {
          if (e.target === ov) Reader.closeShortcuts();
        });
        overlayRoot().appendChild(ov);
      }
      const sc = Object.assign({}, HELP_SHORTCUTS, (App.state.settings && App.state.settings.shortcuts) || {});
      const list = document.getElementById('shortcut-help-list');
      list.innerHTML = '';
      for (const [action, label] of HELP_ACTIONS) {
        const row = document.createElement('div');
        row.className = 'help-row';
        const l = document.createElement('span');
        l.textContent = label;
        const k = document.createElement('kbd');
        k.className = 'help-key';
        k.textContent = Util.displayKey(sc[action]);
        row.append(l, k);
        list.appendChild(row);
      }
      ov.classList.remove('hidden');
    },

    closeShortcuts() {
      const ov = document.getElementById('shortcut-help');
      if (!ov || ov.classList.contains('hidden')) return false;
      ov.classList.add('hidden');
      return true;
    },

    /** 图片点击放大：滚轮缩放（0.5x~5x），双击在适配/1:1 间切换。 */
    openImage(src) {
      if (!src) return;
      let ov = document.getElementById('image-lightbox');
      if (!ov) {
        ov = document.createElement('div');
        ov.className = 'image-lightbox hidden';
        ov.id = 'image-lightbox';
        const img = document.createElement('img');
        img.id = 'lightbox-img';
        img.alt = '';
        const close = document.createElement('button');
        close.className = 'lightbox-close';
        close.title = '关闭 (Esc)';
        close.textContent = '✕';
        const hint = document.createElement('span');
        hint.className = 'lightbox-hint';
        hint.textContent = '滚轮缩放 · 双击 1:1 · 点击关闭';
        ov.append(img, close, hint);
        ov.addEventListener('click', (e) => {
          if (e.target === ov || e.target === close || e.target === hint) Reader.closeImage();
        });
        ov.addEventListener('wheel', (e) => {
          e.preventDefault();
          lightboxScale = Math.max(0.5, Math.min(5, lightboxScale + (e.deltaY < 0 ? 0.15 : -0.15)));
          img.style.transform = 'scale(' + lightboxScale + ')';
        }, { passive: false });
        img.addEventListener('dblclick', () => {
          lightboxScale = lightboxScale === 1 ? 2 : 1;
          img.style.transform = 'scale(' + lightboxScale + ')';
        });
        overlayRoot().appendChild(ov);
      }
      lightboxScale = 1;
      const img = document.getElementById('lightbox-img');
      img.src = src;
      img.style.transform = 'scale(1)';
      ov.classList.remove('hidden');
    },

    closeImage() {
      const ov = document.getElementById('image-lightbox');
      if (!ov || ov.classList.contains('hidden')) return false;
      ov.classList.add('hidden');
      return true;
    },

    async goToSearchHit(chapterIndex, offset) {
      Sidebar.close();
      await this.loadChapter(chapterIndex, offset);
    },
  };

  scroller()?.addEventListener('scroll', () => {
    clearTimeout(scrollDebounceTimer);
    scrollDebounceTimer = setTimeout(() => {
      if (!Paged.isActive()) {
        Reader.saveProgress();
        Reader.updateProgressUI();
      }
    }, 500);
  });

  document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('prev-chapter').addEventListener('click', () => {
      Reader.prevChapter();
      App.hideBarsForAction();
    });
    document.getElementById('next-chapter').addEventListener('click', () => {
      Reader.nextChapter();
      App.hideBarsForAction();
    });
    document.getElementById('page-prev').addEventListener('click', () => {
      Reader.pageOrChapter(-1);
      App.hideBarsForAction();
    });
    document.getElementById('page-next').addEventListener('click', () => {
      Reader.pageOrChapter(1);
      App.hideBarsForAction();
    });
    const helpBtn = document.getElementById('help-btn');
    if (helpBtn) helpBtn.addEventListener('click', () => Reader.showShortcuts());
    document.querySelectorAll('.page-nav .page-nav-btn').forEach((b) => {
      b.addEventListener('click', () => {
        const a = b.dataset.action;
        if (a === 'prev-section') Reader.prevChapter();
        else if (a === 'next-section') Reader.nextChapter();
        else if (a === 'prev') Reader.pageOrChapter(-1);
        else if (a === 'next') Reader.pageOrChapter(1);
        App.hideBarsForAction();
      });
    });

    // 点击 iframe 以外的页面中央区域（边距/空档）同样切换顶/底栏。
    document.getElementById('reader-root').addEventListener('click', (ev) => {
      if (App.state.view !== 'reader') return;
      const t = ev.target;
      if (t && t.closest && t.closest(
        '#chapter-frame, .page-nav, .chapter-nav-row, .view-menu, #top-bar, #status-bar, button, a, input, textarea, select'
      )) return;
      if (window.getSelection && window.getSelection().toString()) return;
      const root = document.getElementById('reader-root');
      const rect = root.getBoundingClientRect();
      const x = (ev.clientX - rect.left) / (rect.width || 1);
      const y = (ev.clientY - rect.top) / (rect.height || 1);
      if (x >= 0.25 && x <= 0.75 && y >= 0.08 && y <= 0.92) Reader.toggleChrome();
    });

    const slider = document.getElementById('progress-slider');
    if (slider) {
      slider.addEventListener('input', (e) => {
        clearTimeout(sliderTimer);
        sliderTimer = setTimeout(() => {
          Reader.jumpToFraction(parseInt(e.target.value, 10) / 1000);
        }, 100);
      });
    }

    if (window.ResizeObserver) {
      const ro = new ResizeObserver(() => {
        if (App.state.view !== 'reader') return;
        // 自动双页会随窗口方向/宽度变化（横屏宽窗→双页），先同步 dual class 再重排
        Reader.applyLayout();
        if (Paged.isActive()) Paged.onResize();
      });
      ro.observe(document.getElementById('reader-root'));
    }
  });
})();
