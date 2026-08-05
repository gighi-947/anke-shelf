/**
 * Reading statistics: session heartbeat, page counts and a detailed modal.
 */
(function () {
  'use strict';

  function fmtDuration(secs) {
    secs = Math.max(0, Math.round(secs || 0));
    if (secs < 60) return secs + ' 秒';
    const mins = Math.floor(secs / 60);
    if (mins < 60) return mins + ' 分钟';
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m ? h + ' 小时 ' + m + ' 分' : h + ' 小时';
  }

  function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
  }

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
      bar.title = r.key + '：' + fmtDuration(r.secs);
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
    grid.appendChild(statCard(fmtDuration(stats.total_seconds), '累计阅读'));
    grid.appendChild(statCard(fmtDuration(stats.today_seconds), '今日阅读'));
    grid.appendChild(statCard(fmtDuration(stats.week_seconds), '最近 7 天'));
    grid.appendChild(statCard(String(stats.sessions), '阅读会话'));
    grid.appendChild(statCard(fmtDuration(stats.avg_session_seconds), '平均每次'));
    grid.appendChild(statCard(String(stats.pages_flipped), '翻页次数'));
    grid.appendChild(statCard(String(stats.streak_days) + ' 天', '连续阅读'));
    grid.appendChild(statCard(fmtDate(stats.last_read_at), '最近阅读'));
    return grid;
  }

  function closeModal(root) {
    root.innerHTML = '';
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
      Bridge.call('record_reading', App.state.bookId, Math.round(this._secs), this._pages);
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

      let book = null;
      let global = null;
      try {
        if (App.state.bookId) {
          const r = await Bridge.call('get_stats', App.state.bookId);
          book = r.book;
        }
        const g = await Bridge.call('get_stats');
        global = g.global;
      } catch (e) { /* keep empty */ }

      if (book) {
        box.appendChild(section('本书'));
        box.appendChild(statGrid(book));
        box.appendChild(section('最近 7 天'));
        box.appendChild(daysChart(book.days));
      }
      if (global) {
        box.appendChild(section('全部书籍'));
        box.appendChild(statGrid(global));
      }
      if (!book && !global) {
        const empty = document.createElement('p');
        empty.className = 'muted';
        empty.textContent = '暂无统计数据';
        box.appendChild(empty);
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
  };
})();
