/** 骨碌碌在线评论：宿主层面板与只读弹幕，不修改 iframe 正文。 */
(function () {
  'use strict';

  const state = {
    sourceId: 0,
    chapterToken: 0,
    floorIds: [],
    floorLabels: new Map(),
    response: null,
    panelOpen: false,
    danmaku: false,
    danmakuTimer: null,
    danmakuCursor: 0,
  };

  const el = (id) => document.getElementById(id);

  function resetChapter() {
    state.chapterToken += 1;
    state.floorIds = [];
    state.floorLabels = new Map();
    state.response = null;
    stopDanmaku();
    renderEmpty('按当前章节在线加载');
  }

  function setBook(book) {
    state.sourceId = Number(book && book.gululu_source_id) || 0;
    state.panelOpen = false;
    state.danmaku = false;
    resetChapter();
    el('gululu-comments-btn').classList.toggle('hidden', !state.sourceId);
    el('gululu-comments-btn').classList.remove('active');
    el('gululu-comments-panel').classList.add('hidden');
    el('gululu-danmaku-toggle').checked = false;
  }

  function onChapterLoaded(doc) {
    resetChapter();
    if (!state.sourceId || !doc) return;
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
    if (!ids.length) {
      renderEmpty('本章没有可关联的评论楼层');
      return;
    }
    if (state.panelOpen || state.danmaku) load(false);
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
    let total = 0;
    floors.forEach((floor) => {
      const comments = Array.isArray(floor.comments) ? floor.comments : [];
      total += countComments(comments);
      list.appendChild(renderFloor(floor, comments));
    });
    const stale = floors.some((floor) => floor.stale);
    const cached = floors.length > 0 && floors.every((floor) => floor.cached);
    const status = el('gululu-comments-status');
    if (stale) status.textContent = `${total} 条 · 离线缓存`;
    else if (cached) status.textContent = `${total} 条 · 本地缓存`;
    else status.textContent = `${total} 条 · 已在线更新`;
    status.classList.toggle('error', !response || response.ok === false);
    if (!floors.length) renderEmpty((response && response.error) || '暂无评论', false, true);
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
    comments.forEach((comment) => section.appendChild(renderComment(comment, false)));
    return section;
  }

  function renderComment(comment, child) {
    const article = document.createElement('article');
    article.className = child ? 'gululu-online-comment child' : 'gululu-online-comment';
    const head = document.createElement('header');
    const author = document.createElement('strong');
    author.textContent = String(comment.author || '匿名用户');
    const meta = document.createElement('span');
    const reply = comment.reply_user ? ` 回复 @${comment.reply_user}` : '';
    meta.textContent = `${reply}  ${comment.created_at || ''} · 赞 ${Number(comment.likes) || 0}`.trim();
    head.append(author, meta);
    const body = document.createElement('p');
    body.textContent = String(comment.content || '');
    article.append(head, body);
    const children = Array.isArray(comment.children) ? comment.children : [];
    children.forEach((item) => article.appendChild(renderComment(item, true)));
    return article;
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

  function togglePanel(force) {
    if (!state.sourceId) return;
    state.panelOpen = typeof force === 'boolean' ? force : !state.panelOpen;
    el('gululu-comments-panel').classList.toggle('hidden', !state.panelOpen);
    el('gululu-comments-btn').classList.toggle('active', state.panelOpen);
    if (state.panelOpen && window.GululuImmersive) GululuImmersive.closePanel();
    if (state.panelOpen && !state.response) load(false);
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
    el('gululu-comments-btn').addEventListener('click', () => togglePanel());
    el('gululu-comments-close').addEventListener('click', () => togglePanel(false));
    el('gululu-comments-refresh').addEventListener('click', () => load(true));
    el('gululu-danmaku-toggle').addEventListener('change', (event) => {
      setDanmaku(event.target.checked);
    });
  });

  window.GululuComments = {
    setBook,
    onChapterLoaded,
    closePanel: () => togglePanel(false),
    close: () => {
      togglePanel(false);
      setDanmaku(false);
    },
  };
})();
