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
  var BRIDGE_CAPABILITIES = ['paged', 'scroll', 'scrollRatio', 'image', 'settled', 'annotation'];

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
    userMoved: false,
    // 显式状态机阶段：bootstrapping / restoring / ready（Step 3 起 phase 取代 settled）
    phase: 'bootstrapping',
    // resize 防抖状态（Step 2 收进 state，避免模块级散落变量）
    resizeOffset: 0,
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

  /* ---------- paging geometry (mirrors desktop paged.js) ---------- */
  function contentWidth(fw, pageWidth, fontSize) {
    var maxW = Math.round(46 * (pageWidth || 1) * (fontSize || 18));
    return Math.max(120, Math.min(fw, maxW));
  }

  function shouldAutoDual(fw, fh) {
    if (fw < 800 || fw <= fh) return false;
    var aspect = fw / fh;
    return aspect >= 1.2 && aspect <= 2.6;
  }

  function isDual(paged, dualPage, autoDual, fw, fh) {
    if (!paged) return false;
    if (dualPage) return true;
    if (autoDual === false) return false;
    return shouldAutoDual(fw, fh);
  }

  function geometry(fw, fh, s) {
    var st = s || state;
    var dual = isDual(st.paged, st.dualPage, st.autoDual, fw, fh);
    var M = clamp(st.margin || 40, 8, 160);
    var G = clamp(st.gap || 28, 8, 120);
    var P = Math.max(4, Math.min(M, G - 8));
    var colW = dual
      ? Math.max(120, (fw - 2 * P - G) / 2)
      : Math.max(120, fw - 2 * P);
    if (dual && colW < 300) {
      dual = false;
      colW = Math.max(120, fw - 2 * P);
    }
    return {
      dual: dual,
      colW: colW,
      advance: colW + G,
      margin: P,
      paddingRight: P,
      gap: G,
      contentWidth: fw,
    };
  }

  /* ---------- text_offset mapping (same rules as desktop TextPos) ---------- */
  var RE_WS = /\s/;

  function isSkipNode(node) {
    var p = node.parentElement;
    while (p) {
      if (p.tagName === 'SCRIPT' || p.tagName === 'STYLE' || p.hasAttribute('data-textpos-exclude')) return true;
      p = p.parentElement;
    }
    return false;
  }

  /**
   * 注入元素（高亮 mark / 代码高亮 span）内的文本节点：这些是显示层注入，
   * 不应产生「相邻文本节点边界空格」，否则 text_offset 会与导入期 HTML
   * （Kotlin TextExtractor / Python extract_dom_text）漂移。
   * 识别：.hl-mark 或 .syntax 祖先（与桌面 web/js/textpos.js 同规则）。
   */
  function isInjectedText(node) {
    var el = node.parentElement;
    while (el) {
      if (el.classList && (el.classList.contains('hl-mark') || el.classList.contains('syntax'))) {
        return true;
      }
      el = el.parentElement;
    }
    return false;
  }

  /** 两个文本节点之间是否只有注释节点（无元素边界 → 不产生分隔空格）。 */
  function separatedByCommentOnly(a, b) {
    var s = b.previousSibling;
    while (s && s.nodeType === Node.COMMENT_NODE) s = s.previousSibling;
    return s === a;
  }

  /**
   * 纯函数：文本项数组 → 折叠纯文本与坐标映射。
   * items: [{ text, isInj, noSep }]；与桌面 web/js/textpos.js 的 foldItems
   * 逐字符一致（跨端 golden 对照见 contracts/tests/reader-lite-textpos.test.js）。
   */
  function foldItems(items) {
    var raw = '';
    var sawPrev = false;
    var lastWasInj = false;
    var i;
    var it;
    for (i = 0; i < items.length; i++) {
      it = items[i];
      if (sawPrev && !(it.isInj && lastWasInj) && !it.noSep) {
        raw += ' ';
      }
      sawPrev = true;
      lastWasInj = !!it.isInj;
      it.rawStart = raw.length;
      raw += it.text;
      it.rawEnd = raw.length;
    }

    var text = raw.replace(/\s+/g, ' ').trim();
    var mapRaw = new Int32Array(raw.length);
    var tIdx = 0;
    var prevSpace = false;
    var k = 0;
    while (k < raw.length && RE_WS.test(raw[k])) { mapRaw[k] = 0; k++; }
    for (; k < raw.length; k++) {
      if (RE_WS.test(raw[k])) {
        if (prevSpace) { mapRaw[k] = tIdx - 1; }
        else { mapRaw[k] = tIdx; tIdx++; prevSpace = true; }
      } else {
        mapRaw[k] = tIdx; tIdx++; prevSpace = false;
      }
    }

    var ranges = [];
    for (i = 0; i < items.length; i++) {
      it = items[i];
      if (it.rawEnd <= it.rawStart) continue;
      ranges.push({
        node: it.node,
        start: mapRaw[it.rawStart],
        end: mapRaw[it.rawEnd - 1] + 1,
        rawStart: it.rawStart,
      });
    }

    return { raw: raw, text: text, mapRaw: mapRaw, ranges: ranges };
  }

  var TextPos = {
    build: function (doc) {
      var items = [];
      var walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT, {
        acceptNode: function (n) {
          return isSkipNode(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
        },
      });
      var node;
      while ((node = walker.nextNode())) {
        var prev = items.length ? items[items.length - 1] : null;
        var noSep = !!(prev && prev.node && separatedByCommentOnly(prev.node, node));
        items.push({
          node: node,
          text: node.data,
          isInj: isInjectedText(node),
          noSep: noSep,
        });
      }
      var folded = foldItems(items);
      return {
        doc: doc,
        text: folded.text,
        ranges: folded.ranges,
        mapRaw: folded.mapRaw,
      };
    },

    nodeCharToPlain: function (ctx, range, charIndex) {
      return ctx.mapRaw[range.rawStart + charIndex] | 0;
    },

    plainToPoint: function (ctx, offset) {
      var ranges = ctx.ranges;
      if (!ranges.length) return null;
      var lo = 0, hi = ranges.length;
      while (lo < hi) {
        var mid = (lo + hi) >> 1;
        if (ranges[mid].start <= offset) lo = mid + 1;
        else hi = mid;
      }
      var idx = lo - 1;
      if (idx < 0) idx = 0;
      if (idx >= ranges.length) idx = ranges.length - 1;
      var r = ranges[idx];
      var inPlain = offset - r.start;
      var data = r.node.data;
      var ci = 0;
      for (var j = 0; j < data.length; j++) {
        var p = TextPos.nodeCharToPlain(ctx, r, j);
        if (p >= r.start + inPlain) { ci = j; break; }
        ci = j + 1;
      }
      if (ci > data.length) ci = data.length;
      return { node: r.node, charIndex: ci };
    },

    /** DOM Range → [start, end]（plain 坐标）；无法映射返回 null。 */
    rangeToOffsets: function (ctx, range) {
      var s = pointToOffset(ctx, range.startContainer, range.startOffset, false);
      var e = pointToOffset(ctx, range.endContainer, range.endOffset, true);
      if (s === null || e === null) return null;
      return [Math.min(s, e), Math.max(s, e)];
    },

    currentOffsetFromPoint: function (ctx, x, y) {
      try {
        var range = ctx.doc.caretRangeFromPoint(x, y);
        if (!range) return null;
        return pointToOffset(ctx, range.startContainer, range.startOffset, false);
      } catch (e) {
        return null;
      }
    },
  };

  function rangesIndex(ctx, node) {
    for (var i = 0; i < ctx.ranges.length; i++) {
      if (ctx.ranges[i].node === node) return i;
    }
    return -1;
  }

  function pointToOffset(ctx, container, offset, isEnd) {
    if (container.nodeType === Node.TEXT_NODE) {
      if (isSkipNode(container)) return null;
      var idx = rangesIndex(ctx, container);
      if (idx === -1) return null;
      var r = ctx.ranges[idx];
      var ci = Math.max(0, Math.min(offset, container.data.length));
      if (ci >= container.data.length) return r.end;
      return TextPos.nodeCharToPlain(ctx, r, ci);
    }
    if (container.nodeType === Node.ELEMENT_NODE) {
      var walker = ctx.doc.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
        acceptNode: function (n) {
          return isSkipNode(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
        },
      });
      var texts = [];
      var n;
      while ((n = walker.nextNode())) texts.push(n);
      if (!texts.length) return null;
      if (isEnd) {
        var li = rangesIndex(ctx, texts[texts.length - 1]);
        if (li === -1) return null;
        return ctx.ranges[li].end;
      }
      var fi = rangesIndex(ctx, texts[0]);
      if (fi === -1) return null;
      return ctx.ranges[fi].start;
    }
    return null;
  }

  /* ---------- paged layout ---------- */
  function currentGeometry() { return geometry(viewW(), viewH()); }

  function prepare() {
    var el = scrollEl();
    if (!el) return;
    var g = currentGeometry();
    var h = viewH();
    document.documentElement.style.height = h + 'px';
    document.body.style.height = h + 'px';
    document.body.style.minHeight = h + 'px';
    el.style.height = h + 'px';
    var root = document.documentElement;
    root.style.setProperty('--reader-margin', g.margin + 'px');
    root.style.setProperty('--reader-gap', g.gap + 'px');
    root.style.setProperty('--reader-pr', (g.paddingRight || 8) + 'px');
    root.style.setProperty('--reader-col', g.colW + 'px');
    el.style.maxWidth = viewW() + 'px';
    var spacer = document.getElementById('__dual_spacer__');
    if (g.dual) {
      if (!spacer) {
        spacer = document.createElement('div');
        spacer.id = '__dual_spacer__';
        spacer.setAttribute('aria-hidden', 'true');
        spacer.textContent = '\u200b';
        el.appendChild(spacer);
      }
      spacer.style.cssText =
        'height:1px;overflow:hidden;visibility:hidden;' +
        'break-before:column;-webkit-column-break-before:always;';
    } else if (spacer) {
      spacer.remove();
    }
  }

  function measure() {
    var el = scrollEl();
    if (!el) return { total: 1, current: 0, advance: 0, step: 1 };
    var g = currentGeometry();
    var cs = getComputedStyle(el);
    var colW = parseFloat(cs.columnWidth) || 0;
    var gap = parseFloat(cs.columnGap) || 0;
    var advance = colW + gap;
    if (advance <= 0) return { total: 1, current: 0, advance: 0, step: 1 };
    var pl = parseFloat(cs.paddingLeft) || 0;
    var pr = parseFloat(cs.paddingRight) || 0;
    var hasSpacer = !!document.getElementById('__dual_spacer__');
    var cols = Math.max(1, Math.round((el.scrollWidth - pl - pr + gap) / advance));
    if (hasSpacer) cols = Math.max(1, cols - 1);
    var step = g.dual ? 2 : 1;
    var total = Math.max(1, Math.ceil(cols / step));
    var current = clamp(Math.round((el.scrollLeft || 0) / (step * advance)), 0, total - 1);
    return { total: total, current: current, advance: advance, step: step };
  }

  function gotoPage(n) {
    var el = scrollEl();
    if (!el) return 0;
    var m = measure();
    var page = clamp(Math.round(n), 0, m.total - 1);
    el.scrollLeft = page * m.step * m.advance;
    return page;
  }

  function hitAt(x, y) {
    try {
      var r = document.caretRangeFromPoint(x, y);
      if (r && r.startContainer) {
        var n = r.startContainer;
        var data = n.nodeType === 3 ? n.data : (n.textContent || '');
        if (data && data.trim()) return true;
      }
    } catch (e) { /* ignore */ }
    var el = document.elementFromPoint(x, y);
    if (!el || el === document.body || el === document.documentElement) return false;
    if (el.closest && el.closest('img,video,audio,svg,canvas,picture')) return true;
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    var t;
    while ((t = walker.nextNode())) {
      if (t.data && t.data.trim()) return true;
    }
    return false;
  }

  function isPageBlank(page) {
    var el = scrollEl();
    if (!el) return false;
    var m = measure();
    if (!m.advance) return false;
    gotoPage(page);
    var w = el.clientWidth;
    var h = el.clientHeight;
    if (w <= 0 || h <= 0) return false;
    var er = el.getBoundingClientRect();
    var hits = 0, samples = 0, topHits = 0;
    var xs = m.step === 2 ? [0.15, 0.35, 0.65, 0.85] : [0.25, 0.5, 0.75];
    for (var fy = 0; fy < 5; fy++) {
      for (var fx = 0; fx < xs.length; fx++) {
        samples++;
        var x = er.left + Math.max(2, Math.min(w - 2, w * xs[fx]));
        var y = er.top + Math.round(h * [0.06, 0.2, 0.4, 0.6, 0.8][fy]);
        if (hitAt(x, y)) {
          hits++;
          if (fy === 0) topHits++;
        }
      }
    }
    if (topHits > 0) return false;
    return hits / samples < 0.25;
  }

  function skipToContent(page, dir) {
    var m = measure();
    var p = clamp(page, 0, m.total - 1);
    var guard = 0;
    while (guard < 5 && isPageBlank(p) && p > 0 && p < m.total - 1) {
      p += dir;
      guard++;
    }
    return p;
  }

  function flipPage(dir) {
    if (!state.paged) return;
    state.userMoved = true;
    var m = measure();
    log('[flip] dir=' + dir + ' before cur=' + m.current + '/' + m.total + ' sl=' + scrollEl().scrollLeft);
    if (dir > 0 && m.current >= m.total - 1) {
      callBridge('requestChapter', 1);
      return;
    }
    if (dir < 0 && m.current <= 0) {
      callBridge('requestChapter', -1);
      return;
    }
    gotoPage(skipToContent(m.current + (dir > 0 ? 1 : -1), dir));
    report(true);
    var o = currentOffsetSafe();
    try {
      var mm = measure();
      log('[flip] after cur=' + mm.current + '/' + mm.total + ' sl=' + scrollEl().scrollLeft + ' off=' + o);
    } catch (e) { /* ignore */ }
    if (o > 0) {
      state.pagedAnchor = o;
      state.pagedAnchorPage = mm.current;
      state.pagedAnchorTotal = mm.total;
    }
  }

  // 分页模式采样：页顶第一个文本行（图片页向下扫描整页），语义 = 页顶锚点。
  function currentOffsetPaged() {
    var ctx = state.textCtx;
    var el = scrollEl();
    if (!ctx || !el) return 0;
    var er = el.getBoundingClientRect();
    var x = Math.max(2, Math.min(er.left + state.margin + 2, window.innerWidth - 2));
    var y = Math.max(2, er.top + (state.topInset || 0) + 8);
    var off = offsetAtPointPaged(ctx, x, y);
    return off === null ? 0 : off;
  }

  // 滚动模式采样：视口中线（45%），语义 = 当前阅读行，与 restoreScrollOffset 严格对应。
  function currentOffsetScroll() {
    var ctx = state.textCtx;
    if (!ctx) return 0;
    var x = Math.max(2, Math.min(window.innerWidth / 2, window.innerWidth - 2));
    var y = sampleOffsetY();
    var off = offsetAtPointScroll(ctx, x, y);
    if (off !== null) {
      state.scrollRatio = -1;
      return off;
    }
    // 图片占满采样区（全屏大图停在屏幕中部）：文本锚点不可得，
    // 用滚动比例兜底，保证退出/防抖仍能保存，避免“自动回退到上一次进度”。
    var len = ctx.text.length;
    if (len <= 0) return 0;
    var max = Math.max(1, document.body.scrollHeight - window.innerHeight);
    var ratio = window.scrollY / max;
    state.scrollRatio = clamp(ratio, 0, 1);
    var out = Math.round(clamp(ratio, 0, 1) * len);
    log('[ratio-fallback] scrollY=' + Math.round(window.scrollY) + ' off=' + out);
    return out;
  }

  // Kotlin 换章/退出查询：一次取回“offset + 滚动比例 + 实际模式”，避免两次
  // evaluateJavascript 之间滚动状态变化导致 offset/ratio 不配对（9.53 异步写入可溯源）。
  // p=true 表示分页：Kotlin dispose 必须忽略 o（9.48：分页退出只 flush 已保存锚点，
  // 不能用页顶采样覆盖）；滚动模式 p=false 才采用 o/r。
  function currentScrollState() {
    var o = currentOffsetSafe();
    var r = state.paged ? -1 : state.scrollRatio;
    return { o: o, r: r, p: state.paged };
  }

  function currentOffset() {
    return state.paged ? currentOffsetPaged() : currentOffsetScroll();
  }

  function currentOffsetSafe() {
    try { return currentOffset(); } catch (e) { return 0; }
  }

  function currentOffsetScrollSafe() {
    try { return currentOffsetScroll(); } catch (e) { return 0; }
  }

  function sampleOffsetY() {
    return Math.max(8, Math.round(viewH() * 0.45));
  }

  function scrollAnchorY() {
    return Math.max(8, sampleOffsetY() - Math.round((state.fontSize * state.lineHeight) / 2));
  }

  // 采样必须落在正文文本上：段落间隙/padding 会返回 null 或元素首文本；
  // 命中图片时 caretRangeFromPoint 返回“邻近文本”（NGA 大图跨列时每页同一旧锚点），
  // 必须跳过图片继续向下找第一个文本行。
  function scanForText(ctx, x, y, maxY) {
    for (var yy = y; yy <= maxY; yy += 24) {
      var hit = document.elementFromPoint(x, yy);
      if (hit && hit.closest && hit.closest('img,video,audio,svg,canvas,picture')) continue;
      var off = TextPos.currentOffsetFromPoint(ctx, x, yy);
      if (off !== null) return off;
    }
    return null;
  }

  function offsetAtPointPaged(ctx, x, y) {
    return scanForText(ctx, x, y, Math.max(y + 24, Math.round(viewH() - 30)));
  }

  function offsetAtPointScroll(ctx, x, y) {
    // 扫描整页而不是只扫采样点下方 120px：屏幕中部是图片时，
    // 下方还有文本也能找到（图片只占一部分屏幕的常见场景）。
    return scanForText(ctx, x, y, Math.max(y + 24, Math.round(viewH() - 30)));
  }

  // 滚动模式恢复：把锚点行顶放到视口中线（scrollAnchorY），与 currentOffsetScroll 对应。
  function restoreScrollOffset(offset, ratio) {
    var ctx = state.textCtx;
    var r = (ratio === undefined || ratio === null) ? -1 : ratio;
    if (r >= 0 && r <= 1) {
      // 保存的是滚动比例（退出时屏幕中部全为图片）：按比例恢复滚动位置，
      // 不能把“文本比例”当文本锚点定位——图片占据的高度会破坏线性映射。
      var len = ctx ? ctx.text.length : 0;
      if (len > 0) {
        window.scrollTo(0, r * Math.max(1, document.body.scrollHeight - window.innerHeight));
        log('[restore:ratio] r=' + r + ' scrollY=' + Math.round(window.scrollY));
        state.restorePending = false;
        return;
      }
    }
    if (!ctx || offset <= 0) {
      window.scrollTo(0, 0);
      return;
    }
    var len = ctx.text.length;
    var point = TextPos.plainToPoint(ctx, len > 0 ? clamp(offset, 0, len) : 0);
    if (point && point.node) {
      try {
        var range = document.createRange();
        range.setStart(point.node, point.charIndex);
        range.collapse(true);
        var rect = range.getBoundingClientRect();
        try {
          log('[restore] target=' + offset + ' rectTop=' + Math.round(rect.top) +
            ' scrollY=' + Math.round(window.scrollY) + ' anchorY=' + Math.round(scrollAnchorY()));
        } catch (e) { /* ignore */ }
        // align with sampling: the anchor line's top lands at scrollAnchorY (viewport center).
        window.scrollTo(0, Math.max(0, rect.top + window.scrollY - scrollAnchorY()));
        try {
          log('[restore] -> scrollY=' + Math.round(window.scrollY) +
            ' sampled=' + (function () { try { return currentOffsetScroll(); } catch (e) { return -1; } })());
        } catch (e) { /* ignore */ }
        state.restorePending = false;
        return;
      } catch (e) { /* fall through to ratio */ }
    }
    var ratio = len > 0 ? clamp(offset / len, 0, 1) : 0;
    window.scrollTo(0, ratio * Math.max(1, document.body.scrollHeight - window.innerHeight));
    if (ctx) state.restorePending = false;
  }

  // 分页模式按 text_offset 定位：锚点字符所在列 → 页。
  function gotoOffset(offset) {
    var ctx = state.textCtx;
    if (!ctx) return 0;
    var point = TextPos.plainToPoint(ctx, offset);
    if (!point) return 0;
    var range = document.createRange();
    range.setStart(point.node, point.charIndex);
    range.collapse(true);
    var rect = range.getBoundingClientRect();
    var el = scrollEl();
    if (!el) return 0;
    var er = el.getBoundingClientRect();
    var m = measure();
    var P = Math.max(4, Math.min(state.margin || 40, (state.gap || 28) - 8));
    // rect.left 是视口坐标；容器横向滚动后内容已左移 scrollLeft，
    // 必须加回 scrollLeft 转成内容坐标，否则恢复一次后再次重排会逐页倒退/振荡。
    var col = Math.max(0, Math.round((rect.left + el.scrollLeft - er.left - P) / m.advance));
    var page = Math.floor(col / m.step);
    return gotoPage(page);
  }

  // 分页恢复锚点：优先“页码一致直接翻页”（含整页图片的页也能精确恢复）；
  // total 不一致（布局/图片加载状态变了）再按文本 offset 定位。
  function restorePagedAnchor(offset) {
    var m = measure();
    try {
      log('[restore-page] anchorPage=' + state.pagedAnchorPage + '/' + state.pagedAnchorTotal +
        ' now=' + m.current + '/' + m.total);
    } catch (e) { /* ignore */ }
    if (state.pagedAnchorPage >= 0 && state.pagedAnchorTotal > 0 && m.total === state.pagedAnchorTotal) {
      gotoPage(state.pagedAnchorPage);
      log('[restore-page] gotoPage -> ' + state.pagedAnchorPage + ' sl=' + scrollEl().scrollLeft);
      return true;
    }
    if (offset > 0) {
      gotoOffset(offset);
      return true;
    }
    return false;
  }

  // doSave=true 只在用户翻页时传；重排/恢复/模式切换只更新 UI，不写进度，
  // 避免把中间布局的临时页码污染已保存的锚点（进度漂移根因）。
  function report(doSave) {
    if (!state.paged) return;
    var m = measure();
    var off = currentOffset();
    if (state.restorePending && off > 0 && doSave) state.restorePending = false;
    try {
      callBridge('pageChanged', state.chapterIndex, m.current, m.total);
      // 翻页保存立即落盘（saveProgressNow），避免“进度缓存赶不上操作”。
      if (off > 0 && doSave) {
        log('[save:flip] ch=' + state.chapterIndex + ' off=' + off + ' page=' + m.current + '/' + m.total);
      // 分页模式显式 ratio=-1：滚动比例字段只属于滚动模式（模式隔离）。
      callBridge('saveProgressNow', state.chapterIndex, off, true, m.current, m.total, -1);
      }
    } catch (e) { /* ignore */ }
  }

  function normalizeTallTables() {
    var el = scrollEl();
    if (!el) return;
    var pageH = el.clientHeight;
    if (pageH <= 0) return;
    var maxH = Math.max(120, pageH - 8);
    var tables = document.querySelectorAll('table');
    for (var i = 0; i < tables.length; i++) {
      var t = tables[i];
      var parent = t.parentNode;
      if (parent && parent.classList && parent.classList.contains('nga-table-scroll')) continue;
      if (t.scrollHeight <= pageH + 2 && t.scrollWidth <= t.clientWidth + 2) continue;
      var wrap = document.createElement('div');
      wrap.className = 'nga-table-scroll';
      wrap.style.maxHeight = maxH + 'px';
      wrap.style.maxWidth = '100%';
      wrap.style.overflow = 'auto';
      wrap.style.margin = '6px 0';
      t.parentNode.insertBefore(wrap, t);
      wrap.appendChild(t);
    }
    var wraps = document.querySelectorAll('.nga-table-scroll');
    for (i = 0; i < wraps.length; i++) {
      wraps[i].style.maxHeight = maxH + 'px';
    }
  }

  /* ---------- images ---------- */
  function bindImages() {
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      imgs[i].referrerPolicy = 'no-referrer';
    }
    document.addEventListener('load', function (e) {
      var t = e.target;
      if (t && t.tagName === 'IMG' && state.paged) onResize();
    }, true);
    document.addEventListener('error', function (e) {
      var t = e.target;
      if (t && t.tagName === 'IMG') {
        // 加载失败替换为占位卡；占位无文本节点（文案走 CSS ::after），
        // data-textpos-exclude 双保险，text_offset 不受影响。
        var ph = document.createElement('span');
        ph.className = 'img-error-placeholder';
        ph.setAttribute('data-textpos-exclude', '');
        if (t.parentNode) t.parentNode.replaceChild(ph, t);
        if (state.paged) onResize();
      }
    }, true);
  }

  function forceEagerImages() {
    var imgs = document.querySelectorAll('img[loading="lazy"]');
    for (var i = 0; i < imgs.length; i++) imgs[i].loading = 'eager';
  }

  /* ---------- annotations: highlight injection + selection reporting ---------- */
  var HL_CLASS = 'hl-mark';

  function parseJsonSafe(payload) {
    if (!payload) return null;
    try {
      return JSON.parse(payload);
    } catch (e) {
      log('[ann] payload parse failed');
      return null;
    }
  }

  /** 移除全部高亮 mark 并合并回文本节点（重新注入前必须先清空）。 */
  function clearHighlightMarks() {
    var marks = document.querySelectorAll('mark.' + HL_CLASS);
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i];
      var parent = m.parentNode;
      if (!parent) continue;
      while (m.firstChild) parent.insertBefore(m.firstChild, m);
      parent.removeChild(m);
      parent.normalize();
    }
    return marks.length;
  }

  /**
   * 把 [start, end) 区间的文本包成连续 mark（跨节点安全，桌面 annotations.js 同算法）：
   * 先 extractContents 收集全部文本节点，再逐个 surroundContents，最后整体插回。
   */
  function wrapHighlight(ctx, item) {
    var start = Math.max(0, item.start | 0);
    var end = Math.max(0, item.end | 0);
    if (end <= start) return false;
    var sp = TextPos.plainToPoint(ctx, start);
    var ep = TextPos.plainToPoint(ctx, end);
    if (!sp || !ep || !sp.node || !ep.node) return false;
    try {
      var range = document.createRange();
      range.setStart(sp.node, sp.charIndex);
      range.setEnd(ep.node, ep.charIndex);
      if (range.collapsed) return false;
      var frag = range.extractContents();
      var nodes = [];
      var walker = document.createTreeWalker(frag, NodeFilter.SHOW_TEXT);
      var n;
      while ((n = walker.nextNode())) {
        if (n.data.length) nodes.push(n);
      }
      for (var i = 0; i < nodes.length; i++) {
        var mark = document.createElement('mark');
        mark.className = HL_CLASS + ' hl-' + (item.color || 'yellow');
        mark.setAttribute('data-hl', String(item.id || ''));
        var r = document.createRange();
        r.selectNode(nodes[i]);
        r.surroundContents(mark);
      }
      range.insertNode(frag);
      return nodes.length > 0;
    } catch (e) {
      log('[ann] wrap failed at ' + start + '-' + end);
      return false;
    }
  }

  /**
   * 注入当前章高亮。倒序（按 start 降序）注入：靠后的区间先包裹，
   * 前面区间引用的文本节点不受影响，单次坐标上下文即可完成全部注入。
   * 注入元素带 .hl-mark，按折叠规则不产生分隔空格，因此 text_offset 不变；
   * 但 ranges 的节点引用已失效，注入后统一重建一次坐标。
   */
  function applyHighlights(payload) {
    var list = parseJsonSafe(payload);
    if (!list || !list.length) {
      if (clearHighlightMarks() > 0) state.textCtx = TextPos.build(document);
      return 0;
    }
    clearHighlightMarks();
    var ctx = TextPos.build(document);
    var sorted = list.slice().sort(function (a, b) { return (b.start | 0) - (a.start | 0); });
    var applied = 0;
    for (var i = 0; i < sorted.length; i++) {
      if (wrapHighlight(ctx, sorted[i])) applied++;
    }
    state.textCtx = TextPos.build(document);
    log('[ann] applied ' + applied + '/' + list.length + ' highlights');
    return applied;
  }

  /**
   * 章节首次建坐标：宿主带来本章高亮时先注入再建坐标（与桌面 reader.js
   * 「注入高亮后重建坐标」同顺序），保证恢复定位与采样都基于最终 DOM。
   */
  function buildTextWithHighlights(payload) {
    if (payload) {
      applyHighlights(payload);
      if (state.textCtx) return state.textCtx;
    }
    return TextPos.build(document);
  }

  /** 当前选区 → text_offset 区间 + 视口矩形（CSS px），无有效选区返回 null。 */
  function currentSelectionInfo() {
    var ctx = state.textCtx;
    if (!ctx) return null;
    var sel = window.getSelection ? window.getSelection() : null;
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) return null;
    var range = sel.getRangeAt(0);
    var offsets = TextPos.rangeToOffsets(ctx, range);
    if (!offsets || offsets[0] >= offsets[1]) return null;
    var rect = range.getBoundingClientRect();
    return {
      start: offsets[0],
      end: offsets[1],
      text: ctx.text.slice(offsets[0], offsets[1]).slice(0, 2000),
      left: rect.left,
      top: rect.top,
      right: rect.right,
      bottom: rect.bottom,
    };
  }

  /** 选区变化 → 上报宿主（Compose 弹出标注工具条）；选区消失上报空。 */
  function bindSelection() {
    var timer = null;
    var lastKey = '';
    document.addEventListener('selectionchange', function () {
      if (timer) clearTimeout(timer);
      timer = setTimeout(function () {
        timer = null;
        var info = currentSelectionInfo();
        var key = info ? (info.start + ':' + info.end) : '';
        if (key === lastKey) return;
        lastKey = key;
        callBridge('onSelection', info ? JSON.stringify(info) : '');
      }, 120);
    });
    // 点击高亮 → 宿主打开编辑（改色/笔记/删除）
    document.addEventListener('click', function (e) {
      var t = e.target;
      var mark = t && t.closest ? t.closest('mark.' + HL_CLASS) : null;
      if (!mark) return;
      var id = mark.getAttribute('data-hl') || '';
      if (!id) return;
      e.preventDefault();
      e.stopPropagation();
      callBridge('onHighlightTap', id);
    }, true);
  }

  function clearSelection() {
    var sel = window.getSelection ? window.getSelection() : null;
    if (sel) sel.removeAllRanges();
  }

  /**
   * 书签/标注跳转：统一按 text_offset 定位。
   * 显式跳转的锚点是已知的文本坐标，因此两种模式都以文本锚点/页码落盘，
   * 绝不写滚动比例（比例兜底只属于滚动采样路径 saveProgress）——
   * 由此保持"saveProgressNow 永远 ratio=-1"的模式隔离不变量。
   */
  function gotoTextOffset(offset) {
    var target = Math.max(0, offset | 0);
    state.userMoved = true;
    if (state.paged) {
      prepare();
      normalizeTallTables();
      gotoOffset(target);
      state.pagedAnchor = target;
      var m = measure();
      state.pagedAnchorPage = m.current;
      state.pagedAnchorTotal = m.total;
      callBridge('pageChanged', state.chapterIndex, m.current, m.total);
      callBridge('saveProgressNow', state.chapterIndex, target, true, m.current, m.total, -1);
    } else {
      restoreScrollOffset(target, -1);
      state.scrollAnchor = target;
      state.scrollRatio = -1;
      callBridge('saveProgressNow', state.chapterIndex, target, true, -1, -1, -1);
    }
    return target;
  }

  /* ---------- Kotlin-facing API ---------- */
  function bridgeReadyPayload() {
    return { bridgeVersion: BRIDGE_VERSION, capabilities: BRIDGE_CAPABILITIES.slice() };
  }

  function emitReady() {
    callBridge('onReady', JSON.stringify(bridgeReadyPayload()));
  }

  function applyTheme(vars) {
    var root = document.documentElement;
    if (vars && vars.bg) root.style.setProperty('--reader-bg', vars.bg);
    if (vars && vars.fg) root.style.setProperty('--reader-fg', vars.fg);
    if (vars && vars.primary) root.style.setProperty('--reader-primary', vars.primary);
  }

  function applyTypography(style) {
    if (style && style.fontSize) {
      state.fontSize = style.fontSize;
      document.documentElement.style.setProperty('--reader-font-size', style.fontSize + 'px');
    }
    if (style && style.lineHeight) {
      state.lineHeight = style.lineHeight;
      document.documentElement.style.setProperty('--reader-line-height', String(style.lineHeight));
    }
  }

  function loadReaderFont() {
    if (window.__readerFontLoaded__) return;
    window.__readerFontLoaded__ = true;
    var style = document.createElement('style');
    style.textContent =
      '@font-face{font-family:"LXGW WenKai";' +
      'src:url("file:///android_asset/fonts/LXGWWenKai-Regular.ttf") format("truetype");' +
      'font-weight:400;font-display:swap;}';
    document.head.appendChild(style);
    if (document.fonts && document.fonts.load) {
      document.fonts.load('16px "LXGW WenKai"').then(function () {
        requestAnimationFrame(function () { onResize(); });
      }).catch(function () { /* keep system font */ });
    }
  }

  function clearPagedLayout() {
    var el = scrollEl();
    document.documentElement.style.height = '';
    document.body.style.height = '';
    document.body.style.minHeight = '';
    if (el) {
      el.style.height = '';
      el.style.maxWidth = '';
      var spacer = document.getElementById('__dual_spacer__');
      if (spacer) spacer.remove();
    }
  }

  function setMode(paged) {
    if (!document.body) return;
    var el = scrollEl();
    var wasScrolled = !!el && (state.paged ? el.scrollLeft > 1 : window.scrollY > 1);
    var offset = currentOffset();
    if (offset > 0) {
      // 模式切换是跨模式交接：text_offset 共通，两个模式的锚点都更新为当前值。
      state.pagedAnchor = offset;
      state.scrollAnchor = offset;
    }
    state.paged = !!paged && !state.huge;
    // 模式切换交接：旧模式的页码/锚点作废，避免后续恢复/重排跳回旧位置；
    // 遮罩重置，等新模式布局稳定后再放行。
    state.pagedAnchorPage = -1;
    state.pagedAnchorTotal = -1;
    state.phase = 'restoring';
    log('[state] restoring (setMode)');
    callBridge('onMode', state.paged);
    document.body.classList.toggle('paged', state.paged);
    if (state.paged) forceEagerImages();
    requestAnimationFrame(function () {
      if (state.paged) {
        prepare();
        normalizeTallTables();
        if (offset > 0) gotoOffset(offset);
        else if (wasScrolled && el) {
          var len = (state.textCtx && state.textCtx.text.length) || 0;
          if (len > 0) {
            var ratio = el.scrollLeft / Math.max(1, el.scrollWidth - el.clientWidth);
            gotoOffset(Math.round(ratio * len));
          }
        }
      } else {
        clearPagedLayout();
        if (offset > 0) {
          restoreScrollOffset(offset);
        } else if (wasScrolled) {
          var r = window.scrollY / Math.max(1, document.body.scrollHeight - window.innerHeight);
          window.scrollTo(0, r * Math.max(1, document.body.scrollHeight - window.innerHeight));
        }
      }
      report(false);
      if (!layoutReady()) {
        requestSettle(state.paged ? state.pagedAnchor : state.scrollAnchor, 0);
      } else {
        setTimeout(markSettled, 100);
      }
    });
  }

  var resizeTimer = null;
  var settleTimer = null;

  function layoutReady() {
    if (document.fonts && document.fonts.status === 'loading') return false;
    // 分页模式依赖图片撑起列高，必须等图片；滚动模式图片懒加载，
    // 等图片会拖到 8 秒兜底，只等字体即可。
    if (state.paged) {
      var imgs = document.images;
      for (var i = 0; i < imgs.length; i++) {
        if (!imgs[i].complete) return false;
      }
    }
    return true;
  }

  function markSettled() {
    if (state.phase === 'ready') return;
    state.phase = 'ready';
    log('[state] ready');
    callBridge('onSettled');
  }

  // 字体/图片加载期间多列布局会反复进入中间态（同一 offset 在不同列之间跳），
  // 只在全部就绪后做最终定位；8 秒兜底（网络卡死时也要能恢复）。
  // Step 1：所有“等布局就绪后恢复/标记就绪”的路径统一走这一个入口。
  function requestSettle(offset, deadline) {
    if (settleTimer) clearTimeout(settleTimer);
    if (state.phase !== 'ready') state.phase = 'restoring';
    var t = deadline || (Date.now() + 8000);
    settleTimer = setTimeout(function settleTick() {
      settleTimer = null;
      log('[settle] userMoved=' + state.userMoved + ' ready=' + layoutReady());
      if (state.userMoved) {
        // 用户已滚动/翻页：位置由用户掌控，settle 链只标记就绪，
        // 绝不能用初始 offset 把阅读位置拉回/覆盖（9.54 根因）。
        markSettled();
        return;
      }
      if (!layoutReady() && Date.now() < t) {
        settleTimer = setTimeout(settleTick, 200);
        return;
      }
      if (state.paged) {
        prepare();
        normalizeTallTables();
        restorePagedAnchor(offset);
      } else if (offset > 0) {
        restoreScrollOffset(offset, state.restoreRatio);
        // 滚动模式：字体就绪后的最终位置才是真位置，重采样并落盘，
        // 避免“切换模式后滚动段落记录错位”。
        var so = currentOffsetScrollSafe();
        if (so > 0) {
          log('[settle-save] so=' + so);
          state.scrollAnchor = so;
          // 滚动保存显式 page=-1：清除追踪器里残留的分页页码（模式隔离）。
          callBridge('saveProgress', state.chapterIndex, so, true, -1, -1, state.scrollRatio);
        }
      }
      report(false);
      markSettled();
    }, 200);
  }

  // Step 2：resize 防抖统一入口，状态收进 state.resizeOffset。
  function scheduleResize() {
    if (!state.paged) return;
    var el = scrollEl();
    // 重排锚点必须是稳定值（用户翻页/滚动时更新的 pagedAnchor），
    // 不能取“当前页顶采样”——多次重排时页顶会逐页漂移，越恢复越靠前。
    var offset = state.pagedAnchor > 0 ? state.pagedAnchor : currentOffsetPaged();
    if (offset > 0 && state.resizeOffset === 0) state.resizeOffset = offset;
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      resizeTimer = null;
      if (!layoutReady()) {
        requestSettle(state.resizeOffset > 0 ? state.resizeOffset : state.restoreOffset, 0);
        return;
      }
      prepare();
      normalizeTallTables();
      if (!restorePagedAnchor(state.resizeOffset > 0 ? state.resizeOffset : state.restoreOffset)) {
        // 无页码且 offset<=0：按滚动比例兜底（极少见）。
        var len = (state.textCtx && state.textCtx.text.length) || 0;
        if (len > 0) {
          var ratio = el.scrollLeft / Math.max(1, el.scrollWidth - el.clientWidth);
          gotoOffset(Math.round(ratio * len));
        }
      }
      state.resizeOffset = 0;
      report(false);
    }, 300);
  }

  function onResize() {
    scheduleResize();
  }

  function setInsets(top, bottom) {
    state.topInset = Math.max(0, top || 0);
    state.bottomInset = Math.max(0, bottom || 0);
    var root = document.documentElement;
    root.style.setProperty('--reader-top-inset', state.topInset + 'px');
    root.style.setProperty('--reader-bottom-inset', state.bottomInset + 'px');
    if (state.paged) onResize();
  }

  function refresh() {
    if (!state.paged) {
      if (state.restorePending) {
        restoreScrollOffset(state.restoreOffset, state.restoreRatio);
        markSettled();
      } else {
        markSettled();
      }
      return;
    }
    // 字体/图片未就绪时重排会把多列布局打回中间态，gotoOffset 会算出错误页
    // （恢复瞬间闪回章首）；全部就绪后也统一走 requestSettle 做最终兜底重排。
    requestSettle(state.restorePending ? state.restoreOffset : state.pagedAnchor, 0);
  }

  function init(opts) {
    state.chapterIndex = opts.chapterIndex || 0;
    state.margin = opts.margin || 40;
    state.gap = opts.gap || 28;
    state.pageWidth = opts.pageWidth || 1;
    state.fontSize = opts.fontSize || 18;
    state.lineHeight = opts.lineHeight || 1.8;
    state.dualPage = !!opts.dualPage;
    state.autoDual = opts.autoDual !== false;
    state.topInset = Math.max(0, opts.topInset || 0);
    state.bottomInset = Math.max(0, opts.bottomInset || 0);
    state.restoreOffset = Math.max(0, opts.offset || 0);
    state.restoreRatio = (opts.scrollRatio === undefined || opts.scrollRatio === null) ? -1 : opts.scrollRatio;
    state.scrollRatio = -1;
    state.pagedAnchor = state.restoreOffset;
    state.scrollAnchor = state.restoreOffset;
    state.restorePending = state.restoreOffset > 0;
    state.wasSwitch = !!opts.wasSwitch;
    state.phase = 'bootstrapping';
    log('[state] bootstrapping (init)');
    state.pagedAnchorPage = (opts.page === undefined || opts.page === null) ? -1 : opts.page;
    state.pagedAnchorTotal = (opts.total === undefined || opts.total === null) ? -1 : opts.total;
    if (!document.body) return;
    state.huge = (document.body.textContent || '').length > MAX_PAGED_TEXT;
    state.paged = !!opts.paged && !state.huge;
    callBridge('onMode', state.paged);
    if (state.paged) {
      state.textCtx = buildTextWithHighlights(opts.highlights);
    } else {
      state.textCtx = null;
      setTimeout(function () {
        state.textCtx = buildTextWithHighlights(opts.highlights);
        state.phase = 'restoring';
        log('[state] restoring (scroll init)');
        if (state.restorePending && state.restoreOffset > 0) {
          restoreScrollOffset(state.restoreOffset, state.restoreRatio);
        }
        var o = currentOffsetSafe();
        if (o > 0) {
          log('[save:scroll] ch=' + state.chapterIndex + ' off=' + o);
          state.scrollAnchor = o;
          callBridge('saveProgress', state.chapterIndex, o, true, -1, -1, state.scrollRatio);
        }
        if (!layoutReady()) {
          requestSettle(state.restoreOffset > 0 ? state.restoreOffset : state.scrollAnchor, 0);
        } else {
          markSettled();
        }
      }, 0);
    }
    document.body.classList.toggle('paged', state.paged);
    if (state.paged) forceEagerImages();
    if (opts.theme) applyTheme(opts.theme);
    applyTypography({ fontSize: state.fontSize, lineHeight: state.lineHeight });
    loadReaderFont();
    var root = document.documentElement;
    root.style.setProperty('--reader-top-inset', state.topInset + 'px');
    root.style.setProperty('--reader-bottom-inset', state.bottomInset + 'px');
    bindImages();
    bindSelection();
    // 滚动模式底部换章按钮（分页模式下由 CSS 隐藏）。
    var prevBtn = document.getElementById('android-prev-chapter');
    var nextBtn = document.getElementById('android-next-chapter');
    if (prevBtn) {
      prevBtn.addEventListener('click', function () {
        callBridge('requestChapter', -1);
      });
    }
    if (nextBtn) {
      nextBtn.addEventListener('click', function () {
        callBridge('requestChapter', 1);
      });
    }
    // 只拦截章节内链接；图片打开由 Kotlin 长按（openImageAt）触发，单击不放行。
    document.addEventListener('click', function (e) {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[href]') : null;
      if (a) {
        e.preventDefault();
        e.stopPropagation();
      }
    }, true);
    window.addEventListener('resize', function () { onResize(); });
    // debounced scroll progress (scroll mode, same as desktop reader.js 500ms)
    var scrollTimer = null;
    var lastScrollNotify = 0;
    window.addEventListener('scroll', function () {
      // 分页模式的保存只走翻页 saveProgressNow；字体/图片重排引发的
      // window 滚动事件绝不能当成滚动进度保存（会把正确偏移覆盖成页顶采样）。
      if (state.paged) return;
      // 滚动发生即通知 Compose 外壳（250ms 节流）：唤出浮动栏后的“新滚动”才允许
      // 自动收起；不能等 500ms 防抖保存回调——那可能是唤出前滚动的迟到事件，
      // 会把刚唤出的控制条误收（9.59）。
      var now = Date.now();
      if (now - lastScrollNotify > 250) {
        lastScrollNotify = now;
        callBridge('onScrollMoved');
      }
      if (scrollTimer) clearTimeout(scrollTimer);
      scrollTimer = setTimeout(function () {
        scrollTimer = null;
        var o = currentOffsetSafe();
        if (o > 0) {
          state.userMoved = true;
          state.scrollAnchor = o;
          callBridge('saveProgress', state.chapterIndex, o, true, -1, -1, state.scrollRatio);
        }
      }, 500);
    });
    // 不再用 pagehide 兜底保存：销毁时页面 scrollLeft/滚动位置会被重置，
    // 迟到的 pagehide 会用错误 offset 覆盖刚 flush 的正确进度（9.48 根因）。
    // 退出保存统一由 Kotlin dispose 查询 + flush 完成。
    var finish = function () {
      if (state.paged) {
        prepare();
        normalizeTallTables();
        if (!restorePagedAnchor(state.restoreOffset)) gotoPage(0);
        // 换章后立即把新章位置落库（桌面 loadChapter 语义；首次打开不写，
        // 避免中间布局污染已保存的锚点）。
        if (state.wasSwitch) {
          var o = currentOffsetSafe();
          // 章首采样可能落在首楼卡片 padding 上返回 0；此时也应把“已换到本章”
          // 落库（offset=1 即章首），否则退出重进会回到上一章。
          log('[save:switch] ch=' + state.chapterIndex + ' off=' + (o > 0 ? o : 1));
          // 分页换章落库：显式 page=-1,total=-1,ratio=-1，绝不携带滚动比例。
          callBridge('saveProgressNow', state.chapterIndex, o > 0 ? o : 1, true, -1, -1, -1);
        }
      } else {
        if (state.restoreOffset > 0) restoreScrollOffset(state.restoreOffset, state.restoreRatio);
      }
      if (!layoutReady()) {
        requestSettle(
          state.paged ? state.pagedAnchor : state.scrollAnchor,
          0,
        );
      } else {
        setTimeout(markSettled, 100);
      }
      report(false);
      emitReady();
    };
    requestAnimationFrame(finish);
    // 最终兜底：字体加载完成后 onResize 已负责定位；此定时器仅在
    // 字体加载失败/无 resize 事件时兜底一次。
    setTimeout(refresh, 2000);
  }

  /* long-press image hit test: returns "true" when an image is under (x,y) */
  function openImageAt(x, y) {
    var el = document.elementFromPoint(x, y);
    var img = el && el.closest ? el.closest('img') : null;
    if (img && img.src) {
      // 长按进入预览时清除系统文本选区，避免选中提示文字残留（9.20 记录）。
      var sel = window.getSelection ? window.getSelection() : null;
      if (sel) sel.removeAllRanges();
      callBridge('openImage', img.src);
      return 'true';
    }
    return 'false';
  }

  var AnkeReaderApi = {
    init: init,
    applyTheme: applyTheme,
    applyTypography: applyTypography,
    setMode: setMode,
    flipPage: flipPage,
    currentOffset: currentOffset,
    currentScrollState: currentScrollState,
    onResize: onResize,
    setInsets: setInsets,
    gotoOffset: gotoOffset,
    openImageAt: openImageAt,
    geometry: geometry,
    shouldAutoDual: shouldAutoDual,
    buildText: TextPos.build,
    // 标注（批 1）：注入高亮 / 读取选区 / 清选区 / 按 text_offset 跳转
    applyHighlights: applyHighlights,
    selectionInfo: function () {
      var info = currentSelectionInfo();
      return info ? JSON.stringify(info) : '';
    },
    clearSelection: clearSelection,
    gotoTextOffset: gotoTextOffset,
    bridgeVersion: function () { return BRIDGE_VERSION; },
    bridgeReadyPayload: bridgeReadyPayload,
    emitReady: emitReady,
  };

  if (typeof window !== 'undefined') {
    window.AnkeReader = AnkeReaderApi;
  }
  // Node 契约测试入口：只导出与 DOM 无关的纯函数（跨端折叠规则对照）。
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { foldItems: foldItems, AnkeReader: AnkeReaderApi };
  }
})();
