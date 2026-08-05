/**
 * 主题与字号管理。
 * 关键机制：CSS 变量定义在 :root（style.css 三组主题），可穿透 shadow DOM；
 * 字号通过 #reader-root 的内联 style 设置变量，shadow 内覆盖样式引用之。
 */
(function () {
  'use strict';

  const THEMES = ['light', 'sepia', 'dark'];

  window.Theme = {
    /** 应用主题到 <html data-theme> */
    applyTheme(theme) {
      if (!THEMES.includes(theme)) theme = 'light';
      document.documentElement.dataset.theme = theme;
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
