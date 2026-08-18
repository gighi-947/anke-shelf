/**
 * Reader core: iframe chapter rendering, paginated/scroll modes, text_offset
 * progress tracking and the Readest-style footer slider.
 */
(function () {
  'use strict';

  const { BASE_OVERRIDE, NGA_OVERRIDE, GULULU_OVERRIDE, PAGINATION_OVERRIDE } = ReaderUtils;

  const scroller = () => document.getElementById('chapter-scroll');
  const frameEl = () => document.getElementById('chapter-frame');
  // 超过该纯文本长度的章节不启用分页（CSS 多栏渲染超大章节会占用数 GB 内存），
  // 自动退回滚动阅读；阅读进度与标注仍按章节级工作。
  const MAX_PAGED_TEXT = 800000;

  function readVars() {
    const cs = getComputedStyle(document.getElementById('reader-root'));
    return {
      family: ReaderUtils.resolveFamily(),
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
    if (App.state.book && App.state.book.gululu) css += GULULU_OVERRIDE;
    if (Paged.isActive()) css += PAGINATION_OVERRIDE;
    css = ReaderUtils.fontFaceCss() + css;
    el.textContent = css
      .replaceAll('var(--reader-font-family, "Segoe UI", "Microsoft YaHei", serif)', ReaderUtils.resolveFamily())
      .replaceAll('var(--reader-font-size, 18px)', v.fontSize)
      .replaceAll('var(--reader-line-height, 1.8)', v.lineHeight)
      .replaceAll('var(--reader-fg, #222)', v.fg)
      .replaceAll('var(--reader-accent, #77bbee)', v.accent);
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
        const url = new URL(href, a.baseURI);
        const abs = url.pathname;
        const prefix = '/book/' + App.state.bookId + '/';
        if (abs.startsWith(prefix)) {
          const target = decodeURIComponent(abs.slice(prefix.length));
          const idx = App.state.book.chapters.findIndex((c) => c.href === target);
          if (idx !== -1) {
            Reader.loadChapter(idx, 0).then(() => {
              if (!url.hash) return;
              const targetDoc = frameEl().contentDocument;
              const anchorId = decodeURIComponent(url.hash.slice(1));
              const anchor = targetDoc && targetDoc.getElementById(anchorId);
              if (!anchor || !App.state.textCtx) return;
              const walker = targetDoc.createTreeWalker(anchor, NodeFilter.SHOW_TEXT);
              let anchorText = walker.nextNode();
              while (anchorText && !anchorText.data.trim()) anchorText = walker.nextNode();
              if (!anchorText) return;
              const range = targetDoc.createRange();
              const charIndex = anchorText.data.search(/\S/);
              range.setStart(anchorText, Math.max(0, charIndex));
              range.collapse(true);
              const offsets = TextPos.rangeToOffsets(App.state.textCtx, range);
              if (!offsets) return;
              const offset = offsets[0];
              if (window.Paged && Paged.isActive()) Paged.gotoOffset(offset);
              else restoreOffset(offset, targetDoc);
              const session = Reader.ensureSession();
              session.setPosition(offset);
              Api.saveProgress(App.state.bookId, App.state.chapterIndex, offset);
              session.markSaved();
              Reader.updateProgressUI();
            });
          }
        } else if (url.protocol === 'https:') {
          window.open(url.href, '_blank', 'noopener,noreferrer');
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

  /** 把来源章节内“黑/白类”的显式内联文字色重映射为主题默认色。
   *
   * NGA / 骨碌碌转 EPUB 时，彩色字和默认字是分离的：
   * 默认字由 body 继承，彩色字是独立的内联颜色。这里只处理计算后
   * 接近纯黑/纯白（无色相）的内联颜色；红色/蓝色等彩色和中间灰
   * （如 NGA 的 gray/silver）一律保留，保证安科彩色精髓不被破坏。
   */
  function remapDefaultColors(doc) {
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

  /** 视口采样/坐标可能落在空白文本节点（无渲染盒子，rect=0 会把位置拉回开头）。
   * 用 textCtx.ranges 顺序向后找第一个非空白文本位置。 */
  function skipBlankPoint(ctx, point) {
    let node = point.node;
    let ci = point.charIndex;
    const isBlank = (n, c) => n.nodeType === Node.TEXT_NODE && n.data.slice(c).trim() === '';
    let guard = 0;
    while (isBlank(node, ci) && guard++ < 60) {
      const idx = ctx.ranges.findIndex((r) => r.node === node);
      if (idx === -1 || idx + 1 >= ctx.ranges.length) break;
      const next = ctx.ranges[idx + 1];
      node = next.node;
      ci = 0;
    }
    return { node, charIndex: ci };
  }

  function restoreOffset(offset, doc) {
    const ctx = App.state.textCtx;
    const el = scroller();
    if (!ctx) { el.scrollTop = 0; return; }
    const point = TextPos.plainToPoint(ctx, offset);
    if (!point) { el.scrollTop = 0; return; }
    const anchor = skipBlankPoint(ctx, point);
    const range = doc.createRange();
    range.setStart(anchor.node, anchor.charIndex);
    range.collapse(true);
    const rect = range.getBoundingClientRect();
    // 同 seekToOffset：rect.top 为 iframe 内容坐标，需加 frameTop 换算宿主位置。
    // 定位到视口 8px（滚动采样点 y=scrollTop+8），保证恢复后采样与定位一致、
    // 不产生逐次累积漂移。
    const frame = frameEl();
    const frameTop = frame ? frame.getBoundingClientRect().top : 0;
    el.scrollTop = Math.max(0, rect.top + frameTop + el.scrollTop - 8);
  }

  function waitForLayoutReady(doc, paged) {
    const deadline = Date.now() + 5000;
    return new Promise((resolve) => {
      const check = () => {
        const fontsReady = !doc.fonts || doc.fonts.status !== 'loading';
        const imagesReady = !paged || Array.from(doc.images || []).every((img) => img.complete);
        if ((fontsReady && imagesReady) || Date.now() >= deadline) {
          resolve();
          return;
        }
        setTimeout(check, 50);
      };
      check();
    });
  }

  let scrollDebounceTimer = null;
  let sliderTimer = null;
  let imageLayoutFrame = 0;
  let loadToken = 0;
  let loadResolve = null;
  let loadingMaskTimer = 0;

  window.Reader = {
    ensureSession() {
      if (!this.session || this.session.bookId !== App.state.bookId) {
        this.session = new ReaderSession(
          App.state.bookId,
          Math.max(0, App.state.chapterIndex || 0),
          0,
        );
      }
      return this.session;
    },

    setLoading(loading) {
      clearTimeout(loadingMaskTimer);
      loadingMaskTimer = 0;
      const root = document.getElementById('reader-root');
      const mask = document.getElementById('reader-loading-mask');
      if (root) root.setAttribute('aria-busy', String(!!loading));
      if (mask) mask.classList.toggle('hidden', !loading);
      if (loading) {
        loadingMaskTimer = setTimeout(() => {
          loadingMaskTimer = 0;
          Reader.setLoading(false);
        }, 5000);
      }
    },

    async loadChapter(index, textOffset) {
      this.ensureSession();
      const book = App.state.book;
      if (!book || index < 0 || index >= book.chapters.length) return;
      this.setLoading(true);

      const prevBook = App.state.bookId;
      const prevIndex = App.state.chapterIndex;
      if (prevBook && prevIndex >= 0 && prevIndex !== index) {
        Api.saveProgress( prevBook, prevIndex, this.currentOffset());
      }
      App.state.chapterIndex = index;
      this.session.enterChapter(index, textOffset || 0);
      App.state.textCtx = null;
      if (imageLayoutFrame) cancelAnimationFrame(imageLayoutFrame);
      imageLayoutFrame = 0;
      const token = ++loadToken;

      const ch = book.chapters[index];
      const frame = frameEl();
      const href = ch.href.split('#')[0].split('/').map(encodeURIComponent).join('/');
      const url = `/book/${App.state.bookId}/${href}`;

      // 判断 iframe 是否停留在同一章节路径：同路径时加时间戳强制重新加载，
      // 避免热更新后仍命中旧缓存。
      // 首帧 about:blank 没有路径（跨源读取会抛错），按非同一路径处理。
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
        // 先解除上一次尚未完成的加载 Promise 等待，避免换章时旧等待永久挂起。
        if (loadResolve) loadResolve();
        loadResolve = resolve;
        frame.onload = () => {
          clearTimeout(timer);
          if (loadResolve === resolve) loadResolve = null;
          if (token !== loadToken) { resolve(); return; }
          const doc = frame.contentDocument;
          if (!doc) { resolve(); return; }
          // 先构建文本坐标并判定章节大小，再决定分页/滚动与覆盖样式。
          window.__readerHugeChapter__ = false;
          App.state.textCtx = TextPos.build(doc);
          window.__readerHugeChapter__ = App.state.textCtx.text.length > MAX_PAGED_TEXT;
          applyOverrides(doc);
          const paged = Paged.isActive();
          if (!paged && window.__readerHugeChapter__) {
            try { Toast.show('本章内容较大，已自动切换为滚动阅读'); } catch (e) { /* ignore */ }
          }
          let textDomChanged = false;
          try {
            if (window.CodeHighlight) {
              textDomChanged = CodeHighlight.highlightBlocks(doc) > 0 || textDomChanged;
            }
          } catch (e) { /* ignore */ }
          try {
            if (window.Annotations) {
              textDomChanged = Annotations.injectForChapter(doc) > 0 || textDomChanged;
            }
          } catch (e) { /* ignore */ }
          // 注入高亮/代码高亮 span 后重建坐标，保证 text_offset 与注入后的 DOM 对齐。
          if (textDomChanged) App.state.textCtx = TextPos.build(doc);
          try {
            if (window.Annotations) Annotations.bindSelection(doc);
          } catch (e) { /* ignore */ }
          bindLinkHandler(doc);
          bindDocInteractions(doc);
          // 焦点在 iframe 内时也能响应全局快捷键（热键在阅读正文中生效）。
          doc.addEventListener('keydown', Reader.onKeyDown);
          if (App.state.book && (App.state.book.nga || App.state.book.gululu)) {
            remapDefaultColors(doc);
          }
          if (paged) Paged.prepare(doc);
          if (paged) {
            doc.querySelectorAll('img[loading="lazy"]').forEach((img) => {
              img.loading = 'eager';
            });
          }
          const onImgChange = () => {
            if (imageLayoutFrame) return;
            imageLayoutFrame = requestAnimationFrame(() => {
              imageLayoutFrame = 0;
              if (token !== loadToken) return;
              if (Paged.isActive()) Paged.onResize();
              else syncHeight();
            });
          };
          doc.querySelectorAll('img').forEach((img) => {
            img.addEventListener('load', () => {
              img.classList.add('gululu-img-loaded');
              onImgChange();
            });
          });
          // 文档级委托：任何图片（含后加载的）加载失败显示占位卡，避免裂图/大段空白。
          // 占位文案带 data-textpos-exclude，不进入 text_offset 坐标系。
          doc.addEventListener('error', (e) => {
            const t = e.target;
            if (t && t.tagName === 'IMG') {
              const placeholder = document.createElement('span');
              placeholder.className = 'img-error-placeholder';
              placeholder.setAttribute('data-textpos-exclude', '');
              placeholder.textContent = '图片加载失败';
              t.replaceWith(placeholder);
              onImgChange();
            }
          }, true);
          waitForLayoutReady(doc, paged).then(() => {
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
            if (window.GululuComments) GululuComments.onChapterLoaded(doc);
            if (window.GululuAssistantReader) GululuAssistantReader.onChapterLoaded(doc);
            if (window.GululuImmersive) GululuImmersive.onChapterLoaded(doc);
            if (window.GululuSecrets) GululuSecrets.onChapterLoaded(doc);
            if (window.GululuOverview) GululuOverview.onChapterLoaded(doc);
            if (window.App && App.syncGululuBookmark) App.syncGululuBookmark();
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
      if (token === loadToken) this.setLoading(false);
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
        if (App.state.book && (App.state.book.nga || App.state.book.gululu)) {
          remapDefaultColors(doc);
        }
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
      const x = Math.max(2, frameEl().clientWidth / 2);
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
          const anchor = skipBlankPoint(ctx, point);
          const range = doc.createRange();
          range.setStart(anchor.node, anchor.charIndex);
          range.collapse(true);
          const rect = range.getBoundingClientRect();
          const scrollerEl = scroller();
          const frame = frameEl();
          const frameTop = frame ? frame.getBoundingClientRect().top : 0;
          if (window.__gululuDiag) console.log('[gululu] seek offset=' + offset, 'rectTop=' + rect.top, 'frameTop=' + frameTop, 'stBefore=' + scrollerEl.scrollTop, 'node=' + (anchor.node.data ? JSON.stringify(anchor.node.data.slice(0, 16)) : anchor.node.nodeName));
          // 定位到视口 8px（滚动采样点），保证恢复后采样与定位一致、无累积漂移
          scrollerEl.scrollTop = Math.max(0, rect.top + frameTop + scrollerEl.scrollTop - 8);
        }
      }
      this.saveProgress(offset);
      this.updateProgressUI();
      window.dispatchEvent(new Event('reader-updated'));
    },

    saveProgress(preciseOffset) {
      if (!App.state.bookId || App.state.view !== 'reader' || App.state.chapterIndex < 0) return;
      const session = this.ensureSession();
      // 定位类操作（搜索跳转 / 评论跳转 / 抽屉保位）传入目标 offset，
      // 避免滚动/重排未稳定时重新采样视口中线造成进度乱跳。
      const off = typeof preciseOffset === 'number' ? preciseOffset : this.currentOffset();
      session.setPosition(off);
      Api.saveProgress( App.state.bookId, session.chapterIndex, off);
      session.markSaved();
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

    fontSize(delta) {
      const s = App.state.settings;
      s.font_size = Math.max(12, Math.min(36, s.font_size + delta));
      Theme.applyReaderPrefs(s.font_size, s.line_height);
      this.updateOverrides();
      Api.saveSettings( { font_size: s.font_size });
      if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
    },

    lineHeight(delta) {
      const s = App.state.settings;
      s.line_height = Math.max(1.2, Math.min(3, +(s.line_height + delta).toFixed(1)));
      Theme.applyReaderPrefs(s.font_size, s.line_height);
      this.updateOverrides();
      Api.saveSettings( { line_height: s.line_height });
      if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
    },

    onPaginationChange() {
      this.applyLayout();
      this.applyMode();
    },

    applyLayout() {
      const rv = document.getElementById('reader-view');
      rv.classList.toggle('paged', Paged.isActive());
      if (this.session) this.session.mode = Paged.isActive() ? 'paged' : 'scroll';
      const root = document.getElementById('reader-root');
      if (root) root.classList.toggle('dual', !!(Paged.isDual && Paged.isDual()));
      const wrap = document.querySelector('.chapter-wrap');
      if (wrap && !Paged.isActive()) {
        wrap.style.maxWidth = (46 * (App.state.settings.page_width || 1)) + 'em';
        // 滚动模式：内容高度变化（徽标/评论/折叠注入）后重算 iframe 高度，
        // 否则底部内容被裁剪，滚动到底出现大段空白。
        syncHeight();
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

    async toggleBookmarkAtCurrent() {
      if (!App.state.bookId || App.state.chapterIndex < 0) return null;
      const offset = this.currentOffset();
      if (window.Annotations && Annotations.toggleBookmark) {
        return Annotations.toggleBookmark(App.state.chapterIndex, offset);
      } else {
        Toast.show('Bookmarks are available after the annotations module loads');
        return null;
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
