/**
 * 独立设置页：主题、亮度、辅助功能、快捷键、NGA 导出说明、统计、数据。
 * 阅读界面只保留排版快捷菜单（view-menu.js）。
 */
(function () {
  'use strict';

  const DEFAULT_SHORTCUTS = {
    next_page: 'ArrowRight',
    prev_page: 'ArrowLeft',
    next_chapter: 'ArrowDown',
    prev_chapter: 'ArrowUp',
    toggle_theme: 't',
    toggle_sidebar: 's',
    toggle_bars: 'b',
    bookmark: 'm',
    help: '?',
  };

  const SHORTCUT_ACTIONS = [
    ['next_page', '下一页'],
    ['prev_page', '上一页'],
    ['next_chapter', '下一章'],
    ['prev_chapter', '上一章'],
    ['toggle_theme', '切换主题'],
    ['toggle_sidebar', '开/关侧栏'],
    ['toggle_bars', '固定顶底栏'],
    ['bookmark', '书签'],
    ['help', '快捷键帮助'],
  ];

  function ensureBuilt() {
    let el = document.getElementById('settings-view');
    if (el) return el;
    el = document.createElement('div');
    el.className = 'settings-view hidden';
    el.id = 'settings-view';

    const head = document.createElement('div');
    head.className = 'settings-head';
    const back = document.createElement('button');
    back.className = 'top-btn';
    back.title = '返回';
    back.appendChild(Icons.icon('library', 18));
    back.addEventListener('click', () => SettingsPage.close());
    const title = document.createElement('div');
    title.className = 'settings-title';
    title.textContent = '设置';
    head.append(back, title);
    el.appendChild(head);

    const body = document.createElement('div');
    body.className = 'settings-body';
    body.appendChild(section('外观', appearanceRow()));
    body.appendChild(section('辅助功能', assistRow()));
    body.appendChild(section('快捷键', shortcutsRow()));
    body.appendChild(section('导出 NGA 帖子', ngaExportRow()));
    body.appendChild(section('阅读统计', statsRow()));
    body.appendChild(section('数据', dataRow()));
    const foot = document.createElement('p');
    foot.className = 'muted settings-hint';
    foot.id = 'sp-version';
    foot.textContent = '安科书架';
    body.appendChild(foot);
    el.appendChild(body);

    document.body.appendChild(el);
    return el;
  }

  function section(title, controls) {
    const s = document.createElement('div');
    s.className = 'settings-section';
    const t = document.createElement('div');
    t.className = 'settings-section-title';
    t.textContent = title;
    s.append(t, controls);
    return s;
  }

  function row(label, controls) {
    const r = document.createElement('div');
    r.className = 'settings-row';
    const l = document.createElement('span');
    l.className = 'settings-label';
    l.textContent = label;
    r.append(l, controls);
    return r;
  }

  function appearanceRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    wrap.appendChild(row('主题', themeRow()));
    wrap.appendChild(row('亮度', brightnessRow()));
    return wrap;
  }

  function themeRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-control-inline';
    const themes = ['light', 'sepia', 'dark'];
    themes.forEach((t) => {
      const b = btn(t === 'dark' ? '深色' : t === 'sepia' ? '羊皮纸' : '浅色', () => {
        const s = App.state.settings;
        s.theme = t;
        Theme.applyTheme(t);
        if (window.Reader) Reader.updateOverrides();
        Bridge.call('save_settings', { theme: t });
        SettingsPage.sync();
      });
      b.dataset.theme = t;
      b.classList.add('sp-theme-btn');
      wrap.appendChild(b);
    });
    return wrap;
  }

  function brightnessRow() {
    const input = document.createElement('input');
    input.type = 'range';
    input.id = 'sp-brightness';
    input.min = 0;
    input.max = 0.7;
    input.step = 0.05;
    input.value = App.state.settings.brightness || 0;
    input.addEventListener('input', () => {
      const s = App.state.settings;
      s.brightness = parseFloat(input.value);
      if (window.Assist) Assist.setBrightness(s.brightness);
      Bridge.call('save_settings', { brightness: s.brightness });
    });
    return input;
  }

  function assistRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    const toggles = [
      { label: '标尺', get: () => !document.getElementById('ruler').classList.contains('hidden'),
        set: (on) => Assist.setRuler(on) },
      { label: '逐段', get: () => !document.getElementById('paragraph-mask').classList.contains('hidden'),
        set: (on) => Assist.setParagraphMode(on) },
      { label: '速读', get: () => !document.getElementById('rsvp-box').classList.contains('hidden'),
        set: (on) => Assist.setRsvp(on) },
      { label: '滚读', get: () => !!Assist._auto.timer,
        set: (on) => Assist.setAutoScroll(on) },
    ];
    const rowWrap = document.createElement('div');
    rowWrap.className = 'settings-control-inline';
    for (const t of toggles) {
      const b = document.createElement('button');
      b.className = 'vm-btn sp-assist-btn';
      b.textContent = t.label;
      const syncBtn = () => b.classList.toggle('active', !!t.get());
      syncBtn();
      b.addEventListener('click', () => { t.set(!t.get()); syncBtn(); });
      rowWrap.appendChild(b);
    }
    wrap.appendChild(row('辅助功能', rowWrap));
    return wrap;
  }

  function shortcutsRow() {
    const s = App.state.settings;
    s.shortcuts = Object.assign({}, DEFAULT_SHORTCUTS, s.shortcuts || {});
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    const grid = document.createElement('div');
    grid.className = 'settings-shortcuts';
    for (const [action, label] of SHORTCUT_ACTIONS) {
      const r = document.createElement('div');
      r.className = 'settings-shortcut-row';
      const l = document.createElement('span');
      l.className = 'settings-label';
      l.textContent = label;
      const k = document.createElement('button');
      k.className = 'vm-btn sp-key-btn';
      k.dataset.action = action;
      k.textContent = displayKey(s.shortcuts[action]);
      k.addEventListener('click', () => captureKey(k, action));
      r.append(l, k);
      grid.appendChild(r);
    }
    const reset = document.createElement('button');
    reset.className = 'vm-btn';
    reset.textContent = '恢复默认';
    reset.addEventListener('click', () => {
      const sc = App.state.settings;
      sc.shortcuts = Object.assign({}, DEFAULT_SHORTCUTS);
      Bridge.call('save_settings', { shortcuts: sc.shortcuts });
      document.querySelectorAll('.sp-key-btn').forEach((b) => {
        b.textContent = displayKey(sc.shortcuts[b.dataset.action]);
      });
      Toast.show('快捷键已恢复默认');
    });
    grid.appendChild(reset);
    wrap.appendChild(grid);
    return wrap;
  }

  function displayKey(key) {
    if (!key) return '未设置';
    const map = {
      ' ': '空格', Space: '空格', ArrowRight: '→', ArrowLeft: '←', ArrowUp: '↑', ArrowDown: '↓',
      PageUp: 'PageUp', PageDown: 'PageDown', Home: 'Home', End: 'End',
    };
    return map[key] || key;
  }

  function captureKey(btn, action) {
    btn.textContent = '按任意键…';
    btn.classList.add('recording');
    const done = (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      document.removeEventListener('keydown', done, true);
      btn.classList.remove('recording');
      const key = ev.key === ' ' ? 'Space' : ev.key;
      if (!key || key === 'Escape') {
        btn.textContent = displayKey(App.state.settings.shortcuts[action]);
        return;
      }
      App.state.settings.shortcuts[action] = key;
      btn.textContent = displayKey(key);
      Bridge.call('save_settings', { shortcuts: App.state.settings.shortcuts });
      Toast.show('快捷键已更新');
    };
    document.addEventListener('keydown', done, true);
  }

  function ngaExportRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    const p = document.createElement('p');
    p.className = 'muted settings-hint';
    p.textContent = '打开“下载 / 导出”页（书架左上角 NGA 下载按钮），选择已下载的帖子与格式（EPUB / Markdown / 两者），即可自选文件夹导出并查看进度。';
    wrap.appendChild(p);
    return wrap;
  }

  function statsRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    const span = document.createElement('span');
    span.className = 'vm-value';
    span.id = 'sp-stats-summary';
    span.textContent = '—';
    const detail = document.createElement('button');
    detail.className = 'vm-btn';
    detail.textContent = '详情';
    detail.addEventListener('click', () => {
      if (window.Stats && Stats.showDetails) Stats.showDetails();
    });
    const rowWrap = document.createElement('div');
    rowWrap.className = 'settings-control-inline';
    rowWrap.append(span, detail);
    wrap.appendChild(rowWrap);
    return wrap;
  }

  function dataRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    const rowWrap = document.createElement('div');
    rowWrap.className = 'settings-control-inline';
    rowWrap.append(
      btn('打开数据目录', () => {
        Bridge.call('open_data_dir').catch((e) => Toast.show('打开失败：' + (e.message || e), true));
      }),
      btn('卸载并清除数据', uninstallAndClear),
    );
    wrap.appendChild(rowWrap);
    return wrap;
  }

  function uninstallAndClear() {
    if (!confirm('将删除全部用户数据（书架、进度、标注、NGA 配置、统计）并退出程序。\n确认继续？')) return;
    if (!confirm('最后确认：此操作不可恢复。确定清除全部数据？')) return;
    try {
      Bridge.call('uninstall_and_quit').catch(() => {});
    } catch (e) { /* 进程即将退出 */ }
    Toast.show('正在清除全部数据…');
  }

  function btn(label, onClick) {
    const b = document.createElement('button');
    b.className = 'vm-btn';
    b.textContent = label;
    b.addEventListener('click', onClick);
    return b;
  }

  function sync() {
    const s = App.state.settings;
    const menu = document.getElementById('settings-view');
    if (!menu) return;
    menu.querySelectorAll('.sp-theme-btn').forEach((b) => {
      b.classList.toggle('active', b.dataset.theme === s.theme);
    });
    const br = document.getElementById('sp-brightness');
    if (br) br.value = s.brightness || 0;
    if (s.shortcuts) {
      menu.querySelectorAll('.sp-key-btn').forEach((b) => {
        const action = b.dataset.action;
        if (action) b.textContent = displayKey(s.shortcuts[action]);
      });
    }
    if (App.state.bookId) {
      Bridge.call('get_stats', App.state.bookId).then((st) => {
        const span = document.getElementById('sp-stats-summary');
        if (!span) return;
        const secs = (st.book && st.book.total_seconds) || 0;
        const mins = Math.floor(secs / 60);
        span.textContent = mins > 0 ? `已读 ${mins} 分钟` : '不足 1 分钟';
      }).catch(() => {});
    }
  }

  window.SettingsPage = {
    open() {
      const el = ensureBuilt();
      sync();
      el.classList.remove('hidden');
      Bridge.call('get_version').then((v) => {
        const f = document.getElementById('sp-version');
        if (f) f.textContent = '安科书架 v' + v;
      }).catch(() => {});
    },
    close() {
      const el = document.getElementById('settings-view');
      if (el) el.classList.add('hidden');
    },
    sync,
  };
})();
