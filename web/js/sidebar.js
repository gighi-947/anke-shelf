/**
 * Sidebar: open/close, pin, tab switching and the book info card.
 */
(function () {
  'use strict';

  window.Sidebar = {
    toggle() {
      const sb = document.getElementById('sidebar');
      if (sb.classList.contains('open')) {
        this.close();
      } else {
        sb.classList.remove('hidden');
        sb.classList.add('open');
      }
    },

    close() {
      const sb = document.getElementById('sidebar');
      sb.classList.remove('open');
      sb.classList.remove('pinned');
      sb.classList.add('hidden');
      document.getElementById('reader-view').classList.remove('sidebar-pinned');
      const pinBtn = document.getElementById('sidebar-pin-btn');
      if (pinBtn) pinBtn.classList.remove('active');
    },

    isOpen() {
      const sb = document.getElementById('sidebar');
      return !sb.classList.contains('hidden') && sb.classList.contains('open');
    },

    togglePin() {
      const sb = document.getElementById('sidebar');
      const rv = document.getElementById('reader-view');
      const pinned = sb.classList.toggle('pinned');
      rv.classList.toggle('sidebar-pinned', pinned);
      sb.classList.remove('hidden');
      sb.classList.add('open');
      const pinBtn = document.getElementById('sidebar-pin-btn');
      if (pinBtn) {
        pinBtn.classList.toggle('active', pinned);
        pinBtn.title = pinned ? 'Unpin Sidebar' : 'Pin Sidebar';
      }
    },

    switchTab(name) {
      document.querySelectorAll('.sidebar-tabs .tab-btn').forEach((b) => {
        b.classList.toggle('active', b.dataset.tab === name);
      });
      document.querySelectorAll('.sidebar-body .tab-panel').forEach((p) => {
        p.classList.toggle('active', p.id === 'tab-' + name);
      });
      if (name === 'stats' && window.Stats && Stats.renderSidebar) {
        Stats.renderSidebar();
      }
    },

    renderBookCard(data) {
      if (!data) return;
      const cover = document.getElementById('sb-cover');
      if (cover) {
        cover.innerHTML = '';
        const fb = document.createElement('div');
        fb.className = 'sb-cover-fallback';
        fb.textContent = (data.title || 'Book').slice(0, 1);
        cover.appendChild(fb);
        if (data.cover_url) {
          const img = new Image();
          img.alt = data.title || '';
          img.addEventListener('error', () => img.remove(), { once: true });
          img.src = Theme.coverUrl(data.cover_url);
          cover.appendChild(img);
        }
      }
      const title = document.getElementById('sb-title');
      if (title) title.textContent = data.title || '';
      const sub = document.getElementById('sb-sub');
      if (sub) {
        const parts = [];
        if (data.author) parts.push(data.author);
        if (data.nga_tid) parts.push('tid ' + data.nga_tid);
        if (data.chapters && data.chapters.length && data.progress) {
          const pct = Math.round(((data.progress.chapter_index || 0) + 1) / data.chapters.length * 100);
          parts.push(pct + '%');
        }
        sub.textContent = parts.join(' · ') || '';
      }
    },

  };

  document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.sidebar-tabs .tab-btn').forEach((btn) => {
      btn.addEventListener('click', () => Sidebar.switchTab(btn.dataset.tab));
    });
  });
})();
