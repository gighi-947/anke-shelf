/**
 * 标注系统：选中工具栏（高亮色板/笔记）、mark 注入、书签、侧栏列表、导出。
 *
 * 坐标系：text_offset（TextPos）。创建/删除/修改后通过 Reader.applyMode()
 * 重载当前章并保位 —— 重载时注入全部 mark，避免就地 DOM 操作的偏移漂移。
 */
(function () {
  'use strict';

  const COLORS = ['yellow', 'green', 'blue', 'pink', 'purple', 'cyan'];
  const COLOR_HEX = {
    yellow: 'rgba(250,204,21,0.40)',
    green: 'rgba(74,222,128,0.35)',
    blue: 'rgba(56,189,248,0.35)',
    pink: 'rgba(244,114,182,0.35)',
    purple: 'rgba(192,132,252,0.35)',
    cyan: 'rgba(34,211,238,0.35)',
  };

  const frameEl = () => document.getElementById('chapter-frame');
  const popupEl = () => document.getElementById('annotation-popup');

  let pending = null; // {start, end, text}

  /** 重新拉取本书标注 + 渲染侧栏（外部/桥接改动后同步）。 */
  async function refresh() {
    try {
      const data = await Api.getAnnotations( App.state.bookId);
      state.highlights = data.highlights || [];
      state.bookmarks = data.bookmarks || [];
    } catch (e) {
      state.highlights = [];
      state.bookmarks = [];
    }
    renderSidebar();
  }

  /** 拉取本书标注 + 渲染侧栏。打开书后调用。 */
  async function init() {
    await refresh();
  }

  const state = { highlights: [], bookmarks: [] };

  /** 注入当前章高亮 mark（reader.js onload 时调用，必须在 TextPos 重建前）。 */
  function injectForChapter(doc) {
    const ctx = App.state.textCtx;
    if (!ctx) return;
    const list = state.highlights.filter((h) => h.chapter_index === App.state.chapterIndex);
    for (const h of list) wrap(ctx, doc, h);
  }

  /** 把区间文本包成连续 mark（跨节点安全）。
   * 先完整遍历收集文本节点（不改结构），再逐个 surroundContents，避免
   * 遍历中修改 fragment 导致的未定义行为。 */
  function wrap(ctx, doc, h) {
    const sp = TextPos.plainToPoint(ctx, h.start_offset);
    const ep = TextPos.plainToPoint(ctx, h.end_offset);
    if (!sp || !ep) return;
    const range = doc.createRange();
    range.setStart(sp.node, sp.charIndex);
    range.setEnd(ep.node, ep.charIndex);
    if (range.collapsed) return;
    const frag = range.extractContents();
    // 先收集全部文本节点
    const nodes = [];
    const walker = doc.createTreeWalker(frag, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = walker.nextNode())) {
      if (n.data.length) nodes.push(n);
    }
    // 再逐个包 mark（不改变遍历集合）
    for (const tn of nodes) {
      const m = doc.createElement('mark');
      m.className = 'hl-mark hl-' + h.color;
      m.dataset.hl = h.id;
      m.addEventListener('click', (e) => {
        e.preventDefault();
        onMarkClick(h);
      });
      const r = doc.createRange();
      r.selectNode(tn);
      r.surroundContents(m);
    }
    range.insertNode(frag);
  }

  // ================= 选中工具栏 =================

  function bindSelection(doc) {
    doc.addEventListener('mouseup', () => {
      setTimeout(() => {
        const sel = doc.getSelection();
        if (!sel || sel.isCollapsed || sel.rangeCount === 0) { hideToolbar(); return; }
        const range = sel.getRangeAt(0);
        const offs = TextPos.rangeToOffsets(App.state.textCtx, range);
        if (!offs || offs[0] === offs[1]) { hideToolbar(); return; }
        showToolbar(range, offs);
      }, 10);
    });
    // 点击空白关闭
    doc.addEventListener('mousedown', (e) => {
      if (!e.target.closest || !e.target.closest('.hl-mark')) hideToolbar();
    });
  }

  function showToolbar(range, offs) {
    const popup = popupEl();
    popup.innerHTML = '';
    const ctx = App.state.textCtx;
    pending = {
      start: offs[0],
      end: offs[1],
      text: ctx ? ctx.text.slice(offs[0], offs[1]).slice(0, 200) : '',
    };

    for (const c of COLORS) {
      const b = document.createElement('button');
      b.className = 'hl-color';
      b.style.background = COLOR_HEX[c];
      b.title = c;
      b.addEventListener('click', () => createHighlight(c));
      popup.appendChild(b);
    }

    const noteBtn = document.createElement('button');
    noteBtn.className = 'ann-btn';
    noteBtn.title = '加笔记';
    noteBtn.appendChild(Icons.icon('note', 16));
    noteBtn.addEventListener('click', () => {
      hideToolbar();
      openNoteModal(null, pending.start, pending.end);
    });
    popup.appendChild(noteBtn);

    const closeBtn = document.createElement('button');
    closeBtn.className = 'ann-btn';
    closeBtn.title = '关闭';
    closeBtn.appendChild(Icons.icon('close', 16));
    closeBtn.addEventListener('click', hideToolbar);
    popup.appendChild(closeBtn);

    const rect = range.getBoundingClientRect();
    const fr = frameEl().getBoundingClientRect();
    popup.style.left = Math.max(8, fr.left + rect.left + rect.width / 2 - 100) + 'px';
    popup.style.top = Math.max(8, fr.top + rect.top - 46) + 'px';
    popup.classList.remove('hidden');
  }

  function hideToolbar() {
    popupEl().classList.add('hidden');
    pending = null;
  }

  async function createHighlight(color) {
    if (!pending) return;
    const r = await Api.saveAnnotation( App.state.bookId, App.state.chapterIndex,
      pending.start, pending.end, pending.text, color,
    );
    if (r && r.error) { Toast.show(r.error, true); hideToolbar(); return; }
    state.highlights.push(r);
    hideToolbar();
    renderSidebar();
    Reader.applyMode(); // 重载注入 mark
  }

  // ================= mark 点击 / 笔记 / 删除 =================

  function onMarkClick(h) {
    openNoteModal(h);
  }

  function openNoteModal(ann, start, end) {
    const root = document.getElementById('modal-root');
    root.innerHTML = '';
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    const box = document.createElement('div');
    box.className = 'modal';
    box.innerHTML = '<div class="modal-title">' + (ann ? '编辑笔记' : '添加笔记') + '</div>';
    if (ann) {
      const quote = document.createElement('div');
      quote.className = 'modal-quote';
      quote.textContent = ann.text;
      box.appendChild(quote);
    }
    const ta = document.createElement('textarea');
    ta.className = 'modal-input';
    ta.placeholder = '写下你的想法…';
    ta.value = ann ? ann.note : '';
    box.appendChild(ta);
    const actions = document.createElement('div');
    actions.className = 'modal-actions';
    if (ann) {
      const del = document.createElement('button');
      del.className = 'btn btn-danger';
      del.textContent = '删除高亮';
      del.addEventListener('click', async () => {
        await Api.deleteAnnotation( App.state.bookId, ann.id);
        state.highlights = state.highlights.filter((x) => x.id !== ann.id);
        root.innerHTML = '';
        renderSidebar();
        Reader.applyMode();
      });
      actions.appendChild(del);
    }
    const cancel = document.createElement('button');
    cancel.className = 'btn';
    cancel.textContent = '取消';
    cancel.addEventListener('click', () => { root.innerHTML = ''; });
    actions.appendChild(cancel);
    const save = document.createElement('button');
    save.className = 'btn btn-primary';
    save.textContent = '保存';
    save.addEventListener('click', async () => {
      if (ann) {
        await Api.updateAnnotation( App.state.bookId, ann.id, { note: ta.value });
        const h = state.highlights.find((x) => x.id === ann.id);
        if (h) h.note = ta.value;
      } else {
        await Api.saveAnnotation( App.state.bookId, App.state.chapterIndex,
          start, end, '', 'yellow', ta.value,
        );
        // 重新拉取以获取新记录
        await init();
      }
      root.innerHTML = '';
      renderSidebar();
      Reader.applyMode();
    });
    actions.appendChild(save);
    box.appendChild(actions);
    overlay.appendChild(box);
    root.appendChild(overlay);
    ta.focus();
  }

  // ================= 书签 =================

  async function toggleBookmark(chapterIndex, offset) {
    const ctx = App.state.textCtx;
    const text = ctx ? ctx.text.slice(offset, offset + 60) : '';
    const existing = state.bookmarks.find(
      (b) => b.chapter_index === chapterIndex && Math.abs(b.offset - offset) < 80,
    );
    if (existing) {
      await Api.deleteBookmark( App.state.bookId, existing.id);
      state.bookmarks = state.bookmarks.filter((b) => b.id !== existing.id);
      Toast.show('已删除书签');
    } else {
      const r = await Api.addBookmark( App.state.bookId, chapterIndex, offset, text);
      if (r && r.error) { Toast.show(r.error, true); return; }
      state.bookmarks.push(r);
      Toast.show('已添加书签');
    }
    renderSidebar();
  }

  // ================= 侧栏 =================

  function renderSidebar() {
    Sidebar.renderBookmarks = renderBookmarks;
    Sidebar.renderAnnotations = renderAnnotations;
    Sidebar.renderBookmarks();
    Sidebar.renderAnnotations();
  }

  function renderBookmarks() {
    const box = document.getElementById('bookmark-list');
    box.innerHTML = '';
    if (!state.bookmarks.length) {
      box.innerHTML = '<p class="muted side-empty">暂无书签</p>';
      return;
    }
    for (const bm of state.bookmarks) {
      const item = document.createElement('div');
      item.className = 'side-item';
      const txt = document.createElement('div');
      txt.textContent = bm.text || '书签';
      const meta = document.createElement('div');
      meta.className = 'side-meta';
      meta.textContent = `第 ${bm.chapter_index + 1} 章 · ` + (bm.created_at || '').slice(0, 10);
      const del = document.createElement('button');
      del.className = 'side-delete';
      del.textContent = '删除';
      del.addEventListener('click', async (e) => {
        e.stopPropagation();
        await Api.deleteBookmark( App.state.bookId, bm.id);
        state.bookmarks = state.bookmarks.filter((x) => x.id !== bm.id);
        renderBookmarks();
      });
      item.append(txt, meta, del);
      item.addEventListener('click', () => {
        Sidebar.close();
        Reader.loadChapter(bm.chapter_index, bm.offset);
      });
      box.appendChild(item);
    }
  }

  function renderAnnotations() {
    const box = document.getElementById('annotation-list');
    box.innerHTML = '';
    // 导出按钮
    const head = document.createElement('div');
    head.className = 'side-list-head';
    const exp = document.createElement('button');
    exp.className = 'btn';
    exp.textContent = '导出';
    exp.addEventListener('click', exportDownload);
    head.appendChild(exp);
    box.appendChild(head);

    if (!state.highlights.length) {
      const empty = document.createElement('p');
      empty.className = 'muted side-empty';
      empty.textContent = '暂无标注';
      box.appendChild(empty);
      return;
    }
    // 按章分组
    const byCh = {};
    for (const h of state.highlights) (byCh[h.chapter_index] = byCh[h.chapter_index] || []).push(h);
    for (const ci of Object.keys(byCh).map(Number).sort((a, b) => a - b)) {
      for (const h of byCh[ci]) {
        const item = document.createElement('div');
        item.className = 'side-item';
        const row = document.createElement('div');
        const dot = document.createElement('span');
        dot.className = 'side-highlight-dot';
        dot.style.background = COLOR_HEX[h.color] || '#ccc';
        const txt = document.createElement('span');
        txt.textContent = (h.text || '').slice(0, 80);
        row.append(dot, ' ', txt);
        const meta = document.createElement('div');
        meta.className = 'side-meta';
        meta.textContent = `第 ${h.chapter_index + 1} 章`;
        const note = document.createElement('div');
        if (h.note) {
          note.className = 'side-note';
          note.textContent = h.note.slice(0, 80);
        }
        const actions = document.createElement('div');
        actions.className = 'side-actions';
        const del = document.createElement('button');
        del.className = 'side-delete';
        del.textContent = '删除';
        del.addEventListener('click', async (e) => {
          e.stopPropagation();
          await Api.deleteAnnotation( App.state.bookId, h.id);
          state.highlights = state.highlights.filter((x) => x.id !== h.id);
          renderAnnotations();
          Reader.applyMode();
        });
        actions.appendChild(del);
        item.append(row, meta, note, actions);
        item.addEventListener('click', () => {
          Sidebar.close();
          Reader.loadChapter(h.chapter_index, h.start_offset);
        });
        box.appendChild(item);
      }
    }
  }

  async function exportDownload() {
    const md = await Api.exportAnnotations( App.state.bookId, 'markdown');
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = (App.state.book.title || 'book') + '-标注.md';
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    Toast.show('已导出 Markdown');
  }

  window.Annotations = {
    init,
    refresh,
    injectForChapter,
    bindSelection,
    toggleBookmark,
    hideToolbar,
  };

  // 侧栏渲染方法暴露
  Sidebar.renderBookmarks = renderBookmarks;
  Sidebar.renderAnnotations = renderAnnotations;
})();
