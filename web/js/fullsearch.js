/**
 * 独立全文检索页（全屏覆盖阅读界面）。
 *
 * 设计参考：
 * - flow：结果总数 + 按章分组、展开/折叠、全部展开/收起；
 * - Koodo Reader：分页展示、点击结果跳转并高亮；
 * - Foliate：大小写敏感、全词匹配选项；
 * - Readest：按书保存搜索历史。
 *
 * 后端按“每章限量”返回命中（默认 50 条/章），全书总命中数与命中章节数
 * 单独统计，因此高频关键词（如 NGA 安科里的角色名）不会挤掉靠后章节；
 * 每章还提供“显示更多”续取剩余命中。
 */
(function () {
  'use strict';

  const PER_CHAPTER = 50;
  const HISTORY_MAX = 10;
  const DEFAULT_EXPANDED = 5; // 默认只展开前 5 个章节组，避免高频词渲染爆炸

  const state = {
    q: '',
    caseSensitive: false,
    wholeWord: false,
    data: null,          // 最近一次 search 的响应
    expanded: new Set(), // 已展开的 chapter_index
    loadingMore: new Set(),
    debounce: null,
    retry: null,
  };

  const $ = (id) => document.getElementById(id);

  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[c]));
  }

  function highlightSnippet(snippet, q) {
    let s = esc(snippet);
    const src = esc(q).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    if (!src) return s;
    try {
      s = s.replace(new RegExp(src, 'gi'), '<mark>$&</mark>');
    } catch (e) { /* 保留原文 */ }
    return s;
  }

  function historyKey() {
    return 'ankeshelf.search.history.' + (App.state.bookId || '');
  }

  function loadHistory() {
    try {
      const h = JSON.parse(localStorage.getItem(historyKey()) || '[]');
      return Array.isArray(h) ? h.filter((x) => typeof x === 'string' && x) : [];
    } catch (e) {
      return [];
    }
  }

  function saveHistory(q) {
    try {
      const h = loadHistory().filter((x) => x !== q);
      h.unshift(q);
      localStorage.setItem(historyKey(), JSON.stringify(h.slice(0, HISTORY_MAX)));
    } catch (e) { /* 隐私模式/磁盘满时静默失败 */ }
  }

  function ensureBuilt() {
    let view = $('search-view');
    if (view) return view;

    view = document.createElement('div');
    view.className = 'search-view hidden';
    view.id = 'search-view';

    const head = document.createElement('div');
    head.className = 'search-head';
    const back = document.createElement('button');
    back.className = 'top-btn';
    back.title = '返回阅读 (Esc)';
    back.appendChild(Icons.icon('back', 18));
    back.addEventListener('click', () => FullSearch.close());
    const title = document.createElement('div');
    title.className = 'search-title';
    title.textContent = '全文检索';
    const headActions = document.createElement('div');
    headActions.className = 'search-head-actions';
    const toggleAll = document.createElement('button');
    toggleAll.className = 'vm-btn fs-toggle-all';
    toggleAll.id = 'fs-toggle-all';
    toggleAll.textContent = '展开全部';
    toggleAll.addEventListener('click', () => FullSearch.toggleAll());
    const caseLabel = document.createElement('label');
    caseLabel.className = 'fs-opt';
    caseLabel.title = '区分英文大小写（中文不受影响）';
    const caseBox = document.createElement('input');
    caseBox.type = 'checkbox';
    caseBox.id = 'fs-case';
    caseBox.addEventListener('change', () => {
      state.caseSensitive = caseBox.checked;
      FullSearch.query(true);
    });
    caseLabel.append(caseBox, document.createTextNode('大小写敏感'));
    const wordLabel = document.createElement('label');
    wordLabel.className = 'fs-opt';
    wordLabel.title = '只匹配完整英文单词（中文关键词不受影响）';
    const wordBox = document.createElement('input');
    wordBox.type = 'checkbox';
    wordBox.id = 'fs-word';
    wordBox.addEventListener('change', () => {
      state.wholeWord = wordBox.checked;
      FullSearch.query(true);
    });
    wordLabel.append(wordBox, document.createTextNode('全词匹配'));
    headActions.append(toggleAll, caseLabel, wordLabel);
    head.append(back, title, headActions);

    const body = document.createElement('div');
    body.className = 'search-body';

    const searchRow = document.createElement('div');
    searchRow.className = 'fs-search-row';
    const input = document.createElement('input');
    input.type = 'text';
    input.id = 'fs-input';
    input.placeholder = '输入关键词搜索全书（Enter 立即搜索）…';
    input.autocomplete = 'off';
    input.spellcheck = false;
    input.addEventListener('input', () => {
      clearTimeout(state.debounce);
      state.debounce = setTimeout(() => FullSearch.query(), 300);
      FullSearch.renderHistory();
    });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        clearTimeout(state.debounce);
        FullSearch.query();
        return;
      }
      // 检索页已打开时再按 Ctrl+F：只聚焦输入框，不重置当前查询
      if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')) {
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      // 其余按键（含 Escape）正常冒泡：输入态下阅读快捷键本就会被 Reader 拦截，
      // Escape 交由全局处理用于关闭检索页。
    });
    input.addEventListener('focus', () => FullSearch.renderHistory());
    const history = document.createElement('div');
    history.className = 'fs-history hidden';
    history.id = 'fs-history';
    searchRow.append(input, history);

    const status = document.createElement('div');
    status.className = 'fs-status hidden';
    status.id = 'fs-status';
    const summary = document.createElement('div');
    summary.className = 'fs-summary hidden';
    summary.id = 'fs-summary';
    const results = document.createElement('div');
    results.className = 'fs-results';
    results.id = 'fs-results';
    body.append(searchRow, status, summary, results);

    view.append(head, body);
    document.getElementById('reader-view').appendChild(view);
    return view;
  }

  function makeHitButton(hit, chapterIndex, textLen, q) {
    const btn = document.createElement('button');
    btn.className = 'fs-hit';
    btn.innerHTML = highlightSnippet(hit.snippet, q);
    btn.title = '点击跳转到该位置';
    btn.addEventListener('click', () => {
      FullSearch.close();
      Reader.goToSearchHit(chapterIndex, hit.offset, textLen);
    });
    return btn;
  }

  function makeGroup(ch, q) {
    const group = document.createElement('div');
    group.className = 'fs-group' + (state.expanded.has(ch.chapter_index) ? ' open' : '');
    group.dataset.chapter = String(ch.chapter_index);

    const head = document.createElement('button');
    head.className = 'fs-group-head';
    const chevron = document.createElement('span');
    chevron.className = 'fs-chevron';
    chevron.appendChild(Icons.icon('chevron', 14));
    const label = document.createElement('span');
    label.className = 'fs-group-title';
    label.textContent = ch.chapter_title || `第 ${ch.chapter_index + 1} 章`;
    const count = document.createElement('span');
    count.className = 'fs-group-count';
    count.textContent = ch.chapter_hits + ' 处';
    head.append(chevron, label, count);
    head.addEventListener('click', () => {
      const open = group.classList.toggle('open');
      if (open) state.expanded.add(ch.chapter_index);
      else state.expanded.delete(ch.chapter_index);
      FullSearch.syncToggleAll();
    });

    const body = document.createElement('div');
    body.className = 'fs-group-body';
    for (const hit of ch.hits) {
      body.appendChild(makeHitButton(hit, ch.chapter_index, ch.text_len, q));
    }
    FullSearch.appendMoreButton(body, ch);

    group.append(head, body);
    return group;
  }

  window.FullSearch = {
    open() {
      if (!App.state.bookId || App.state.view !== 'reader') return;
      if (this.isOpen()) {
        this.focusInput();
        return;
      }
      ensureBuilt();
      const view = $('search-view');
      view.classList.remove('hidden');
      state.q = '';
      state.data = null;
      state.expanded = new Set();
      state.loadingMore.clear();
      $('fs-input').value = '';
      $('fs-results').innerHTML = '';
      $('fs-summary').classList.add('hidden');
      $('fs-status').classList.add('hidden');
      this.syncToggleAll();
      this.renderHistory();
      setTimeout(() => $('fs-input').focus(), 60);
    },

    close() {
      const view = $('search-view');
      if (view) view.classList.add('hidden');
      clearTimeout(state.debounce);
      clearTimeout(state.retry);
    },

    isOpen() {
      const view = $('search-view');
      return !!view && !view.classList.contains('hidden');
    },

    focusInput() {
      const input = $('fs-input');
      if (input) {
        input.focus();
        input.select();
      }
    },

    renderHistory() {
      const box = $('fs-history');
      if (!box) return;
      const q = $('fs-input').value.trim();
      const items = loadHistory().filter((x) => !q || x.includes(q));
      box.innerHTML = '';
      if (!items.length) {
        box.classList.add('hidden');
        return;
      }
      for (const item of items) {
        const chip = document.createElement('button');
        chip.className = 'fs-history-chip';
        chip.textContent = item;
        chip.title = '搜索：' + item;
        chip.addEventListener('click', () => {
          $('fs-input').value = item;
          clearTimeout(state.debounce);
          FullSearch.query();
          box.classList.add('hidden');
        });
        box.appendChild(chip);
      }
      box.classList.remove('hidden');
    },

    toggleAll() {
      const allOpen = state.data && state.data.results.every(
        (ch) => state.expanded.has(ch.chapter_index)
      );
      state.expanded.clear();
      if (!allOpen && state.data) {
        state.data.results.forEach((ch) => state.expanded.add(ch.chapter_index));
      }
      this._rerender();
      this.syncToggleAll();
    },

    syncToggleAll() {
      const btn = $('fs-toggle-all');
      if (!btn || !state.data) {
        if (btn) btn.classList.add('hidden');
        return;
      }
      const allOpen = state.data.results.every((ch) => state.expanded.has(ch.chapter_index));
      btn.classList.remove('hidden');
      btn.textContent = allOpen ? '收起全部' : '展开全部';
    },

    async query(force) {
      const input = $('fs-input');
      const status = $('fs-status');
      const q = (input ? input.value : state.q || '').trim();
      if (!q) {
        $('fs-results').innerHTML = '';
        $('fs-summary').classList.add('hidden');
        status.classList.add('hidden');
        return;
      }
      if (q === state.q && state.data && !force) return;
      state.q = q;
      try {
        const resp = await Api.search( App.state.bookId, q,
          state.caseSensitive, state.wholeWord, PER_CHAPTER
        );
        if (!resp.ready) {
          status.textContent = '正在建立索引…';
          status.classList.remove('hidden');
          clearTimeout(state.retry);
          state.retry = setTimeout(() => this.query(force), 600);
          return;
        }
        status.classList.add('hidden');
        state.data = resp;
        state.expanded = new Set(
          resp.results.slice(0, DEFAULT_EXPANDED).map((ch) => ch.chapter_index)
        );
        saveHistory(q);
        this.renderHistory();
        this._rerender();
      } catch (e) {
        status.textContent = '搜索出错：' + (e.message || e);
        status.classList.remove('hidden');
      }
    },

    _rerender() {
      const box = $('fs-results');
      const summary = $('fs-summary');
      box.innerHTML = '';
      const data = state.data;
      if (!data) return;
      if (!data.results.length) {
        const empty = document.createElement('div');
        empty.className = 'fs-empty';
        empty.textContent = '没有匹配结果';
        box.appendChild(empty);
        summary.classList.add('hidden');
        this.syncToggleAll();
        return;
      }
      summary.textContent =
        `共 ${data.total_hits} 处命中 · ${data.hit_chapters}/${data.total_chapters} 章有结果` +
        `（每章最多显示 ${PER_CHAPTER} 条，可展开更多）`;
      summary.classList.remove('hidden');
      for (const ch of data.results) {
        box.appendChild(makeGroup(ch, state.q));
      }
      this.syncToggleAll();
    },

    appendMoreButton(body, ch) {
      const shown = body.querySelectorAll('.fs-hit').length;
      const remain = Math.max(0, (ch.chapter_hits || 0) - shown);
      if (!ch.more && remain <= 0) return;
      const btn = document.createElement('button');
      btn.className = 'fs-more';
      btn.textContent = ch.more ? `显示本章更多结果（还剩 ${Math.max(remain, 1)} 条）` : `显示剩余 ${remain} 条`;
      btn.addEventListener('click', async () => {
        const group = body.closest('.fs-group');
        if (state.loadingMore.has(ch.chapter_index)) return;
        state.loadingMore.add(ch.chapter_index);
        btn.disabled = true;
        btn.textContent = '加载中…';
        try {
          const last = ch.hits[ch.hits.length - 1];
          const page = await Api.searchMore( App.state.bookId, state.q, ch.chapter_index,
            last ? last.offset : -1, state.caseSensitive, state.wholeWord, PER_CHAPTER
          );
          ch.hits.push(...page.hits);
          ch.more = page.more;
          // 只追加本组 DOM，避免整页重排
          for (const hit of page.hits) {
            body.insertBefore(makeHitButton(hit, ch.chapter_index, ch.text_len, state.q), btn);
          }
          const remain2 = Math.max(0, (ch.chapter_hits || 0) - ch.hits.length);
          if (page.more && remain2 > 0) {
            btn.textContent = `显示本章更多结果（还剩 ${remain2} 条）`;
          } else {
            btn.remove();
          }
        } catch (e) {
          btn.textContent = '加载失败，点击重试';
          Toast.show('加载更多失败：' + (e.message || e), true);
        } finally {
          state.loadingMore.delete(ch.chapter_index);
          btn.disabled = false;
        }
      });
      body.appendChild(btn);
    },
  };

  document.addEventListener('DOMContentLoaded', () => {
    const btn = document.getElementById('search-page-btn');
    if (btn) btn.addEventListener('click', () => FullSearch.open());
  });
})();
