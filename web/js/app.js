/**
 * App state, view switching and global event wiring.
 */
(function () {
  'use strict';

  const state = {
    view: 'shelf',        // 'shelf' | 'reader'
    bookId: null,
    book: null,           // open_book payload
    chapterIndex: -1,
    textCtx: null,        // TextPos context for the current chapter
    settings: { theme: 'dark', font_size: 18, line_height: 1.8, dual_page: false },
  };

  window.App = {
    state,

    async init() {
      if (this._initialized) return;
      this._initialized = true;
      try {
        Icons.inject();
        Bridge.call('log_frontend', 'init:start').catch(() => {});
        try {
          state.settings = await Bridge.call('get_settings');
        } catch (e) {
          Bridge.call('log_frontend', 'init:get_settings_failed: ' + (e.message || e)).catch(() => {});
        }
        Theme.applyTheme(state.settings.theme);
        Theme.applyReaderPrefs(state.settings.font_size, state.settings.line_height);
        this.setBarsPinned(!!state.settings.bars_pinned);
        this.updateThemeIcons();
        this.bindGlobalEvents();
        this.bindReaderChrome();
        try {
          await Shelf.render();
          Bridge.call('log_frontend', 'init:shelf_rendered').catch(() => {});
        } catch (e) {
          Bridge.call('log_frontend', 'init:shelf_failed: ' + (e.message || e)).catch(() => {});
        }
      } catch (e) {
        Bridge.call('log_frontend', 'init:fatal: ' + (e.message || e)).catch(() => {});
      } finally {
        // 无论初始化是否完整，都通知 Python 可以显示窗口（超时保护在桥接层）。
        Bridge.call('on_frontend_ready').catch(() => {});
      }
    },

    /** Theme button icons follow the current theme (sun for dark, moon otherwise). */
    updateThemeIcons() {
      const isDark = state.settings.theme === 'dark';
      const icon = isDark ? 'sun' : 'moon';
      const btn1 = document.getElementById('theme-btn');
      const btn2 = document.getElementById('theme-btn2');
      if (btn1) {
        btn1.innerHTML = '';
        btn1.appendChild(Icons.icon(icon, 18));
      }
      if (btn2) {
        btn2.innerHTML = '';
        btn2.appendChild(Icons.icon(icon, 18));
      }
    },

    setBarsVisible(on) {
      const pinned = document.getElementById('reader-view').classList.contains('bars-pinned');
      const topBar = document.getElementById('top-bar');
      const statusBar = document.getElementById('status-bar');
      if (topBar) topBar.classList.toggle('bar-visible', !!(on || pinned));
      if (statusBar) statusBar.classList.toggle('bar-visible', !!(on || pinned));
    },

    setBarsPinned(on) {
      document.getElementById('reader-view').classList.toggle('bars-pinned', !!on);
      const btn = document.getElementById('bars-pin-btn');
      if (btn) btn.classList.toggle('active', !!on);
      if (on) this.setBarsVisible(true);
    },

    toggleBarsPinned() {
      const on = !App.state.settings.bars_pinned;
      App.state.settings.bars_pinned = on;
      this.setBarsPinned(on);
      Bridge.call('save_settings', { bars_pinned: on });
    },

    showShelf() {
      state.view = 'shelf';
      document.getElementById('reader-view').classList.add('hidden');
      document.getElementById('shelf-view').classList.remove('hidden');
      this.setBarsVisible(false);
      Sidebar.close();
      ViewMenu.close();
      if (window.SettingsPage) SettingsPage.close();
      document.title = '安科书架';
    },

    async showReader(bookId) {
      try {
        const data = await Bridge.call('open_book', bookId);
        if (data && data.error) {
          Toast.show(data.error, true);
          return;
        }
        state.view = 'reader';
        state.bookId = bookId;
        state.book = data;
        state.chapterIndex = -1;
        document.getElementById('shelf-view').classList.add('hidden');
        document.getElementById('reader-view').classList.remove('hidden');
        document.getElementById('reader-book-title').textContent = data.title || '';
        document.title = data.title || 'Reading';
        this.setBarsVisible(true);
        if (window.Sidebar) Sidebar.renderBookCard(data);
        Toc.render(data.toc);
        Sidebar.switchTab('toc');
        if (window.Annotations) await Annotations.init();
        if (window.Stats) Stats.start();
        if (window.Assist) Assist.setBrightness(state.settings.brightness || 0);
        Search.reset();
        const p = data.progress || { chapter_index: 0, text_offset: 0 };
        await Reader.loadChapter(p.chapter_index, p.text_offset || 0);
      } catch (e) {
        Toast.show('Failed to open book: ' + (e.message || e), true);
      }
    },

    bindGlobalEvents() {
      document.getElementById('back-btn').addEventListener('click', () => {
        Reader.saveProgress();
        this.showShelf();
        Shelf.render();
      });
      const settingsBtn = document.getElementById('settings-btn');
      if (settingsBtn) {
        settingsBtn.addEventListener('click', () => {
          if (window.SettingsPage) SettingsPage.open();
        });
      }

      const applyThemeNext = () => {
        state.settings.theme = Theme.nextTheme(state.settings.theme);
        Theme.applyTheme(state.settings.theme);
        if (state.view === 'reader' && window.Reader) Reader.updateOverrides();
        this.updateThemeIcons();
        Bridge.call('save_settings', { theme: state.settings.theme });
        if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
        if (window.SettingsPage && SettingsPage.sync) SettingsPage.sync();
      };
      document.getElementById('theme-btn').addEventListener('click', applyThemeNext);
      document.getElementById('theme-btn2').addEventListener('click', applyThemeNext);

      document.getElementById('sidebar-toggle').addEventListener('click', () => Sidebar.toggle());
      document.getElementById('sidebar-toggle2').addEventListener('click', () => Sidebar.close());
      document.getElementById('sidebar-search-btn').addEventListener('click', () => {
        Sidebar.openSearch();
      });
      document.getElementById('sidebar-pin-btn').addEventListener('click', () => {
        Sidebar.togglePin();
      });
      document.getElementById('sidebar-book-card').addEventListener('click', () => {
        Sidebar.close();
      });

      document.getElementById('view-menu-btn').addEventListener('click', () => ViewMenu.toggle());
      document.getElementById('bars-pin-btn').addEventListener('click', () => this.toggleBarsPinned());
      document.getElementById('bookmark-btn').addEventListener('click', () => {
        Reader.toggleBookmarkAtCurrent();
      });

      window.addEventListener('beforeunload', () => Reader.saveProgress());
      window.addEventListener('keydown', Reader.onKeyDown);
      window.addEventListener('keydown', (e) => {
        if (state.view !== 'shelf') return;
        if ((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')) {
          e.preventDefault();
          const input = document.getElementById('shelf-search');
          if (input) {
            input.focus();
            input.select();
          }
        }
      });

      document.addEventListener('click', (e) => {
        if (state.view !== 'reader') return;
        const vm = document.getElementById('view-menu');
        if (vm && !vm.classList.contains('hidden') &&
            !e.target.closest('#view-menu-btn') &&
            !e.target.closest('.view-menu')) {
          ViewMenu.close();
        }
      });
    },

    /** Readest-style chrome: reveal top/footer bars when the mouse nears the edges. */
    bindReaderChrome() {
      const rv = document.getElementById('reader-view');
      const topBar = document.getElementById('top-bar');
      const statusBar = document.getElementById('status-bar');
      if (!rv || !topBar || !statusBar) return;
      let hideTimer = null;
      let scrollDrag = false;
      const scrollEl = document.getElementById('chapter-scroll');
      if (scrollEl) {
        scrollEl.addEventListener('pointerdown', (e) => {
          const r = rv.getBoundingClientRect();
          if (e.clientX > r.right - 28) scrollDrag = true;
        });
      }
      window.addEventListener('pointerup', () => { scrollDrag = false; });

      const menuOpen = () => {
        const vm = document.getElementById('view-menu');
        return !!vm && !vm.classList.contains('hidden');
      };
      const pinned = () => document.getElementById('reader-view').classList.contains('bars-pinned');

      const refresh = (e) => {
        if (App.state.view !== 'reader') return;
        if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
        const rect = rv.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        const h = rect.height;
        // 鼠标在右侧滚动条区域：不唤出顶/底栏，避免遮挡滚动条
        if (x > rect.width - 28) {
          if (!pinned()) {
            topBar.classList.remove('bar-visible');
            statusBar.classList.remove('bar-visible');
          }
          return;
        }
        if (scrollDrag) return;
        const keep = menuOpen() || pinned();
        topBar.classList.toggle('bar-visible', y < 54 || keep);
        statusBar.classList.toggle('bar-visible', y > h - 54 || keep);
      };

      rv.addEventListener('mousemove', refresh);
      rv.addEventListener('mouseleave', () => {
        if (App.state.view !== 'reader') return;
        hideTimer = setTimeout(() => {
          if (!menuOpen() && !pinned()) App.setBarsVisible(false);
        }, 250);
      });
    },
  };

  /** 前后端分离后无需等待 pywebview 注入，DOM 就绪即可初始化。 */
  document.addEventListener('DOMContentLoaded', () => { App.init(); });

  window.Toast = {
    show(msg, isError) {
      const el = document.createElement('div');
      el.className = 'toast' + (isError ? ' toast-error' : '');
      el.textContent = msg;
      document.getElementById('toast-container').appendChild(el);
      setTimeout(() => {
        el.style.opacity = '0';
        el.style.transition = 'opacity 0.3s';
        setTimeout(() => el.remove(), 300);
      }, 3200);
    },
  };
})();
