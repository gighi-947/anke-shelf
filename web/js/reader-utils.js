/**
 * Reader 纯工具与常量（B4）：CSS 覆盖层、快捷键帮助文案、字体解析。
 * 与 reader.js 解耦；App/Paged 只在运行时被引用。
 */
(function () {
  'use strict';

  const BASE_OVERRIDE = `
    body {
      font-family: var(--reader-font-family, "Segoe UI", "Microsoft YaHei", serif) !important;
      font-size: var(--reader-font-size, 18px) !important;
      line-height: var(--reader-line-height, 1.8) !important;
      color: var(--reader-fg, #222) !important;
      background: transparent !important;
      margin: 0 !important;
      padding: 0 !important;
      overflow-wrap: anywhere !important;
      word-break: break-word !important;
    }
    p { margin: 0.6em 0; }
    img { max-width: 100% !important; height: auto !important; }
    a { color: var(--reader-accent, #77bbee) !important; }
    h1, h2, h3, h4 { line-height: 1.4 !important; }
    pre { overflow-x: auto; }
    table { max-width: 100%; }
  `;

  // NGA books keep their original thread styles (floor cards, quotes, colors);
  // only typography and image responsiveness are managed here.
  const NGA_OVERRIDE = `
    body {
      font-family: var(--reader-font-family, "Segoe UI", "Microsoft YaHei", serif) !important;
      font-size: var(--reader-font-size, 18px) !important;
      line-height: var(--reader-line-height, 1.8) !important;
      /* 默认文字色跟随阅读器主题：深色页→浅色字，浅色页→深色字。
         仅作用于 body 继承的默认色；楼层内的彩色/灰色字体不受影响。 */
      color: var(--reader-fg, #222) !important;
      background: transparent !important;
      overflow-wrap: anywhere !important;
      word-break: break-word !important;
    }
    img { max-width: 100% !important; height: auto !important; }
    pre { overflow-x: auto; }
    table { max-width: 100%; }
  `;

  const PAGINATION_OVERRIDE = `
    html, body {
      height: 100% !important;
      width: 100% !important;
      margin: 0 !important;
      overflow: hidden !important;
      box-sizing: border-box !important;
    }
    body {
      padding: 20px var(--margin-px, 40px) !important;
      column-width: var(--col-px, 600px) !important;
      column-gap: var(--gap-px, 28px) !important;
      column-fill: auto !important;
    }
    pre { white-space: pre-wrap !important; word-break: break-all; }
    img, video {
      max-width: 100% !important;
      max-height: 72vh !important;
      object-fit: contain;
      break-inside: avoid;
    }
    p { margin: 0.45em 0 !important; }
    /* NGA 楼层/表格/引用等必须允许跨页拆分：楼层里的长表格常超过一页高度，
       若整栋楼禁止分页，表格会整体溢出列边界，导致页面出界与错位。 */
    .nga-floor, .nga-quote, .nga-comment, blockquote, table, details {
      margin: 10px 0 !important;
      break-inside: auto !important;
    }
    .nga-floor { padding: 10px 12px !important; }
    table { max-width: 100% !important; }
    td, th {
      max-width: 100% !important;
      overflow-wrap: anywhere !important;
      word-break: break-word !important;
    }
    .nga-table-scroll {
      max-width: 100% !important;
      overflow: auto !important;
    }
  `;

  // 快捷键帮助弹窗内容（与 settings.js 的默认值保持一致；此处只做展示兜底）
  const HELP_SHORTCUTS = {
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
  const HELP_ACTIONS = [
    ['next_page', '下一页 / 下一章'],
    ['prev_page', '上一页 / 上一章'],
    ['next_chapter', '下一章'],
    ['prev_chapter', '上一章'],
    ['toggle_theme', '切换主题'],
    ['toggle_sidebar', '开/关侧栏'],
    ['toggle_bars', '固定顶底栏'],
    ['bookmark', '书签'],
    ['help', '快捷键帮助'],
    ['toggle_fullscreen', '沉浸式阅读（全屏）'],
  ];

  function activeFontKey() {
    const s = App.state.settings || {};
    if (App.state.bookId && s.book_fonts && s.book_fonts[App.state.bookId]) {
      return s.book_fonts[App.state.bookId];
    }
    return s.custom_font || '';
  }

  function fontFaceCss() {
    const key = activeFontKey();
    if (!key) return '';
    let url = '';
    if (key.startsWith('sys:')) url = '/font/system/' + key.slice(4);
    else if (key.startsWith('custom:')) url = '/font/custom/' + key.slice(7);
    if (!url) return '';
    return '@font-face { font-family: "AnkeCustomFont"; src: url("' + url + '"); font-display: swap; }\n';
  }

  function resolveFamily() {
    const key = activeFontKey();
    if (key) return '"AnkeCustomFont", "Segoe UI", "Microsoft YaHei", serif';
    return '"Segoe UI", "Microsoft YaHei", serif';
  }

  window.ReaderUtils = {
    BASE_OVERRIDE,
    NGA_OVERRIDE,
    PAGINATION_OVERRIDE,
    HELP_SHORTCUTS,
    HELP_ACTIONS,
    activeFontKey,
    fontFaceCss,
    resolveFamily,
  };
})();
