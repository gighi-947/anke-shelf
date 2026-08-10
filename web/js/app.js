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
    immersive: false,     // 沉浸式阅读（宿主窗口全屏）
    settings: { theme: 'dark', font_size: 18, line_height: 1.8, dual_page: false },
  };

  window.App = {
    state,

    async init() {
      if (this._initialized) return;
      this._initialized = true;
      try {
        Icons.inject();
        Api.logFrontend( 'init:start').catch(() => {});
        try {
          state.settings = await Api.getSettings();
        } catch (e) {
          Api.logFrontend( 'init:get_settings_failed: ' + (e.message || e)).catch(() => {});
        }
        Theme.applySettings(state.settings);
        Theme.applyReaderPrefs(state.settings.font_size, state.settings.line_height);
        this.setBarsPinned(!!state.settings.bars_pinned);
        this.updateThemeIcons();
        this.bindSystemTheme();
        this.bindGlobalEvents();
        this.bindReaderChrome();
        try {
          await Shelf.render();
          Api.logFrontend( 'init:shelf_rendered').catch(() => {});
        } catch (e) {
          Api.logFrontend( 'init:shelf_failed: ' + (e.message || e)).catch(() => {});
        }
      } catch (e) {
        Api.logFrontend( 'init:fatal: ' + (e.message || e)).catch(() => {});
      } finally {
        // 无论初始化是否完整，都通知 Python 可以显示窗口（超时保护在桥接层）。
        Api.onFrontendReady().catch(() => {});
      }
    },

    /** Theme button icons follow the current theme (sun for dark, moon otherwise). */
    updateThemeIcons() {
      const isDark = Theme.isDark(state.settings);
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

    /** 跟随系统模式：系统深浅色变化时自动重新应用主题。 */
    bindSystemTheme() {
      if (!window.matchMedia) return;
      const mq = window.matchMedia('(prefers-color-scheme: dark)');
      const onChange = () => {
        if (state.settings.theme_mode !== 'system') return;
        Theme.applySettings(state.settings);
        if (state.view === 'reader' && window.Reader) Reader.updateOverrides();
        this.updateThemeIcons();
        if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
        if (window.SettingsPage && SettingsPage.sync) SettingsPage.sync();
      };
      if (mq.addEventListener) mq.addEventListener('change', onChange);
      else if (mq.addListener) mq.addListener(onChange);
      this._systemThemeMq = mq;
      this._systemThemeOnChange = onChange;
    },

    setBarsVisible(on) {
      const pinned = document.getElementById('reader-view').classList.contains('bars-pinned');
      const topBar = document.getElementById('top-bar');
      const statusBar = document.getElementById('status-bar');
      if (topBar) topBar.classList.toggle('bar-visible', !!(on || pinned));
      if (statusBar) statusBar.classList.toggle('bar-visible', !!(on || pinned));
      // 顶栏收起时同步收起其二级菜单卡片，避免卡片悬空残留
      if (!on && !pinned && window.ViewMenu) ViewMenu.close();
    },

    /** 翻页/换章操作后立即收起顶/底栏（固定显示时除外）。 */
    hideBarsForAction() {
      const pinned = document.getElementById('reader-view').classList.contains('bars-pinned');
      if (pinned) return;
      this.setBarsVisible(false);
    },

    setBarsPinned(on) {
      App.state.settings.bars_pinned = !!on;
      document.getElementById('reader-view').classList.toggle('bars-pinned', !!on);
      const btn = document.getElementById('bars-pin-btn');
      if (btn) btn.classList.toggle('active', !!on);
      if (on) this.setBarsVisible(true);
    },

    toggleBarsPinned() {
      const on = !App.state.settings.bars_pinned;
      App.state.settings.bars_pinned = on;
      this.setBarsPinned(on);
      Api.saveSettings( { bars_pinned: on });
    },

    /** 沉浸式阅读：切换宿主窗口全屏；进入时收起顶/底栏。 */
    async toggleImmersive() {
      const r = await this._toggleFullscreenBridge();
      if (!r || r.ok === false) {
        Toast.show((r && r.error) || '全屏切换失败', true);
        return;
      }
      state.immersive = !state.immersive;
      this._applyImmersiveState();
      if (state.immersive) {
        Toast.show('已进入沉浸式阅读，按 Esc 或 F11 退出');
      }
    },

    /** 退出沉浸式阅读（返回书架 / Esc 时调用）。 */
    async exitImmersive() {
      if (!state.immersive) return;
      const r = await this._toggleFullscreenBridge();
      if (!r || r.ok === false) {
        Toast.show((r && r.error) || '退出全屏失败', true);
        return;
      }
      state.immersive = false;
      this._applyImmersiveState();
    },

    async _toggleFullscreenBridge() {
      let r = null;
      try {
        r = await Api.toggleFullscreen();
      } catch (e) {
        r = { ok: false, error: e.message || String(e) };
      }
      return r;
    },

    _applyImmersiveState() {
      document.getElementById('reader-view').classList.toggle('immersive', state.immersive);
      this.setBarsVisible(!state.immersive);
      const btn = document.getElementById('fullscreen-btn');
      if (btn) {
        btn.title = state.immersive ? '退出沉浸式阅读' : '沉浸式阅读（全屏）';
        btn.classList.toggle('active', state.immersive);
      }
    },

    showShelf() {
      // 返回书架时自动退出沉浸式阅读（全屏只属于阅读界面）
      this.exitImmersive();
      state.view = 'shelf';
      document.getElementById('reader-view').classList.add('hidden');
      document.getElementById('shelf-view').classList.remove('hidden');
      this.setBarsVisible(false);
      Sidebar.close();
      ViewMenu.close();
      if (window.FullSearch) FullSearch.close();
      if (window.SettingsPage) SettingsPage.close();
      document.title = '安科书架';
    },

    async showReader(bookId) {
      try {
        const data = await Api.openBook( bookId);
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
          if (window.FullSearch) FullSearch.close();
          if (window.SettingsPage) SettingsPage.open();
        });
      }

      const applyThemeNext = () => {
        // 跟随系统时先解析当前实际主题，再从它继续循环；循环后固定为该主题
        const current = Theme.resolveTheme(state.settings);
        state.settings.theme = Theme.nextTheme(current);
        state.settings.theme_mode = state.settings.theme;
        Theme.applySettings(state.settings);
        if (state.view === 'reader' && window.Reader) Reader.updateOverrides();
        this.updateThemeIcons();
        Api.saveSettings( {
          theme: state.settings.theme,
          theme_mode: state.settings.theme_mode,
        });
        if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
        if (window.SettingsPage && SettingsPage.sync) SettingsPage.sync();
      };
      document.getElementById('theme-btn').addEventListener('click', applyThemeNext);
      document.getElementById('theme-btn2').addEventListener('click', applyThemeNext);

      document.getElementById('sidebar-toggle').addEventListener('click', () => Sidebar.toggle());
      document.getElementById('sidebar-toggle2').addEventListener('click', () => Sidebar.close());
      document.getElementById('sidebar-search-btn').addEventListener('click', () => {
        if (window.FullSearch) FullSearch.open();
      });
      document.getElementById('sidebar-pin-btn').addEventListener('click', () => {
        Sidebar.togglePin();
      });
      document.getElementById('sidebar-book-card').addEventListener('click', () => {
        Sidebar.close();
      });

      document.getElementById('view-menu-btn').addEventListener('click', () => ViewMenu.toggle());
      document.getElementById('bars-pin-btn').addEventListener('click', () => this.toggleBarsPinned());
      document.getElementById('fullscreen-btn').addEventListener('click', () => this.toggleImmersive());
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

    /** 顶/底栏自动隐藏：
     *  - 鼠标进入上下边缘带（54px）或悬浮在顶/底栏上 → 显示；
     *  - 鼠标回到正文中部 / 离开阅读视图 → 延迟隐藏（避免边缘抖动）；
     *  - 固定显示（bars-pinned）或视图菜单打开时始终显示；
     *  - 滚动模式下右侧滚动条区域不唤出顶/底栏，避免遮挡。
     * 分页模式 iframe 铺满舞台，父页面收不到正文内的 mousemove，
     * 因此同时把监听挂到 iframe 文档上（同源，坐标换算回父页面）。 */
    bindReaderChrome() {
      const rv = document.getElementById('reader-view');
      const topBar = document.getElementById('top-bar');
      const statusBar = document.getElementById('status-bar');
      const frame = document.getElementById('chapter-frame');
      if (!rv || !topBar || !statusBar || !frame) return;

      const EDGE = 54;
      let hideTimer = null;
      let lastDoc = null;

      const menuOpen = () => {
        const vm = document.getElementById('view-menu');
        return !!vm && !vm.classList.contains('hidden');
      };
      const pinned = () => document.getElementById('reader-view').classList.contains('bars-pinned');
      const overBars = (t) => topBar.contains(t) || statusBar.contains(t);
      const inEdgeZone = (y, h) => y < EDGE || y > h - EDGE;

      const cancelHide = () => {
        if (hideTimer) {
          clearTimeout(hideTimer);
          hideTimer = null;
        }
      };

      const scheduleHide = () => {
        if (menuOpen() || pinned()) return;
        cancelHide();
        hideTimer = setTimeout(() => App.setBarsVisible(false), 600);
      };

      /** 滚动模式：边缘触发区横向限为书籍实际显示区域，避免侧边留白/滑动条误触发。 */
      const inBookZone = (clientX) => {
        if (Paged.isActive()) return true;
        const fr = frame.getBoundingClientRect();
        return clientX >= fr.left - 1 && clientX <= fr.right + 1;
      };

      /** 滚动模式：滚轮立即收起顶/底栏（不等待 600ms 延迟）。 */
      const onWheelHide = () => {
        if (App.state.view !== 'reader') return;
        if (Paged.isActive()) return;
        if (menuOpen() || pinned()) return;
        cancelHide();
        App.setBarsVisible(false);
      };

      const refreshAt = (clientX, clientY, target) => {
        if (App.state.view !== 'reader') return;
        const rect = rv.getBoundingClientRect();
        const x = clientX - rect.left;
        const y = clientY - rect.top;
        const h = rect.height;
        // 滚动模式：鼠标停在右侧滚动条区域时不唤出顶/底栏
        if (!pinned() && !overBars(target) && !Paged.isActive() && x > rect.width - 28) {
          scheduleHide();
          return;
        }
        if (inEdgeZone(y, h) || overBars(target) || menuOpen() || pinned()) {
          // 悬停在书宽之外（侧边留白）的边缘不唤出顶/底栏
          if (inEdgeZone(y, h) && !overBars(target) && !menuOpen() && !pinned() &&
              !inBookZone(clientX)) {
            scheduleHide();
            return;
          }
          cancelHide();
          App.setBarsVisible(true);
        } else {
          scheduleHide();
        }
      };

      const onDocMove = (e) => {
        const fr = frame.getBoundingClientRect();
        refreshAt(fr.left + e.clientX, fr.top + e.clientY, e.target);
      };
      const onDocLeave = () => scheduleHide();

      const bindFrameDoc = () => {
        let doc = null;
        try {
          doc = frame.contentDocument;
        } catch (e) { /* 跨域/未加载 */ }
        if (!doc || doc === lastDoc) return;
        if (lastDoc) {
          lastDoc.removeEventListener('mousemove', onDocMove);
          lastDoc.removeEventListener('mouseleave', onDocLeave);
          lastDoc.removeEventListener('wheel', onWheelHide);
        }
        lastDoc = doc;
        doc.addEventListener('mousemove', onDocMove);
        doc.addEventListener('mouseleave', onDocLeave);
        doc.addEventListener('wheel', onWheelHide, { passive: true });
      };

      rv.addEventListener('mousemove', (e) => refreshAt(e.clientX, e.clientY, e.target));
      rv.addEventListener('mouseleave', scheduleHide);
      rv.addEventListener('wheel', onWheelHide, { passive: true });
      frame.addEventListener('load', bindFrameDoc);
      bindFrameDoc();
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
