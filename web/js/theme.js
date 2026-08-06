/**
 * 主题与字号管理。
 * 关键机制：CSS 变量定义在 :root（style.css 三组主题），可穿透 shadow DOM；
 * 字号通过 #reader-root 的内联 style 设置变量，shadow 内覆盖样式引用之。
 */
(function () {
  'use strict';

  const THEMES = ['light', 'sepia', 'dark'];

  /** '#rgb'/'#rrggbb' → [r,g,b]；非法输入返回 null。 */
  function hexToRgb(hex) {
    if (!hex) return null;
    let h = String(hex).trim().replace(/^#/, '');
    if (h.length === 3) h = h.split('').map((c) => c + c).join('');
    if (!/^[0-9a-fA-F]{6}$/.test(h)) return null;
    const n = parseInt(h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  window.Theme = {
    /** 应用主题到 <html data-theme>，并把用户自定义颜色叠加覆盖到 CSS 变量上。
     *  custom 为空串的项保持“跟随主题”；文字色还会派生 muted/border 等半透明变量。 */
    applyTheme(theme, custom) {
      if (!THEMES.includes(theme)) theme = 'light';
      document.documentElement.dataset.theme = theme;
      const c = custom || {};
      const rs = document.documentElement.style;
      const bg = c.custom_bg || null;
      rs.setProperty('--reader-bg', bg);
      rs.setProperty('--base-100', bg);
      rs.setProperty('--base-200', bg);
      const primary = c.custom_primary || null;
      rs.setProperty('--primary', primary);
      rs.setProperty('--secondary', primary);
      rs.setProperty('--accent', c.custom_accent || primary);
      const rgb = hexToRgb(c.custom_text);
      if (rgb) {
        rs.setProperty('--reader-fg', c.custom_text);
        rs.setProperty('--base-content', c.custom_text);
        rs.setProperty('--muted', `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, 0.55)`);
        rs.setProperty('--neutral-content', `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, 0.55)`);
        rs.setProperty('--border', `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, 0.12)`);
      } else {
        ['--reader-fg', '--base-content', '--muted', '--neutral-content', '--border']
          .forEach((k) => rs.setProperty(k, null));
      }
    },

    /** 应用字号/行高到阅读器根节点（穿透 shadow 的变量） */
    applyReaderPrefs(fontSize, lineHeight) {
      const root = document.getElementById('reader-root');
      if (!root) return;
      root.style.setProperty('--reader-font-size', fontSize + 'px');
      root.style.setProperty('--reader-line-height', String(lineHeight));
    },

    /** 主题循环切换：light → sepia → dark → light */
    nextTheme(current) {
      const i = THEMES.indexOf(current);
      return THEMES[(i + 1) % THEMES.length];
    },
  };
})();
