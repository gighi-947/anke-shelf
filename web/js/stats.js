/**
 * Reading statistics: session heartbeat, page counts and a detailed modal.
 */
(function () {
  'use strict';

  function statCard(value, label) {
    const card = document.createElement('div');
    card.className = 'stat-card';
    const v = document.createElement('div');
    v.className = 'stat-value';
    v.textContent = value;
    const l = document.createElement('div');
    l.className = 'stat-label';
    l.textContent = label;
    card.append(v, l);
    return card;
  }

  function daysChart(days) {
    const wrap = document.createElement('div');
    wrap.className = 'stats-days';
    const rows = [];
    const now = new Date();
    for (let i = 6; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
      const key = d.getFullYear() + '-' +
        String(d.getMonth() + 1).padStart(2, '0') + '-' +
        String(d.getDate()).padStart(2, '0');
      const secs = (days && days[key] && days[key].seconds) || 0;
      rows.push({ key, secs });
    }
    const max = Math.max(1, ...rows.map((r) => r.secs));
    for (const r of rows) {
      const col = document.createElement('div');
      col.className = 'stats-day-col';
      const barWrap = document.createElement('div');
      barWrap.className = 'stats-day-bar-wrap';
      const bar = document.createElement('div');
      bar.className = 'stats-day-bar';
      bar.style.height = Math.max(2, Math.round((r.secs / max) * 100)) + '%';
      bar.title = r.key + '：' + Util.fmtDuration(r.secs);
      barWrap.appendChild(bar);
      const label = document.createElement('div');
      label.className = 'stats-day-label';
      label.textContent = String(r.key).slice(5);
      col.append(barWrap, label);
      wrap.appendChild(col);
    }
    return wrap;
  }

  function section(title) {
    const el = document.createElement('div');
    el.className = 'stats-section-title';
    el.textContent = title;
    return el;
  }

  function statGrid(stats) {
    const grid = document.createElement('div');
    grid.className = 'stats-grid';
    grid.appendChild(statCard(Util.fmtDuration(stats.total_seconds), '累计阅读'));
    grid.appendChild(statCard(Util.fmtDuration(stats.today_seconds), '今日阅读'));
    grid.appendChild(statCard(Util.fmtDuration(stats.week_seconds), '最近 7 天'));
    grid.appendChild(statCard(String(stats.sessions), '阅读会话'));
    grid.appendChild(statCard(Util.fmtDuration(stats.avg_session_seconds), '平均每次'));
    grid.appendChild(statCard(String(stats.pages_flipped), '翻页次数'));
    grid.appendChild(statCard(String(stats.streak_days) + ' 天', '连续阅读'));
    grid.appendChild(statCard(Util.fmtDate(stats.last_read_at), '最近阅读'));
    return grid;
  }

  function closeModal(root) {
    root.innerHTML = '';
  }

  function statsBookList(books, selectedId, onPick) {
    const wrap = document.createElement('div');
    wrap.className = 'stats-book-list';
    wrap.appendChild(section('最近阅读书目'));
    if (!books.length) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = '暂无阅读记录';
      wrap.appendChild(p);
      return wrap;
    }
    const grid = document.createElement('div');
    grid.className = 'stats-book-cards';
    for (const b of books) {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'stats-book-card' + (b.id === selectedId ? ' active' : '');
      card.dataset.id = b.id;
      const total = document.createElement('div');
      total.className = 'stats-book-total';
      total.textContent = Util.fmtDuration(b.stats.total_seconds);
      const title = document.createElement('div');
      title.className = 'stats-book-title';
      title.textContent = b.title || '未命名';
      const meta = document.createElement('div');
      meta.className = 'stats-book-meta';
      const parts = [];
      if (b.author) parts.push(b.author);
      if (b.stats.sessions) parts.push(b.stats.sessions + ' 次会话');
      if (b.stats.last_read_at) parts.push('最近 ' + Util.fmtDate(b.stats.last_read_at));
      meta.textContent = parts.join(' · ');
      card.append(total, title, meta);
      card.addEventListener('click', () => onPick(b.id));
      grid.appendChild(card);
    }
    wrap.appendChild(grid);
    return wrap;
  }

  window.Stats = {
    _secs: 0,
    _pages: 0,
    _last: 0,
    _timer: null,
    _listenersBound: false,

    start() {
      this._last = Date.now();
      if (this._timer) return;
      this._timer = setInterval(() => this._tick(), 5000);
      if (this._listenersBound) return;
      this._listenersBound = true;
      document.addEventListener('visibilitychange', () => {
        if (document.hidden) this.flush();
      });
      window.addEventListener('beforeunload', () => this.flush());
    },

    _tick() {
      if (document.hidden) return;
      const now = Date.now();
      const dt = (now - this._last) / 1000;
      this._last = now;
      this._secs += dt;
      if (this._secs >= 60) this.flush();
    },

    flush() {
      if (!App.state.bookId || this._secs < 1) return;
      Api.recordReading( App.state.bookId, Math.round(this._secs), this._pages);
      this._secs = 0;
      this._pages = 0;
    },

    addPage() {
      this._pages++;
    },

    async showDetails() {
      const root = document.getElementById('modal-root');
      root.innerHTML = '';
      const overlay = document.createElement('div');
      overlay.className = 'modal-overlay';
      const box = document.createElement('div');
      box.className = 'modal stats-panel';
      const title = document.createElement('div');
      title.className = 'modal-title';
      title.textContent = '阅读统计';
      box.appendChild(title);

      let books = [];
      let global = null;
      try {
        const g = await Api.getStats();
        books = g.books || [];
        global = g.global || null;
      } catch (e) { /* keep empty */ }

      const scope = document.createElement('div');
      scope.className = 'stats-scope';
      scope.textContent = '全部书籍';

      const select = document.createElement('select');
      select.className = 'stats-book-select';
      select.id = 'stats-book-select';
      const allOpt = document.createElement('option');
      allOpt.value = '';
      allOpt.textContent = '全部书籍';
      select.appendChild(allOpt);
      for (const b of books) {
        const o = document.createElement('option');
        o.value = b.id;
        o.textContent = b.title || '未命名';
        select.appendChild(o);
      }

      const toolbar = document.createElement('div');
      toolbar.className = 'stats-toolbar';
      toolbar.append(scope, select);
      box.appendChild(toolbar);

      const detail = document.createElement('div');
      detail.className = 'stats-detail';
      box.appendChild(detail);

      const listWrap = document.createElement('div');
      listWrap.id = 'stats-book-list-wrap';
      box.appendChild(listWrap);

      function render() {
        const id = select.value;
        let st = global;
        let label = '全部书籍';
        if (id) {
          const b = books.find((x) => x.id === id);
          if (b) {
            st = b.stats;
            label = b.title || '未命名';
          }
        }
        scope.textContent = label;
        detail.innerHTML = '';
        if (st) {
          detail.appendChild(statGrid(st));
          detail.appendChild(section('最近 7 天'));
          detail.appendChild(daysChart(st.days));
        }
        listWrap.querySelectorAll('.stats-book-card').forEach((c) => {
          c.classList.toggle('active', c.dataset.id === id);
        });
      }

      select.addEventListener('change', render);
      listWrap.appendChild(statsBookList(books, select.value, (id) => {
        select.value = id;
        render();
      }));
      render();

      if (!books.length && !global) {
        const empty = document.createElement('p');
        empty.className = 'muted';
        empty.textContent = '暂无统计数据';
        detail.appendChild(empty);
      }

      const actions = document.createElement('div');
      actions.className = 'stats-close-row';
      const closeBtn = document.createElement('button');
      closeBtn.className = 'btn';
      closeBtn.textContent = '关闭';
      closeBtn.addEventListener('click', () => closeModal(root));
      actions.appendChild(closeBtn);
      box.appendChild(actions);
      overlay.appendChild(box);
      overlay.addEventListener('click', (e) => {
        if (e.target === overlay) closeModal(root);
      });
      root.appendChild(overlay);
    },

    async renderSidebar() {
      const panel = document.getElementById('tab-stats');
      if (!panel) return;
      let book = null;
      let global = null;
      try {
        if (App.state.bookId) {
          const r = await Api.getStats( App.state.bookId);
          book = r.book || null;
        }
        const g = await Api.getStats();
        global = g.global || null;
      } catch (e) { /* keep empty */ }
      panel.innerHTML = '';
      const box = document.createElement('div');
      box.className = 'side-stats';
      if (book) {
        const total = document.createElement('div');
        total.className = 'side-stats-total';
        total.textContent = Util.fmtDuration(book.total_seconds);
        const sub = document.createElement('div');
        sub.className = 'side-stats-sub';
        sub.textContent = '本书 · 今日 ' + Util.fmtDuration(book.today_seconds) +
          ' · 最近 7 天 ' + Util.fmtDuration(book.week_seconds);
        box.append(total, sub);
      }
      if (global) {
        const g = document.createElement('div');
        g.className = 'side-stats-global';
        g.textContent = '全部书籍累计 ' + Util.fmtDuration(global.total_seconds);
        box.appendChild(g);
      }
      if (!book && !global) {
        const p = document.createElement('p');
        p.className = 'muted';
        p.textContent = '暂无统计数据';
        box.appendChild(p);
      }
      const btn = document.createElement('button');
      btn.className = 'vm-btn side-stats-btn';
      btn.textContent = '查看详细统计';
      btn.addEventListener('click', () => {
        Sidebar.close();
        Stats.showDetails();
      });
      box.appendChild(btn);
      panel.appendChild(box);
    },
  };
})();
