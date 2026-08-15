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
    commentsVisible: true,
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
      .floor-head, .book-meta { color:${palette.muted} !important; border-color:${palette.line} !important; }
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
      #__gululu_debug_flow__ > figure, #__gululu_debug_flow__ > details, .gululu-floor { break-inside:avoid; }
    `;
    style.textContent = common + (state.mode === 'paged' ? pagedCss : scrollCss);
    doc.body.classList.toggle('__debug_comments_hidden__', !state.commentsVisible);
    requestAnimationFrame(() => updatePageMetrics(resetPage));
  }

  function currentCommentTexts() {
    const doc = currentDocument();
    if (!doc) return [];
    return Array.from(doc.querySelectorAll('.gululu-comment-text'))
      .map((item) => item.textContent.trim().replace(/\s+/g, ' '))
      .filter(Boolean);
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
    commentsButton.disabled = !available;
    danmakuButton.disabled = !available;
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
    const chapter = state.book.chapters[index];
    frame.src = `/book/${encodedHref(chapter.href)}`;
    markActiveChapter();
    updatePosition();
    if (window.innerWidth <= 960) toggleToc(false);
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
    document.getElementById('comments-toggle').setAttribute('aria-pressed', String(visible));
    applyReaderLayout(false);
  }

  function setDanmaku(enabled) {
    state.danmakuEnabled = enabled;
    document.getElementById('danmaku-toggle').setAttribute('aria-pressed', String(enabled));
    syncDanmaku();
  }

  function toggleToc(open) {
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
    applyReaderLayout(false);
  });
  document.getElementById('content-width').addEventListener('input', (event) => {
    state.contentWidth = Number(event.target.value);
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

  let resizeTimer = null;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => applyReaderLayout(false), 100);
  });

  boot();
})();
