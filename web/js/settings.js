/**
 * 独立设置页（参考 Koodo Reader 的 Tab 导航 + 选项说明、Readest/flow 的色板交互）：
 * 外观（主题模式/亮度/预设色板/自定义颜色 + 实时预览）、阅读、辅助、快捷键、统计、数据。
 * 阅读界面仍保留排版快捷菜单（view-menu.js）。
 */
(function () {
  'use strict';
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

    for (const [id, label] of SettingsUI.TABS) {
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
    appearance.appendChild(SettingsUI.section('主题', SettingsPanels.appearanceRow()));
    appearance.appendChild(SettingsUI.section('预设色板', SettingsPanels.paletteRow()));
    appearance.appendChild(SettingsUI.section('自定义颜色', SettingsPanels.customColorsRow()));

    const reading = panelById.reading;
    reading.appendChild(SettingsUI.section('排版', SettingsPanels.readingRow()));
    reading.appendChild(SettingsUI.section('界面', SettingsPanels.interfaceRow()));

    const assist = panelById.assist;
    assist.appendChild(SettingsUI.section('辅助功能', SettingsPanels.assistRow()));

    const shortcuts = panelById.shortcuts;
    shortcuts.appendChild(SettingsUI.section('快捷键', SettingsPanels.shortcutsRow()));

    const stats = panelById.stats;
    stats.appendChild(SettingsUI.section('阅读统计', SettingsPanels.statsRow()));

    const data = panelById.data;
    data.appendChild(SettingsUI.section('数据', SettingsPanels.dataRow()));

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
