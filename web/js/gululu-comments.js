/** 骨碌碌在线评论：宿主层面板与只读弹幕，不修改 iframe 正文。 */
(function () {
  'use strict';

  const state = {
    sourceId: 0,
    chapterToken: 0,
    chapterDocument: null,
    floorIds: [],
    floorLabels: new Map(),
    response: null,
    panelOpen: false,
    selectedFloorId: null,
    displayMode: 'panel',
    returnFocus: null,
    danmaku: false,
    danmakuTimer: null,
    danmakuCursor: 0,
  };

  const el = (id) => document.getElementById(id);

  function resetChapter() {
    state.chapterToken += 1;
    removeChapterUi();
    state.chapterDocument = null;
    state.floorIds = [];
    state.floorLabels = new Map();
    state.response = null;
    state.selectedFloorId = null;
    stopDanmaku();
    renderEmpty('按当前章节在线加载');
  }

  function setBook(book) {
    state.sourceId = Number(book && book.gululu_source_id) || 0;
    state.panelOpen = false;
    state.danmaku = false;
    resetChapter();
    el('gululu-comments-btn').classList.add('hidden');
    el('gululu-comments-btn').classList.remove('active');
    el('gululu-comments-panel').classList.add('hidden');
    const quick = el('gululu-quick-comments');
    if (quick) {
      quick.classList.remove('active');
      quick.setAttribute('aria-expanded', 'false');
    }
    el('gululu-danmaku-toggle').checked = false;
    el('gululu-comments-mode').value = state.displayMode;
  }

  function onChapterLoaded(doc) {
    resetChapter();
    if (!state.sourceId || !doc) return;
    state.chapterDocument = doc;
    const ids = [];
    if (App.state.chapterIndex === 0) {
      ids.push(0);
      state.floorLabels.set(0, '作品评论');
    }
    doc.querySelectorAll('.gululu-floor[id^="floor-"]').forEach((section) => {
      const floorId = Number(section.id.slice('floor-'.length));
      if (!Number.isSafeInteger(floorId) || floorId <= 0 || ids.includes(floorId)) return;
      ids.push(floorId);
      const label = section.querySelector('.floor-number');
      state.floorLabels.set(floorId, (label && label.textContent.trim()) || `楼层 ${floorId}`);
    });
    state.floorIds = ids;
    installChapterUi(doc);
    if (!ids.length) {
      renderEmpty('本章没有可关联的评论楼层');
      return;
    }
    if (state.panelOpen || state.danmaku) load(false);
  }

  function removeChapterUi() {
    const doc = state.chapterDocument;
    if (!doc) return;
    doc.querySelectorAll('.gululu-floor-comment-button, .gululu-inline-comments')
      .forEach((node) => node.remove());
    const style = doc.getElementById('__gululu_online_comments__');
    if (style) style.remove();
  }

  function installChapterUi(doc) {
    if (!doc.head || !doc.body) return;
    const style = doc.createElement('style');
    style.id = '__gululu_online_comments__';
    style.textContent = `
      .floor-head { display:flex; align-items:center; gap:.6em; }
      .floor-title { min-width:0; flex:1; }
      .gululu-floor-comment-button {
        flex:0 0 auto; border:1px solid color-mix(in srgb, currentColor 24%, transparent);
        border-radius:4px; padding:.28em .6em; background:transparent; color:inherit;
        cursor:pointer; font:inherit; font-size:.86em;
      }
      .gululu-floor-comment-button:hover {
        background:color-mix(in srgb, currentColor 8%, transparent);
      }
      .gululu-floor-comment-button:focus-visible,
      .gululu-inline-comments > summary:focus-visible { outline:2px solid currentColor; outline-offset:2px; }
      .gululu-inline-comments {
        border-top:1px solid color-mix(in srgb, currentColor 18%, transparent);
        margin:1em 0 0; padding:.65em 0 0;
      }
      .gululu-inline-comments > summary { cursor:pointer; font-weight:600; }
      .gululu-inline-comment-list { margin:.65em 0 0; }
      .gululu-inline-comment {
        border-left:2px solid color-mix(in srgb, currentColor 20%, transparent);
        margin:.7em 0; padding:.15em 0 .15em .75em;
      }
      .gululu-inline-comment header { display:flex; align-items:baseline; flex-wrap:wrap; gap:.4em .7em; }
      .gululu-inline-comment header strong { font-size:.88em; }
      .gululu-inline-comment header span { opacity:.66; font-size:.75em; overflow-wrap:anywhere; }
      .gululu-inline-comment-body { margin:.3em 0 0; white-space:pre-wrap; overflow-wrap:anywhere; }
      .gululu-inline-comment.child { margin-left:.8em; }
      .gululu-inline-comment-empty { margin:.65em 0; opacity:.66; }
    `;
    doc.head.appendChild(style);
    doc.querySelectorAll('.gululu-floor[id^="floor-"]').forEach((section) => {
      const floorId = Number(section.id.slice('floor-'.length));
      if (!Number.isSafeInteger(floorId) || floorId <= 0) return;
      const head = section.querySelector('.floor-head');
      if (!head) return;
      const button = doc.createElement('button');
      button.type = 'button';
      button.className = 'gululu-floor-comment-button';
      button.dataset.floorId = String(floorId);
      button.setAttribute('data-textpos-exclude', 'true');
      button.setAttribute('aria-label', `${state.floorLabels.get(floorId) || '当前楼层'}评论`);
      button.textContent = '评论';
      button.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        if (state.displayMode === 'inline') {
          toggleInlineFloor(floorId);
        } else {
          openFloor(floorId, button);
        }
      });
      head.appendChild(button);
    });
  }

  function scheduleReaderLayout() {
    requestAnimationFrame(() => {
      if (window.Reader && Reader.applyLayout) Reader.applyLayout();
    });
  }

  function responseFloor(floorId) {
    const floors = state.response && Array.isArray(state.response.floors)
      ? state.response.floors : [];
    return floors.find((floor) => Number(floor.floor_id) === Number(floorId)) || null;
  }

  function updateFloorButtons() {
    const doc = state.chapterDocument;
    if (!doc) return;
    doc.querySelectorAll('.gululu-floor-comment-button').forEach((button) => {
      const floor = responseFloor(Number(button.dataset.floorId));
      const count = floor ? countComments(Array.isArray(floor.comments) ? floor.comments : []) : 0;
      button.textContent = floor ? `评论 ${count}` : '评论';
      button.setAttribute('aria-expanded', String(
        state.displayMode === 'inline'
          ? !!doc.querySelector(`.gululu-inline-comments[data-floor-id="${button.dataset.floorId}"][open]`)
          : state.panelOpen && state.selectedFloorId === Number(button.dataset.floorId)
      ));
    });
  }

  function openFloor(floorId, returnFocus) {
    state.selectedFloorId = floorId;
    state.returnFocus = returnFocus || null;
    togglePanel(true, true);
  }

  function toggleInlineFloor(floorId) {
    const doc = state.chapterDocument;
    if (!doc) return;
    const details = doc.querySelector(`.gululu-inline-comments[data-floor-id="${floorId}"]`);
    if (!details) {
      if (!state.response) load(false);
      return;
    }
    details.open = !details.open;
    updateFloorButtons();
    scheduleReaderLayout();
  }

  async function load(refresh) {
    if (!state.sourceId || !state.floorIds.length) return;
    const token = state.chapterToken;
    const refreshButton = el('gululu-comments-refresh');
    refreshButton.disabled = true;
    renderEmpty(refresh ? '正在刷新评论…' : '正在加载评论…', true);
    try {
      const batches = [];
      for (let index = 0; index < state.floorIds.length; index += 50) {
        batches.push(state.floorIds.slice(index, index + 50));
      }
      const responses = await Promise.all(batches.map((floorIds) => (
        Api.gululuGetComments(state.sourceId, floorIds, !!refresh)
      )));
      const response = {
        ok: responses.every((item) => item && item.ok !== false),
        source_id: state.sourceId,
        floors: responses.flatMap((item) => (
          item && Array.isArray(item.floors) ? item.floors : []
        )),
        error: responses.map((item) => item && item.error).filter(Boolean).join('; '),
      };
      if (token !== state.chapterToken) return;
      state.response = response;
      renderResponse(response);
      renderInlineComments();
      updateFloorButtons();
      if (state.danmaku) startDanmaku();
    } catch (error) {
      if (token !== state.chapterToken) return;
      state.response = null;
      renderEmpty('评论加载失败：' + (error.message || error), false, true);
    } finally {
      if (token === state.chapterToken) refreshButton.disabled = false;
    }
  }

  function renderResponse(response) {
    const list = el('gululu-comments-list');
    list.innerHTML = '';
    const floors = response && Array.isArray(response.floors) ? response.floors : [];
    const shownFloors = state.selectedFloorId === null
      ? floors
      : floors.filter((floor) => Number(floor.floor_id) === state.selectedFloorId);
    let total = 0;
    shownFloors.forEach((floor) => {
      const comments = Array.isArray(floor.comments) ? floor.comments : [];
      total += countComments(comments);
      list.appendChild(renderFloor(floor, comments));
    });
    const stale = shownFloors.some((floor) => floor.stale);
    const cached = shownFloors.length > 0 && shownFloors.every((floor) => floor.cached);
    const status = el('gululu-comments-status');
    if (stale) status.textContent = `${total} 条 · 离线缓存`;
    else if (cached) status.textContent = `${total} 条 · 本地缓存`;
    else status.textContent = `${total} 条 · 已在线更新`;
    status.classList.toggle('error', !response || response.ok === false);
    if (!shownFloors.length) renderEmpty((response && response.error) || '暂无评论', false, true);
  }

  function renderFloor(floor, comments) {
    const section = document.createElement('section');
    section.className = 'gululu-comment-floor';
    const heading = document.createElement('h3');
    const label = state.floorLabels.get(Number(floor.floor_id)) || `楼层 ${floor.floor_id}`;
    heading.textContent = `${label} · ${countComments(comments)}`;
    section.appendChild(heading);
    if (floor.error) {
      const warning = document.createElement('p');
      warning.className = 'gululu-comment-warning';
      warning.textContent = floor.stale ? '网络不可用，正在显示上次缓存' : floor.error;
      section.appendChild(warning);
    }
    if (!comments.length) {
      const empty = document.createElement('p');
      empty.className = 'gululu-comment-empty';
      empty.textContent = '暂无评论';
      section.appendChild(empty);
      return section;
    }
    comments.forEach((comment) => section.appendChild(renderComment(comment, false, document, false)));
    return section;
  }

  function renderComment(comment, child, ownerDocument, inline) {
    const article = ownerDocument.createElement('article');
    const baseClass = inline ? 'gululu-inline-comment' : 'gululu-online-comment';
    article.className = child ? `${baseClass} child` : baseClass;
    const head = ownerDocument.createElement('header');
    const author = ownerDocument.createElement('strong');
    author.textContent = String(comment.author || '匿名用户');
    const meta = ownerDocument.createElement('span');
    const reply = comment.reply_user ? ` 回复 @${comment.reply_user}` : '';
    meta.textContent = `${reply}  ${comment.created_at || ''} · 赞 ${Number(comment.likes) || 0}`.trim();
    head.append(author, meta);
    const body = ownerDocument.createElement('p');
    if (inline) body.className = 'gululu-inline-comment-body';
    body.textContent = String(comment.content || '');
    article.append(head, body);
    const children = Array.isArray(comment.children) ? comment.children : [];
    children.forEach((item) => article.appendChild(
      renderComment(item, true, ownerDocument, inline)
    ));
    return article;
  }

  function renderInlineComments() {
    const doc = state.chapterDocument;
    if (!doc) return;
    doc.querySelectorAll('.gululu-inline-comments').forEach((node) => node.remove());
    if (state.displayMode !== 'inline' || !state.response) {
      scheduleReaderLayout();
      return;
    }
    const floors = Array.isArray(state.response.floors) ? state.response.floors : [];
    floors.forEach((floor) => {
      const floorId = Number(floor.floor_id);
      let anchor = floorId === 0
        ? (doc.querySelector('.book-meta') || doc.querySelector('.chapter-title'))
        : doc.getElementById(`floor-${floorId}`);
      if (!anchor) return;
      const comments = Array.isArray(floor.comments) ? floor.comments : [];
      const details = doc.createElement('details');
      details.className = 'gululu-inline-comments';
      details.dataset.floorId = String(floorId);
      details.setAttribute('data-textpos-exclude', 'true');
      const summary = doc.createElement('summary');
      summary.textContent = `评论 ${countComments(comments)}`;
      const list = doc.createElement('div');
      list.className = 'gululu-inline-comment-list';
      if (comments.length) {
        comments.forEach((comment) => list.appendChild(
          renderComment(comment, false, doc, true)
        ));
      } else {
        const empty = doc.createElement('p');
        empty.className = 'gululu-inline-comment-empty';
        empty.textContent = floor.error || '暂无评论';
        list.appendChild(empty);
      }
      details.append(summary, list);
      details.addEventListener('toggle', () => {
        updateFloorButtons();
        scheduleReaderLayout();
      });
      if (floorId === 0) anchor.insertAdjacentElement('afterend', details);
      else anchor.appendChild(details);
    });
    updateFloorButtons();
    scheduleReaderLayout();
  }

  function countComments(comments) {
    return comments.reduce((total, item) => (
      total + 1 + countComments(Array.isArray(item.children) ? item.children : [])
    ), 0);
  }

  function flattenComments() {
    const output = [];
    const visit = (items) => items.forEach((item) => {
      const text = String(item.content || '').replace(/\s+/g, ' ').trim();
      if (text) output.push(text.slice(0, 100));
      visit(Array.isArray(item.children) ? item.children : []);
    });
    const floors = state.response && Array.isArray(state.response.floors)
      ? state.response.floors : [];
    floors.forEach((floor) => visit(Array.isArray(floor.comments) ? floor.comments : []));
    return output;
  }

  function shootDanmaku() {
    const comments = flattenComments();
    if (!state.danmaku || !comments.length) return;
    const layer = el('gululu-danmaku-layer');
    const item = document.createElement('span');
    item.className = 'gululu-danmaku-item';
    item.textContent = comments[state.danmakuCursor % comments.length];
    item.style.setProperty('--danmaku-top', `${10 + (state.danmakuCursor % 6) * 34}px`);
    item.style.setProperty('--danmaku-duration', `${13 + (state.danmakuCursor % 5)}s`);
    state.danmakuCursor += 1;
    item.addEventListener('animationend', () => item.remove(), { once: true });
    layer.appendChild(item);
  }

  function startDanmaku() {
    stopDanmaku();
    if (!state.danmaku || !flattenComments().length) return;
    el('gululu-danmaku-layer').classList.add('active');
    shootDanmaku();
    state.danmakuTimer = setInterval(shootDanmaku, 2400);
  }

  function stopDanmaku() {
    clearInterval(state.danmakuTimer);
    state.danmakuTimer = null;
    state.danmakuCursor = 0;
    const layer = el('gululu-danmaku-layer');
    if (layer) {
      layer.classList.remove('active');
      layer.innerHTML = '';
    }
  }

  function togglePanel(force, keepSelection, restoreFocus) {
    if (!state.sourceId) return;
    const wasOpen = state.panelOpen;
    state.panelOpen = typeof force === 'boolean' ? force : !state.panelOpen;
    if (state.panelOpen && !keepSelection) state.selectedFloorId = null;
    el('gululu-comments-panel').classList.toggle('hidden', !state.panelOpen);
    el('gululu-comments-btn').classList.toggle('active', state.panelOpen);
    const quick = el('gululu-quick-comments');
    if (quick) {
      quick.classList.toggle('active', state.panelOpen);
      quick.setAttribute('aria-expanded', String(state.panelOpen));
    }
    if (state.panelOpen) {
      if (window.App && App.setGululuQuickMenu) App.setGululuQuickMenu(false, false);
      if (window.ViewMenu) ViewMenu.close(false);
      if (window.GululuImmersive) GululuImmersive.closePanel();
    }
    if (state.panelOpen && state.response) renderResponse(state.response);
    if (state.panelOpen && !state.response) load(false);
    if (wasOpen && !state.panelOpen && restoreFocus !== false && state.returnFocus) {
      state.returnFocus.focus();
    }
    if (!state.panelOpen) state.returnFocus = null;
    updateFloorButtons();
  }

  function setDisplayMode(mode) {
    if (mode !== 'panel' && mode !== 'inline') return;
    state.displayMode = mode;
    el('gululu-comments-mode').value = mode;
    if (mode === 'inline') {
      togglePanel(false);
      if (state.response) renderInlineComments();
      else if (state.floorIds.length) load(false);
    } else {
      renderInlineComments();
    }
    updateFloorButtons();
    if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
  }

  function setDanmaku(enabled) {
    state.danmaku = !!enabled;
    el('gululu-danmaku-toggle').checked = state.danmaku;
    if (!state.danmaku) {
      stopDanmaku();
    } else if (state.response) {
      startDanmaku();
    } else {
      load(false);
    }
    if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
  }

  function renderEmpty(message, loading, error) {
    const list = el('gululu-comments-list');
    if (!list) return;
    list.innerHTML = '';
    const text = document.createElement('p');
    text.className = 'gululu-comments-empty' + (error ? ' error' : '');
    text.textContent = message;
    list.appendChild(text);
    const status = el('gululu-comments-status');
    if (status) status.textContent = loading ? '正在连接骨碌碌' : message;
  }

  document.addEventListener('DOMContentLoaded', () => {
    el('gululu-comments-btn').addEventListener('click', (event) => {
      state.returnFocus = event.currentTarget;
      togglePanel(undefined, false);
    });
    el('gululu-comments-close').addEventListener('click', () => togglePanel(false, false, true));
    el('gululu-comments-refresh').addEventListener('click', () => load(true));
    el('gululu-comments-mode').addEventListener('change', (event) => {
      setDisplayMode(event.target.value);
    });
    el('gululu-danmaku-toggle').addEventListener('change', (event) => {
      setDanmaku(event.target.checked);
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && state.panelOpen) togglePanel(false, false, true);
    });
  });

  window.GululuComments = {
    setBook,
    onChapterLoaded,
    setDisplayMode,
    setDanmaku,
    togglePanel: (trigger) => {
      state.returnFocus = trigger || el('gululu-quick-comments');
      togglePanel(undefined, false, true);
    },
    closePanel: () => togglePanel(false, false, false),
    close: () => setBook(null),
    snapshot: () => ({
      sourceId: state.sourceId,
      panelOpen: state.panelOpen,
      displayMode: state.displayMode,
      danmaku: state.danmaku,
    }),
  };
})();
