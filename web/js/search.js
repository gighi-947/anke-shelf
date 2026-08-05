/**
 * 全文搜索面板：输入防抖 → 桥接查询 → 结果分组渲染 → 点击跳转。
 * 索引未就绪时轮询重试（is_index_ready）。
 */
(function () {
  'use strict';

  let debounceTimer = null;
  let retryTimer = null;

  window.Search = {
    reset() {
      document.getElementById('search-input').value = '';
      document.getElementById('search-results').innerHTML = '';
      document.getElementById('search-status').classList.add('hidden');
      clearTimeout(debounceTimer);
      clearTimeout(retryTimer);
    },

    async query() {
      const input = document.getElementById('search-input');
      const status = document.getElementById('search-status');
      const resultsBox = document.getElementById('search-results');
      const q = input.value.trim();
      if (!q) {
        resultsBox.innerHTML = '';
        status.classList.add('hidden');
        return;
      }
      try {
        const resp = await Bridge.call('search', App.state.bookId, q);
        if (!resp.ready) {
          // 索引构建中：轮询重试
          status.textContent = '正在建立索引…';
          status.classList.remove('hidden');
          clearTimeout(retryTimer);
          retryTimer = setTimeout(() => this.query(), 500);
          return;
        }
        status.classList.add('hidden');
        this._render(resp.results, q);
      } catch (e) {
        status.textContent = '搜索出错：' + (e.message || e);
        status.classList.remove('hidden');
      }
    },

    _render(results, q) {
      const box = document.getElementById('search-results');
      box.innerHTML = '';
      if (!results.length) {
        box.innerHTML = '<p class="muted" style="padding:12px 4px">无匹配结果</p>';
        return;
      }
      const esc = (s) => s.replace(/[&<>"']/g, (c) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
      }[c]));
      const qesc = esc(q);
      const reSrc = qesc.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

      for (const ch of results) {
        const head = document.createElement('div');
        head.className = 'search-chapter-title';
        head.textContent = ch.chapter_title || `第 ${ch.chapter_index + 1} 章`;
        box.appendChild(head);

        for (const hit of ch.hits) {
          const btn = document.createElement('button');
          btn.className = 'search-hit';
          // 命中词高亮（正则转义）
          let snippet = esc(hit.snippet);
          try {
            const re = new RegExp(reSrc, 'gi');
            snippet = snippet.replace(re, '<mark>$&</mark>');
          } catch (e) { /* 保留原文 */ }
          btn.innerHTML = snippet;
          btn.title = '点击跳转';
          btn.addEventListener('click', () => {
            Reader.goToSearchHit(ch.chapter_index, hit.offset, ch.text_len);
          });
          box.appendChild(btn);
        }
      }
    },
  };

  document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('search-input').addEventListener('input', () => {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => Search.query(), 300);
    });
  });
})();
