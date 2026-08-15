(function () {
  'use strict';

  const frame = document.getElementById('chapter-frame');
  const state = {
    book: null,
    chapter: 0,
    mode: 'scroll',
    page: 0,
    pageTotal: 1,
    fontSize: 18,
    lineHeight: 1.8,
    contentWidth: 820,
    theme: 'light',
    commentsVisible: false,
    danmakuEnabled: false,
  };

  let danmakuTimer = null;
  let danmakuCursor = 0;

  const palettes = {
    light: { bg: '#ffffff', text: '#202522', muted: '#66706a', line: '#cfd5d1', accent: '#087f6b' },
    dark: { bg: '#222724', text: '#edf1ee', muted: '#aeb8b1', line: '#48514b', accent: '#55c7af' },
    contrast: { bg: '#000000', text: '#ffffff', muted: '#d8d8d8', line: '#ffffff', accent: '#67f3d4' },
  };

  function encodedHref(href) {
    return href.split('#')[0].split('/').map(encodeURIComponent).join('/');
  }

  function currentDocument() {
    try {
      return frame.contentDocument;
    } catch (error) {
      return null;
    }
  }

  function ensureFlow(doc) {
    let flow = doc.getElementById('__gululu_debug_flow__');
    if (flow) return flow;
    flow = doc.createElement('main');
    flow.id = '__gululu_debug_flow__';
    while (doc.body.firstChild) flow.appendChild(doc.body.firstChild);
    doc.body.appendChild(flow);
    return flow;
  }

  function updatePageMetrics(resetPage) {
    const doc = currentDocument();
    if (!doc || !doc.documentElement) return;
    if (state.mode !== 'paged') {
      state.page = 0;
      state.pageTotal = 1;
      updatePosition();
      return;
    }
    const viewport = Math.max(1, doc.documentElement.clientWidth);
    const scrollWidth = Math.max(viewport, doc.documentElement.scrollWidth, doc.body.scrollWidth);
    state.pageTotal = Math.max(1, Math.ceil(scrollWidth / viewport));
    if (resetPage) state.page = 0;
    state.page = Math.min(state.page, state.pageTotal - 1);
    doc.documentElement.scrollLeft = state.page * viewport;
    doc.body.scrollLeft = state.page * viewport;
    updatePosition();
  }

  function applyReaderLayout(resetPage) {
    const doc = currentDocument();
    if (!doc || !doc.head || !doc.body) return;
    ensureFlow(doc);
    let style = doc.getElementById('__gululu_debug_style__');
    if (!style) {
      style = doc.createElement('style');
      style.id = '__gululu_debug_style__';
      doc.head.appendChild(style);
    }
    const palette = palettes[state.theme];
    const common = `
      :root { color-scheme: ${state.theme === 'light' ? 'light' : 'dark'}; }
      html, body { background:${palette.bg} !important; color:${palette.text} !important; }
      body { font-family:"Segoe UI","Microsoft YaHei",sans-serif !important; font-size:${state.fontSize}px !important; line-height:${state.lineHeight} !important; }
      a { color:${palette.accent} !important; }
      img, video, svg { max-width:100% !important; height:auto !important; }
      figure { max-width:100%; margin-left:auto !important; margin-right:auto !important; }
      details { border-color:${palette.line} !important; }
      summary { color:${palette.accent}; }
      body.__debug_comments_hidden__ .gululu-comments { display:none !important; }
      .gululu-floor { box-sizing:border-box; margin:14px 0 !important; border:1px solid ${palette.line}; border-left:4px solid color-mix(in srgb, ${palette.accent} 52%, ${palette.text}); border-radius:2px; padding:12px 14px; background:color-mix(in srgb, ${palette.bg} 97%, ${palette.accent}); box-decoration-break:clone; }
      .floor-head { display:flex; align-items:baseline; gap:.55em; margin:0 0 8px !important; padding:0 0 6px !important; border-bottom-style:dotted !important; font-size:.82em; }
      .floor-number { color:color-mix(in srgb, ${palette.accent} 52%, ${palette.text}) !important; font-weight:700; }
      .floor-title { min-width:0; flex:1; }
      .floor-head, .book-meta { color:${palette.muted} !important; border-color:${palette.line} !important; }
      .gululu-debug-comment-button { flex:0 0 auto; border:1px solid ${palette.line}; border-radius:4px; padding:4px 8px; background:transparent; color:${palette.accent}; cursor:pointer; font:inherit; font-size:12px; }
      .gululu-debug-comment-button:hover { background:color-mix(in srgb, ${palette.accent} 10%, transparent); }
      .gululu-debug-comment-button:focus-visible { outline:2px solid ${palette.accent}; outline-offset:2px; }
      #__gululu_debug_flow__ { box-sizing:border-box; }
    `;
    const scrollCss = `
      html { overflow:hidden !important; }
      body { height:100vh; margin:0 !important; overflow-y:auto !important; overflow-x:hidden !important; }
      #__gululu_debug_flow__ { width:min(${state.contentWidth}px, 100%); min-height:100%; margin:0 auto; padding:40px clamp(24px, 5vw, 64px) 88px; }
    `;
    const pagedCss = `
      html, body { height:100vh; margin:0 !important; overflow-y:hidden !important; overflow-x:auto !important; scroll-behavior:smooth; }
      #__gululu_debug_flow__ { height:100vh; padding:34px clamp(28px, 6vw, 72px) 42px; column-width:calc(100vw - clamp(56px, 12vw, 144px)); column-gap:clamp(56px, 12vw, 144px); column-fill:auto; }
      #__gululu_debug_flow__ > * { break-inside:auto; }
      #__gululu_debug_flow__ > figure, #__gululu_debug_flow__ > details { break-inside:avoid; }
    `;
    style.textContent = common + (state.mode === 'paged' ? pagedCss : scrollCss);
    doc.body.classList.toggle('__debug_comments_hidden__', !state.commentsVisible);
    installFloorCommentButtons(doc);
    requestAnimationFrame(() => updatePageMetrics(resetPage));
  }

  function installFloorCommentButtons(doc) {
    doc.querySelectorAll('.gululu-floor[id^="floor-"]').forEach((floor) => {
      const head = floor.querySelector('.floor-head');
      if (!head || head.querySelector('.gululu-debug-comment-button')) return;
      const floorId = Number(floor.id.slice('floor-'.length));
      const count = floor.querySelectorAll('.gululu-comment').length;
      const number = floor.querySelector('.floor-number');
      const button = doc.createElement('button');
      button.type = 'button';
      button.className = 'gululu-debug-comment-button';
      button.dataset.floorId = String(floorId);
      button.setAttribute('data-textpos-exclude', 'true');
      button.textContent = `评论 ${count}`;
      button.setAttribute('aria-label', `${number ? number.textContent.trim() : '当前楼层'}评论`);
      button.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        openComments(floorId, button);
      });
      head.appendChild(button);
    });
  }

  function currentCommentTexts() {
    const doc = currentDocument();
    if (!doc) return [];
    return Array.from(doc.querySelectorAll('.gululu-comment-text'))
      .map((item) => item.textContent.trim().replace(/\s+/g, ' '))
      .filter(Boolean);
  }

  let panelReturnFocus = null;

  function setQuickMenu(open) {
    const menu = document.getElementById('quick-menu');
    const toggle = document.getElementById('quick-menu-toggle');
    menu.hidden = !open;
    toggle.setAttribute('aria-expanded', String(open));
    toggle.textContent = open ? '×' : '＋';
  }

  function closeSettings(restoreFocus) {
    const panel = document.getElementById('settings-panel');
    if (panel.hidden) return;
    panel.hidden = true;
    if (restoreFocus && panelReturnFocus) panelReturnFocus.focus();
    panelReturnFocus = null;
  }

  function closeComments(restoreFocus) {
    const drawer = document.getElementById('comments-drawer');
    if (drawer.hidden) return;
    drawer.hidden = true;
    if (restoreFocus && panelReturnFocus) panelReturnFocus.focus();
    panelReturnFocus = null;
  }

  function openSettings(trigger) {
    closeComments(false);
    setQuickMenu(false);
    panelReturnFocus = trigger || document.getElementById('quick-settings');
    const panel = document.getElementById('settings-panel');
    panel.hidden = false;
    document.getElementById('settings-close').focus();
  }

  function renderCommentDrawer(floorId) {
    const list = document.getElementById('comments-list');
    const status = document.getElementById('comments-status');
    const doc = currentDocument();
    list.replaceChildren();
    if (!doc) {
      status.textContent = '章节尚未载入';
      return;
    }
    const floors = Array.from(doc.querySelectorAll('.gululu-floor[id^="floor-"]'))
      .filter((floor) => floorId == null || Number(floor.id.slice('floor-'.length)) === floorId);
    let total = 0;
    floors.forEach((floor) => {
      const section = document.createElement('section');
      section.className = 'comment-floor';
      const heading = document.createElement('h3');
      const number = floor.querySelector('.floor-number')?.textContent.trim() || '楼层';
      const title = floor.querySelector('.floor-title')?.textContent.trim() || '';
      heading.textContent = title ? `${number} · ${title}` : number;
      section.appendChild(heading);
      const articles = Array.from(floor.querySelectorAll('.gululu-comment'));
      total += articles.length;
      if (!articles.length) {
        const empty = document.createElement('p');
        empty.className = 'comment-empty';
        empty.textContent = '暂无评论';
        section.appendChild(empty);
      } else {
        articles.forEach((source) => {
          const article = document.createElement('article');
          article.className = 'drawer-comment';
          const head = document.createElement('header');
          const author = document.createElement('strong');
          author.textContent = source.querySelector('.gululu-comment-head strong')?.textContent.trim() || '匿名用户';
          const meta = document.createElement('span');
          meta.textContent = source.querySelector('.gululu-comment-head span')?.textContent.trim() || '';
          const body = document.createElement('p');
          body.textContent = source.querySelector('.gululu-comment-text')?.textContent.trim() || '';
          head.append(author, meta);
          article.append(head, body);
          section.appendChild(article);
        });
      }
      list.appendChild(section);
    });
    if (!floors.length) {
      const empty = document.createElement('p');
      empty.className = 'comments-empty';
      empty.textContent = '本章没有可关联的评论楼层';
      list.appendChild(empty);
    }
    status.textContent = floorId == null ? `${floors.length} 个楼层 · ${total} 条` : `${total} 条评论`;
  }

  function openComments(floorId, trigger) {
    closeSettings(false);
    setQuickMenu(false);
    panelReturnFocus = trigger || document.getElementById('quick-comments');
    renderCommentDrawer(floorId == null ? null : Number(floorId));
    const drawer = document.getElementById('comments-drawer');
    drawer.hidden = false;
    document.getElementById('comments-close').focus();
  }

  function stopDanmaku() {
    clearInterval(danmakuTimer);
    danmakuTimer = null;
    danmakuCursor = 0;
    document.getElementById('danmaku-layer').replaceChildren();
  }

  function shootDanmaku() {
    if (!state.danmakuEnabled) return;
    const comments = currentCommentTexts();
    if (!comments.length) return;
    const text = comments[danmakuCursor % comments.length];
    const item = document.createElement('span');
    item.className = 'danmaku-item';
    item.textContent = text.length > 100 ? text.slice(0, 100) + '…' : text;
    item.style.setProperty('--lane', String(danmakuCursor % 6));
    item.style.setProperty('--duration', `${12 + (danmakuCursor % 5)}s`);
    danmakuCursor += 1;
    item.addEventListener('animationend', () => item.remove(), { once: true });
    document.getElementById('danmaku-layer').appendChild(item);
  }

  function syncDanmaku() {
    stopDanmaku();
    const available = currentCommentTexts().length > 0;
    const commentsButton = document.getElementById('comments-toggle');
    const danmakuButton = document.getElementById('danmaku-toggle');
    const quickComments = document.getElementById('quick-comments');
    const quickDanmaku = document.getElementById('quick-menu-danmaku');
    commentsButton.disabled = !available;
    danmakuButton.disabled = !available;
    quickComments.disabled = !available;
    quickDanmaku.disabled = !available;
    if (!available || !state.danmakuEnabled) return;
    shootDanmaku();
    danmakuTimer = setInterval(shootDanmaku, 1100);
  }

  function updatePosition() {
    const total = state.book ? state.book.chapters.length : 0;
    document.getElementById('chapter-position').textContent = total
      ? `${state.chapter + 1} / ${total}` : '-- / --';
    document.getElementById('page-position').textContent = state.mode === 'paged'
      ? `第 ${state.page + 1} / ${state.pageTotal} 页` : '滚动阅读';
    document.getElementById('previous').disabled = state.chapter === 0 && state.page === 0;
    document.getElementById('next').disabled = total === 0 ||
      (state.chapter === total - 1 && state.page >= state.pageTotal - 1);
  }

  function renderToc() {
    const list = document.getElementById('chapter-list');
    list.replaceChildren();
    state.book.chapters.forEach((chapter, index) => {
      const button = document.createElement('button');
      button.className = 'chapter-item';
      button.type = 'button';
      button.dataset.index = String(index);
      const number = document.createElement('span');
      number.className = 'chapter-number';
      number.textContent = String(index + 1).padStart(2, '0');
      const title = document.createElement('span');
      title.className = 'chapter-name';
      title.textContent = chapter.title;
      button.append(number, title);
      button.addEventListener('click', () => loadChapter(index));
      list.appendChild(button);
    });
  }

  function markActiveChapter() {
    document.querySelectorAll('.chapter-item').forEach((button) => {
      const active = Number(button.dataset.index) === state.chapter;
      button.classList.toggle('active', active);
      if (active) button.scrollIntoView({ block: 'nearest' });
    });
  }

  function loadChapter(index) {
    if (!state.book || index < 0 || index >= state.book.chapters.length) return;
    state.chapter = index;
    state.page = 0;
    stopDanmaku();
    closeComments(false);
    const chapter = state.book.chapters[index];
    frame.src = `/book/${encodedHref(chapter.href)}`;
    markActiveChapter();
    updatePosition();
    toggleToc(false);
  }

  function movePage(direction) {
    if (!state.book) return;
    if (state.mode === 'paged') {
      const target = state.page + direction;
      if (target >= 0 && target < state.pageTotal) {
        state.page = target;
        updatePageMetrics(false);
        return;
      }
    }
    loadChapter(state.chapter + direction);
  }

  function setMode(mode) {
    state.mode = mode;
    document.querySelectorAll('[data-mode]').forEach((button) => {
      button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
    });
    applyReaderLayout(true);
  }

  function setTheme(theme) {
    state.theme = theme;
    document.documentElement.dataset.theme = theme;
    applyReaderLayout(false);
  }

  function setCommentsVisible(visible) {
    state.commentsVisible = visible;
    const button = document.getElementById('comments-toggle');
    button.setAttribute('aria-pressed', String(visible));
    button.textContent = visible ? '开启' : '关闭';
    applyReaderLayout(false);
  }

  function setDanmaku(enabled) {
    state.danmakuEnabled = enabled;
    const button = document.getElementById('danmaku-toggle');
    const quick = document.getElementById('quick-menu-danmaku');
    button.setAttribute('aria-pressed', String(enabled));
    button.textContent = enabled ? '开启' : '关闭';
    quick.setAttribute('aria-pressed', String(enabled));
    syncDanmaku();
  }

  function toggleToc(open) {
    if (open) {
      closeSettings(false);
      closeComments(false);
      setQuickMenu(false);
    }
    document.getElementById('toc-panel').classList.toggle('open', open);
  }

  async function boot() {
    try {
      const response = await fetch('/api/book', { cache: 'no-store' });
      const payload = await response.json();
      if (!response.ok || !payload.ok) throw new Error(payload.error || '无法读取书籍');
      state.book = payload.book;
      document.title = `${state.book.title} · 骨碌碌专版`;
      document.getElementById('book-title').textContent = state.book.title;
      document.getElementById('book-author').textContent = state.book.author || '未知作者';
      renderToc();
      loadChapter(0);
    } catch (error) {
      const fatal = document.getElementById('fatal');
      fatal.hidden = false;
      fatal.textContent = error.message || String(error);
    }
  }

  frame.addEventListener('load', () => {
    const doc = currentDocument();
    if (!doc || !doc.body) return;
    applyReaderLayout(true);
    doc.addEventListener('click', (event) => {
      if (event.target && event.target.closest && event.target.closest('details')) {
        requestAnimationFrame(() => updatePageMetrics(false));
      }
    });
    doc.querySelectorAll('img').forEach((image) => {
      if (!image.complete) image.addEventListener('load', () => updatePageMetrics(false), { once: true });
    });
    syncDanmaku();
  });

  document.getElementById('previous').addEventListener('click', () => movePage(-1));
  document.getElementById('next').addEventListener('click', () => movePage(1));
  document.querySelectorAll('[data-mode]').forEach((button) => {
    button.addEventListener('click', () => setMode(button.dataset.mode));
  });
  document.getElementById('font-down').addEventListener('click', () => {
    state.fontSize = Math.max(12, state.fontSize - 1);
    document.getElementById('font-size').textContent = state.fontSize;
    applyReaderLayout(false);
  });
  document.getElementById('font-up').addEventListener('click', () => {
    state.fontSize = Math.min(32, state.fontSize + 1);
    document.getElementById('font-size').textContent = state.fontSize;
    applyReaderLayout(false);
  });
  document.getElementById('line-height').addEventListener('input', (event) => {
    state.lineHeight = Number(event.target.value);
    document.getElementById('line-height-value').textContent = state.lineHeight.toFixed(1);
    applyReaderLayout(false);
  });
  document.getElementById('content-width').addEventListener('input', (event) => {
    state.contentWidth = Number(event.target.value);
    document.getElementById('content-width-value').textContent = String(state.contentWidth);
    applyReaderLayout(false);
  });
  document.getElementById('theme-select').addEventListener('change', (event) => setTheme(event.target.value));
  document.getElementById('comments-toggle').addEventListener('click', () => {
    setCommentsVisible(!state.commentsVisible);
  });
  document.getElementById('danmaku-toggle').addEventListener('click', () => {
    setDanmaku(!state.danmakuEnabled);
  });
  document.getElementById('toc-toggle').addEventListener('click', () => toggleToc(true));
  document.getElementById('toc-close').addEventListener('click', () => toggleToc(false));
  document.getElementById('quick-menu-toggle').addEventListener('click', () => {
    setQuickMenu(document.getElementById('quick-menu').hidden);
  });
  document.getElementById('quick-settings').addEventListener('click', (event) => {
    openSettings(event.currentTarget);
  });
  document.getElementById('quick-comments').addEventListener('click', (event) => {
    openComments(null, event.currentTarget);
  });
  document.getElementById('quick-menu-settings').addEventListener('click', () => {
    openSettings(document.getElementById('quick-menu-toggle'));
  });
  document.getElementById('quick-menu-toc').addEventListener('click', () => toggleToc(true));
  document.getElementById('quick-menu-danmaku').addEventListener('click', () => {
    setDanmaku(!state.danmakuEnabled);
    setQuickMenu(false);
    document.getElementById('quick-menu-toggle').focus();
  });
  document.getElementById('settings-close').addEventListener('click', () => closeSettings(true));
  document.getElementById('comments-close').addEventListener('click', () => closeComments(true));

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (!document.getElementById('settings-panel').hidden) closeSettings(true);
    else if (!document.getElementById('comments-drawer').hidden) closeComments(true);
    else if (!document.getElementById('quick-menu').hidden) {
      setQuickMenu(false);
      document.getElementById('quick-menu-toggle').focus();
    } else if (document.getElementById('toc-panel').classList.contains('open')) {
      toggleToc(false);
      document.getElementById('toc-toggle').focus();
    }
  });

  let resizeTimer = null;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => applyReaderLayout(false), 100);
  });

  boot();
})();
