/**
 * 独立设置页（参考 Koodo Reader 的 Tab 导航 + 选项说明、Readest/flow 的色板交互）：
 * 外观（主题模式/亮度/预设色板/自定义颜色 + 实时预览）、阅读、辅助、快捷键、统计、数据。
 * 阅读界面仍保留排版快捷菜单（view-menu.js）。
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
    toggle_fullscreen: 'F11',
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
    ['toggle_fullscreen', '沉浸式阅读'],
  ];

  const TABS = [
    ['appearance', '外观'],
    ['reading', '阅读'],
    ['assist', '辅助'],
    ['shortcuts', '快捷键'],
    ['stats', '统计'],
    ['data', '数据'],
  ];

  const MODES = [
    ['system', '跟随系统'],
    ['light', '浅色'],
    ['sepia', '羊皮纸'],
    ['dark', '深色'],
  ];

  /** 自定义颜色行的快捷色板（''=跟随主题）。 */
  const COLOR_SWATCHES = {
    custom_bg: ['', '#ffffff', '#f1e8d0', '#dcedd8', '#fdf6e3', '#eceff4', '#222222', '#1e293b', '#002b36'],
    custom_primary: ['', '#0066cc', '#77bbee', '#008b8b', '#268bd2', '#2f855a', '#60a5fa', '#5e81ac'],
    custom_accent: ['', '#0066cc', '#2aa198', '#88c0d0', '#38bdf8', '#38a169', '#f59e0b', '#e11d48'],
    custom_text: ['', '#171717', '#5b4636', '#2e3440', '#657b83', '#e0e0e0', '#e2e8f0', '#93a1a1'],
  };

  let activeTab = 'appearance';

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

    const layout = document.createElement('div');
    layout.className = 'settings-layout';
    const tabs = document.createElement('nav');
    tabs.className = 'settings-tabs';
    const panels = document.createElement('div');
    panels.className = 'settings-panels';
    const panelById = {};

    for (const [id, label] of TABS) {
      const t = document.createElement('button');
      t.className = 'settings-tab' + (id === activeTab ? ' active' : '');
      t.dataset.tab = id;
      t.textContent = label;
      t.addEventListener('click', () => switchTab(id));
      tabs.appendChild(t);
      const p = document.createElement('div');
      p.className = 'settings-panel' + (id === activeTab ? ' active' : '');
      p.dataset.panel = id;
      p.id = 'sp-panel-' + id;
      panels.appendChild(p);
      panelById[id] = p;
    }
    layout.append(tabs, panels);
    el.appendChild(layout);

    const appearance = panelById.appearance;
    appearance.appendChild(section('主题', appearanceRow()));
    appearance.appendChild(section('预设色板', paletteRow()));
    appearance.appendChild(section('自定义颜色', customColorsRow()));

    const reading = panelById.reading;
    reading.appendChild(section('排版', readingRow()));
    reading.appendChild(section('界面', interfaceRow()));

    const assist = panelById.assist;
    assist.appendChild(section('辅助功能', assistRow()));

    const shortcuts = panelById.shortcuts;
    shortcuts.appendChild(section('快捷键', shortcutsRow()));

    const stats = panelById.stats;
    stats.appendChild(section('阅读统计', statsRow()));

    const data = panelById.data;
    data.appendChild(section('数据', dataRow()));

    const foot = document.createElement('p');
    foot.className = 'muted settings-hint settings-version';
    foot.id = 'sp-version';
    foot.textContent = '安科书架';
    panels.appendChild(foot);

    document.body.appendChild(el);
    return el;
  }

  function switchTab(id) {
    activeTab = id;
    const el = document.getElementById('settings-view');
    if (!el) return;
    el.querySelectorAll('.settings-tab').forEach((t) => {
      t.classList.toggle('active', t.dataset.tab === id);
    });
    el.querySelectorAll('.settings-panel').forEach((p) => {
      p.classList.toggle('active', p.dataset.panel === id);
    });
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

  function row(label, controls, desc) {
    const r = document.createElement('div');
    r.className = 'settings-row';
    const lw = document.createElement('div');
    lw.className = 'settings-label-wrap';
    const l = document.createElement('span');
    l.className = 'settings-label';
    l.textContent = label;
    lw.appendChild(l);
    if (desc) {
      const d = document.createElement('span');
      d.className = 'muted settings-desc';
      d.textContent = desc;
      lw.appendChild(d);
    }
    r.append(lw, controls);
    return r;
  }

  function appearanceRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    wrap.appendChild(row('主题模式', themeModeRow(),
      '跟随系统会随 Windows 深浅色自动切换；阅读页顶栏按钮仍可循环切换并固定为新主题'));
    wrap.appendChild(row('亮度', brightnessRow(), '叠加一层黑色遮罩，适合夜间调低屏幕亮度'));
    wrap.appendChild(previewCard());
    return wrap;
  }

  function themeModeRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-control-inline';
    MODES.forEach(([mode, label]) => {
      const b = btn(label, () => {
        const s = App.state.settings;
        s.theme_mode = mode;
        if (mode !== 'system') s.theme = mode;
        Theme.applySettings(s);
        if (window.Reader) Reader.updateOverrides();
        if (window.App) App.updateThemeIcons();
        Api.saveSettings( { theme: s.theme, theme_mode: mode });
        SettingsPage.sync();
        Toast.show(mode === 'system' ? '已切换为跟随系统主题' : '主题已更新');
      });
      b.dataset.mode = mode;
      b.classList.add('sp-theme-mode-btn');
      wrap.appendChild(b);
    });
    return wrap;
  }

  function previewCard() {
    const card = document.createElement('div');
    card.className = 'sp-preview';
    card.id = 'sp-preview';
    const title = document.createElement('div');
    title.className = 'sp-preview-title';
    title.textContent = '安科书架';
    const text = document.createElement('div');
    text.className = 'sp-preview-text';
    text.innerHTML = '这是一段用于预览主题效果的示例文字。' +
      '<span class="sp-preview-color">彩色字体保留原色</span>，仅默认黑/白文字跟随主题。';
    card.append(title, text);
    return card;
  }

  function paletteRow() {
    const wrap = document.createElement('div');
    wrap.className = 'sp-palette-grid';
    const palettes = window.Theme ? Theme.PALETTES : [];
    for (const p of palettes) {
      const b = document.createElement('button');
      b.className = 'sp-palette-btn';
      b.dataset.palette = p.id;
      const dots = document.createElement('span');
      dots.className = 'sp-palette-dots';
      dots.innerHTML =
        `<span style="background:${p.bg}"></span>` +
        `<span style="background:${p.text}"></span>` +
        `<span style="background:${p.primary}"></span>`;
      const name = document.createElement('span');
      name.className = 'sp-palette-name';
      name.textContent = p.name;
      b.append(dots, name);
      b.addEventListener('click', () => {
        const s = App.state.settings;
        s.custom_bg = p.bg;
        s.custom_text = p.text;
        s.custom_primary = p.primary;
        s.custom_accent = p.accent;
        Theme.applySettings(s);
        if (window.Reader) Reader.updateOverrides();
        Api.saveSettings( {
          custom_bg: p.bg, custom_text: p.text,
          custom_primary: p.primary, custom_accent: p.accent,
        });
        SettingsPage.sync();
        Toast.show('已应用色板「' + p.name + '」');
      });
      wrap.appendChild(b);
    }
    const reset = btn('恢复跟随主题', () => {
      const s = App.state.settings;
      s.custom_bg = '';
      s.custom_text = '';
      s.custom_primary = '';
      s.custom_accent = '';
      Theme.applySettings(s);
      if (window.Reader) Reader.updateOverrides();
      Api.saveSettings( {
        custom_bg: '', custom_text: '',
        custom_primary: '', custom_accent: '',
      });
      SettingsPage.sync();
      Toast.show('已恢复主题默认颜色');
    });
    reset.id = 'sp-palette-reset';
    reset.classList.add('sp-palette-reset');
    wrap.appendChild(reset);
    return wrap;
  }

  function customColorsRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    wrap.appendChild(row('背景色', colorRow('sp-custom-bg', 'custom_bg', '跟随主题'),
      '用于阅读页与界面背景；深色背景会自动派生工具栏层次'));
    wrap.appendChild(row('主题色', colorRow('sp-custom-primary', 'custom_primary', '跟随主题'),
      '用于按钮、进度条、选中态等强调元素'));
    wrap.appendChild(row('强调色', colorRow('sp-custom-accent', 'custom_accent', '跟随主题'),
      '用于标注色块、引用边线等次要强调'));
    wrap.appendChild(row('文字颜色', colorRow('sp-custom-text', 'custom_text', '跟随主题'),
      '仅作用于默认黑/白文字，NGA 帖子中的彩色字体保留原色'));
    return wrap;
  }

  function colorRow(id, key, hint) {
    const wrap = document.createElement('div');
    wrap.className = 'settings-control-inline sp-color-row';
    const input = document.createElement('input');
    input.type = 'color';
    input.id = id;
    input.className = 'sp-color-input';
    input.value = App.state.settings[key] || '#000000';
    input.title = hint;
    const apply = () => {
      const s = App.state.settings;
      Theme.applySettings(s);
      if (window.Reader) Reader.updateOverrides();
      if (window.Assist) Assist.setBrightness(s.brightness || 0);
      if (window.App) App.updateThemeIcons();
    };
    input.addEventListener('input', () => {
      const s = App.state.settings;
      s[key] = input.value;
      apply();
      Api.saveSettings( { [key]: input.value });
      SettingsPage.sync();
    });
    const swatches = document.createElement('div');
    swatches.className = 'sp-swatch-row';
    for (const val of (COLOR_SWATCHES[key] || [''])) {
      const s = document.createElement('button');
      s.type = 'button';
      s.className = 'sp-swatch' + (val === (App.state.settings[key] || '') ? ' active' : '');
      s.dataset.color = val;
      s.title = val ? val : '跟随主题';
      if (val) s.style.background = val;
      else s.classList.add('sp-swatch-follow');
      s.addEventListener('click', () => {
        const st = App.state.settings;
        st[key] = val;
        input.value = val || '#000000';
        apply();
        Api.saveSettings( { [key]: val });
        SettingsPage.sync();
      });
      swatches.appendChild(s);
    }
    const reset = btn('默认', () => {
      const s = App.state.settings;
      s[key] = '';
      input.value = '#000000';
      apply();
      Api.saveSettings( { [key]: '' });
      SettingsPage.sync();
    });
    const hintEl = document.createElement('span');
    hintEl.className = 'muted settings-hint';
    hintEl.textContent = hint;
    wrap.append(input, swatches, reset, hintEl);
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
    const val = document.createElement('span');
    val.className = 'vm-value';
    val.id = 'sp-brightness-val';
    val.textContent = Math.round((input.value || 0) * 100) + '%';
    input.addEventListener('input', () => {
      const s = App.state.settings;
      s.brightness = parseFloat(input.value);
      val.textContent = Math.round(s.brightness * 100) + '%';
      if (window.Assist) Assist.setBrightness(s.brightness);
      Api.saveSettings( { brightness: s.brightness });
    });
    const wrap = document.createElement('div');
    wrap.className = 'settings-control-inline';
    wrap.append(input, val);
    return wrap;
  }

  function readingRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    wrap.appendChild(row('字号', rangeRow('sp-font-size', 12, 36, 1,
      () => App.state.settings.font_size || 18,
      (v) => {
        const s = App.state.settings;
        s.font_size = v;
        Theme.applyReaderPrefs(s.font_size, s.line_height);
        if (window.Reader) Reader.updateOverrides();
        Api.saveSettings( { font_size: s.font_size });
        if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
      },
      (v) => v + 'px')));
    wrap.appendChild(row('行高', rangeRow('sp-line-height', 12, 30, 1,
      () => Math.round((App.state.settings.line_height || 1.8) * 10),
      (v) => {
        const s = App.state.settings;
        s.line_height = v / 10;
        Theme.applyReaderPrefs(s.font_size, s.line_height);
        if (window.Reader) Reader.updateOverrides();
        Api.saveSettings( { line_height: s.line_height });
        if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
      },
      (v) => (v / 10).toFixed(1))));
    wrap.appendChild(row('页面宽度', rangeRow('sp-page-width', 50, 150, 5,
      () => Math.round((App.state.settings.page_width || 1) * 100),
      (v) => {
        const s = App.state.settings;
        s.page_width = v / 100;
        if (window.Reader) Reader.setPageWidth(s.page_width);
        Api.saveSettings( { page_width: s.page_width });
        if (window.ViewMenu && ViewMenu.sync) ViewMenu.sync();
      },
      (v) => v + '%')));
    wrap.appendChild(row('翻页方式', layoutSelect(),
      '滚动阅读不分页、整章滚动到底即一章；分页模式支持单页与横屏双页'));
    return wrap;
  }

  function rangeRow(id, min, max, step, get, set, fmt) {
    const wrap = document.createElement('div');
    wrap.className = 'settings-control-inline';
    const input = document.createElement('input');
    input.type = 'range';
    input.id = id;
    input.min = min;
    input.max = max;
    input.step = step;
    input.value = get();
    const val = document.createElement('span');
    val.className = 'vm-value';
    val.id = id + '-val';
    val.textContent = fmt(input.value);
    input.addEventListener('input', () => {
      const v = parseInt(input.value, 10);
      val.textContent = fmt(v);
      set(v);
    });
    wrap.append(input, val);
    return wrap;
  }

  function layoutSelect() {
    const sel = document.createElement('select');
    sel.id = 'sp-layout';
    sel.className = 'vm-select';
    const opts = [
      ['scroll', '滚动阅读'],
      ['auto', '自动双页（横屏双页）'],
      ['single', '单页分页'],
      ['dual', '横屏双页（强制）'],
    ];
    for (const [v, label] of opts) {
      const o = document.createElement('option');
      o.value = v;
      o.textContent = label;
      sel.appendChild(o);
    }
    sel.addEventListener('change', () => {
      const s = App.state.settings;
      const v = sel.value;
      s.pagination = v !== 'scroll';
      s.dual_page = v === 'dual';
      if (v === 'auto') s.auto_dual = true;
      else if (v === 'single') s.auto_dual = false;
      Api.saveSettings( {
        pagination: s.pagination,
        dual_page: s.dual_page,
        auto_dual: s.auto_dual !== false,
      });
      if (window.Reader) Reader.onPaginationChange();
    });
    return sel;
  }

  function interfaceRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    const box = document.createElement('label');
    box.className = 'nga-check sp-check-row';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.id = 'sp-bars-pinned';
    cb.checked = !!App.state.settings.bars_pinned;
    cb.addEventListener('change', () => {
      App.state.settings.bars_pinned = cb.checked;
      if (window.App) App.setBarsPinned(cb.checked);
      Api.saveSettings( { bars_pinned: cb.checked });
    });
    const span = document.createElement('span');
    span.textContent = '固定显示阅读页顶栏与底栏';
    box.append(cb, span);
    wrap.appendChild(box);
    const desc = document.createElement('span');
    desc.className = 'muted settings-desc';
    desc.textContent = '固定后翻页/换章不会自动收起顶底栏';
    wrap.appendChild(desc);
    return wrap;
  }

  function assistRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    const toggles = [
      { label: '标尺', desc: '显示一条横向参考线，辅助对齐阅读行', get: () => !document.getElementById('ruler').classList.contains('hidden'),
        set: (on) => Assist.setRuler(on) },
      { label: '逐段', desc: '一次只显示一段，其余内容半透明遮罩', get: () => !document.getElementById('paragraph-mask').classList.contains('hidden'),
        set: (on) => Assist.setParagraphMode(on) },
      { label: '速读', desc: '屏幕中央逐词显示，适合快速通读', get: () => !document.getElementById('rsvp-box').classList.contains('hidden'),
        set: (on) => Assist.setRsvp(on) },
      { label: '滚读', desc: '按设定速度自动向下滚动阅读', get: () => !!Assist._auto.timer,
        set: (on) => Assist.setAutoScroll(on) },
    ];
    for (const t of toggles) {
      const r = document.createElement('div');
      r.className = 'settings-row';
      const lw = document.createElement('div');
      lw.className = 'settings-label-wrap';
      const l = document.createElement('span');
      l.className = 'settings-label';
      l.textContent = t.label;
      const d = document.createElement('span');
      d.className = 'muted settings-desc';
      d.textContent = t.desc;
      lw.append(l, d);
      const b = document.createElement('button');
      b.className = 'vm-btn sp-assist-btn';
      b.textContent = t.label;
      const syncBtn = () => b.classList.toggle('active', !!t.get());
      syncBtn();
      b.addEventListener('click', () => { t.set(!t.get()); syncBtn(); });
      r.append(lw, b);
      wrap.appendChild(r);
    }
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
      k.textContent = Util.displayKey(s.shortcuts[action]);
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
      Api.saveSettings( { shortcuts: sc.shortcuts });
      document.querySelectorAll('.sp-key-btn').forEach((b) => {
        b.textContent = Util.displayKey(sc.shortcuts[b.dataset.action]);
      });
      Toast.show('快捷键已恢复默认');
    });
    grid.appendChild(reset);
    wrap.appendChild(grid);
    const hint = document.createElement('p');
    hint.className = 'muted settings-hint';
    hint.textContent = '点击按键后按下新的组合键即可绑定；Esc 取消本次录制。';
    wrap.appendChild(hint);
    return wrap;
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
        btn.textContent = Util.displayKey(App.state.settings.shortcuts[action]);
        return;
      }
      App.state.settings.shortcuts[action] = key;
      btn.textContent = Util.displayKey(key);
      Api.saveSettings( { shortcuts: App.state.settings.shortcuts });
      Toast.show('快捷键已更新');
    };
    document.addEventListener('keydown', done, true);
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
    const hint = document.createElement('p');
    hint.className = 'muted settings-hint';
    hint.textContent = '默认汇总全部书目；进入详情后可按具体书目查看。';
    wrap.appendChild(hint);
    return wrap;
  }

  function dataRow() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';
    const rowWrap = document.createElement('div');
    rowWrap.className = 'settings-control-inline';
    rowWrap.append(
      btn('打开数据目录', () => {
        Api.openDataDir().catch((e) => Toast.show('打开失败：' + (e.message || e), true));
      }),
      btn('导出诊断信息', () => {
        Api.exportDiagnostics().then((r) => {
          if (r && r.ok) Toast.show('诊断包已导出：' + r.path);
          else Toast.show('导出失败：' + ((r && r.error) || '已取消'), true);
        }).catch((e) => Toast.show('导出失败：' + (e.message || e), true));
      }),
      btn('卸载并清除数据', uninstallAndClear),
    );
    wrap.appendChild(rowWrap);
    const hint = document.createElement('p');
    hint.className = 'muted settings-hint';
    hint.textContent = 'NGA 帖子的下载与导出请在书架左上角「NGA 下载」页操作；卸载将删除书架、进度、标注、NGA 配置与统计。';
    wrap.appendChild(hint);
    return wrap;
  }

  function uninstallAndClear() {
    if (!confirm('将删除全部用户数据（书架、进度、标注、NGA 配置、统计）并退出程序。\n确认继续？')) return;
    if (!confirm('最后确认：此操作不可恢复。确定清除全部数据？')) return;
    try {
      Api.uninstallAndQuit().catch(() => {});
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
    menu.querySelectorAll('.sp-theme-mode-btn').forEach((b) => {
      const mode = b.dataset.mode;
      b.classList.toggle('active',
        mode === 'system' ? s.theme_mode === 'system' : (s.theme_mode || s.theme) === mode);
    });
    if (window.Theme && Theme.PALETTES) {
      menu.querySelectorAll('.sp-palette-btn').forEach((b) => {
        const p = Theme.PALETTES.find((x) => x.id === b.dataset.palette);
        b.classList.toggle('active', !!p &&
          s.custom_bg === p.bg && s.custom_text === p.text &&
          s.custom_primary === p.primary && s.custom_accent === p.accent);
      });
    }
    for (const key of ['custom_bg', 'custom_primary', 'custom_accent', 'custom_text']) {
      const input = document.getElementById('sp-' + key.replace(/_/g, '-'));
      if (input) input.value = s[key] || '#000000';
    }
    menu.querySelectorAll('.sp-color-row').forEach((row) => {
      const input = row.querySelector('.sp-color-input');
      const key = input.id.slice(3).replace(/-/g, '_');
      row.querySelectorAll('.sp-swatch').forEach((sw) => {
        sw.classList.toggle('active', sw.dataset.color === (s[key] || ''));
      });
    });
    const br = document.getElementById('sp-brightness');
    if (br) br.value = s.brightness || 0;
    const brv = document.getElementById('sp-brightness-val');
    if (brv) brv.textContent = Math.round((s.brightness || 0) * 100) + '%';
    const fs = document.getElementById('sp-font-size');
    if (fs) fs.value = s.font_size || 18;
    const fsv = document.getElementById('sp-font-size-val');
    if (fsv) fsv.textContent = (s.font_size || 18) + 'px';
    const lh = document.getElementById('sp-line-height');
    if (lh) lh.value = Math.round((s.line_height || 1.8) * 10);
    const lhv = document.getElementById('sp-line-height-val');
    if (lhv) lhv.textContent = (s.line_height || 1.8).toFixed(1);
    const pw = document.getElementById('sp-page-width');
    if (pw) pw.value = Math.round((s.page_width || 1) * 100);
    const pwv = document.getElementById('sp-page-width-val');
    if (pwv) pwv.textContent = Math.round((s.page_width || 1) * 100) + '%';
    const layout = document.getElementById('sp-layout');
    if (layout) {
      if (!s.pagination) layout.value = 'scroll';
      else if (s.dual_page) layout.value = 'dual';
      else layout.value = s.auto_dual === false ? 'single' : 'auto';
    }
    const bars = document.getElementById('sp-bars-pinned');
    if (bars) bars.checked = !!s.bars_pinned;
    if (s.shortcuts) {
      menu.querySelectorAll('.sp-key-btn').forEach((b) => {
        const action = b.dataset.action;
        if (action) b.textContent = Util.displayKey(s.shortcuts[action]);
      });
    }
    Api.getStats().then((st) => {
      const span = document.getElementById('sp-stats-summary');
      if (!span) return;
      const secs = (st.global && st.global.total_seconds) || 0;
      span.textContent = '全部书籍 · 已读 ' + Util.fmtDuration(secs);
    }).catch(() => {});
  }

  window.SettingsPage = {
    open() {
      const el = ensureBuilt();
      sync();
      el.classList.remove('hidden');
      Api.getVersion().then((v) => {
        const f = document.getElementById('sp-version');
        if (f) f.textContent = '安科书架 v' + v;
      }).catch(() => {});
    },
    close() {
      const el = document.getElementById('settings-view');
      if (el) el.classList.add('hidden');
    },
    switchTab,
    sync,
  };
})();
