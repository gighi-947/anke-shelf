/**
 * ??????????? settings.js??Tabs/??/????? section/row/btn ????
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
    ['books', '书籍管理'],
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

  function btn(label, onClick) {
    const b = document.createElement('button');
    b.className = 'vm-btn';
    b.textContent = label;
    b.addEventListener('click', onClick);
    return b;
  }

  window.SettingsUI = { DEFAULT_SHORTCUTS, SHORTCUT_ACTIONS, TABS, MODES, COLOR_SWATCHES, section, row, btn };
})();
