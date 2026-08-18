/*
 * AnkeShelf Android reader-lite: minimal rendering bridge.
 * Keeps visual fidelity (chapter CSS + multi-column paging + text_offset)
 * with the smallest possible JS footprint. UI/image viewer/annotations are
 * handled by the Compose shell; this file only renders and reports.
 */
(function () {
  'use strict';

  var MAX_PAGED_TEXT = 800000;
  // 桥协议版本：ready 握手时与 Kotlin 侧对照，不兼容时显式失败并记诊断。
  var BRIDGE_VERSION = 1;
  var BRIDGE_CAPABILITIES = ['paged', 'scroll', 'scrollRatio', 'image', 'settled'];

  var state = {
    paged: false,
    huge: false,
    chapterIndex: 0,
    margin: 40,
    gap: 28,
    pageWidth: 1,
    fontSize: 18,
    lineHeight: 1.8,
    dualPage: false,
    autoDual: true,
    topInset: 0,
    bottomInset: 0,
    textCtx: null,
    restoreOffset: 0,
    restorePending: false,
    // 模式隔离：分页锚点 = 页顶采样（含页码），滚动锚点 = 视口中线采样，
    // 两种模式绝不共用同一锚点字段，避免“改一边坏另一边”。
    pagedAnchor: 0,
    pagedAnchorPage: -1,
    pagedAnchorTotal: -1,
    scrollAnchor: 0,
    // 滚动模式专属：-1 = 文本锚点可用；0..1 = 当前屏全为图片时按滚动比例兜底。
    // 分页模式永不写入（模式隔离，见 9.50）。
    scrollRatio: -1,
    restoreRatio: -1,
    wasSwitch: false,
    settled: false,
    userMoved: false,
    // 显式状态机阶段：bootstrapping / restoring / ready（Step 0 先记录，不改行为）
    phase: 'bootstrapping',
  };

  function callBridge(name) {
    try {
      return AnkeReaderBridge[name].apply(AnkeReaderBridge, [].slice.call(arguments, 1));
    } catch (e) { /* ignore */ }
  }

  function log(msg) {
    callBridge('log', '[reader] ' + msg);
  }

  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
  function viewW() { return window.innerWidth || 1; }
  function viewH() { return window.innerHeight || 1; }
  function scrollEl() { return document.getElementById('paged-scroll'); }

