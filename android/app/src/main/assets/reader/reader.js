/*
 * AnkeShelf 安卓阅读引擎（安卓专用，不复用桌面 web/ 代码）。
 * M0：占位实现；M2 移植分页几何 / textpos / 主题变量 / 标注回放。
 */
(function () {
  'use strict';

  function applyTheme(vars) {
    var root = document.documentElement.style;
    if (vars.bg) root.setProperty('--reader-bg', vars.bg);
    if (vars.fg) root.setProperty('--reader-fg', vars.fg);
    if (vars.primary) root.setProperty('--reader-primary', vars.primary);
  }

  function applyTypography(style) {
    var root = document.documentElement.style;
    if (style.fontSize) root.setProperty('--reader-font-size', style.fontSize + 'px');
    if (style.lineHeight) root.setProperty('--reader-line-height', String(style.lineHeight));
  }

  function gotoOffset(chapterIndex, textOffset) {
    // M2：使用 textpos 定位到字符偏移并滚动/翻页到对应位置。
    console.log('[AnkeShelf] gotoOffset', chapterIndex, textOffset);
  }

  function flipPage(direction) {
    // M2：分页模式翻页；滚动模式无操作。
    console.log('[AnkeShelf] flipPage', direction);
  }

  window.AnkeReader = {
    applyTheme: applyTheme,
    applyTypography: applyTypography,
    gotoOffset: gotoOffset,
    flipPage: flipPage,
  };

  document.addEventListener('DOMContentLoaded', function () {
    if (window.AnkeReaderBridge) {
      window.AnkeReaderBridge.onReady({
        width: window.innerWidth,
        height: window.innerHeight,
        chapterIndex: 0,
      });
    }
  });
})();
