/*
 * AnkeShelf Android reader-lite: minimal rendering bridge.
 * Keeps visual fidelity (chapter CSS + multi-column paging + text_offset)
 * with the smallest possible JS footprint. UI/image viewer/annotations are
 * handled by the Compose shell; this file only renders and reports.
 */
(function () {
  'use strict';

  var MAX_PAGED_TEXT = 800000;

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
  };

  function log(msg) {
    try { AnkeReaderBridge.log('[reader] ' + msg); } catch (e) { /* ignore */ }
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

  function geometry(fw, fh) {
    var dual = isDual(state.paged, state.dualPage, state.autoDual, fw, fh);
    var M = clamp(state.margin || 40, 8, 160);
    var G = clamp(state.gap || 28, 8, 120);
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
    };
  }

  /* ---------- text_offset mapping (same rules as desktop TextPos) ---------- */
  var RE_WS = /\s/;

  function isSkipNode(node) {
    var p = node.parentElement;
    return p && (p.tagName === 'SCRIPT' || p.tagName === 'STYLE');
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
        items.push({ node: node, text: node.data });
      }
      var raw = '';
      var sawPrev = false;
      var i;
      for (i = 0; i < items.length; i++) {
        var it = items[i];
        if (sawPrev) raw += ' ';
        sawPrev = true;
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
      return { doc: doc, text: text, ranges: ranges, mapRaw: mapRaw };
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
    var m = measure();
    if (dir > 0 && m.current >= m.total - 1) {
      try { AnkeReaderBridge.requestChapter(1); } catch (e) { /* ignore */ }
      return;
    }
    if (dir < 0 && m.current <= 0) {
      try { AnkeReaderBridge.requestChapter(-1); } catch (e) { /* ignore */ }
      return;
    }
    gotoPage(skipToContent(m.current + (dir > 0 ? 1 : -1), dir));
    report();
  }

  function currentOffset() {
    var ctx = state.textCtx;
    var el = scrollEl();
    if (!ctx || !el) return 0;
    var er = el.getBoundingClientRect();
    var x, y;
    if (state.paged) {
      x = Math.max(2, Math.min(er.left + state.margin + 2, window.innerWidth - 2));
      y = Math.max(2, er.top + (state.topInset || 0) + 8);
    } else {
      x = Math.max(2, Math.min(window.innerWidth / 2, window.innerWidth - 2));
      // 正文顶部内边距 = 18px + topInset（异形屏安全区），采样点必须越过它，
      // 否则 caretRangeFromPoint 落在空白处返回 null -> offset=0（进度漂移根因）。
      y = window.scrollY + 18 + (state.topInset || 0) + 8;
    }
    var off = TextPos.currentOffsetFromPoint(ctx, x, y);
    return off === null ? 0 : off;
  }

  function restoreScroll(offset) {
    var ctx = state.textCtx;
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
        // align with sampling: body top padding 18px + topInset, sample y = scrollY + 18 + topInset + 8
        window.scrollTo(0, Math.max(0, rect.top + window.scrollY - (18 + (state.topInset || 0) + 8)));
        state.restorePending = false;
        return;
      } catch (e) { /* fall through to ratio */ }
    }
    var ratio = len > 0 ? clamp(offset / len, 0, 1) : 0;
    window.scrollTo(0, ratio * Math.max(1, document.body.scrollHeight - window.innerHeight));
    if (ctx) state.restorePending = false;
  }

  function gotoOffset(offset) {
    if (!state.paged) {
      restoreScroll(offset);
      return 0;
    }
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
    var col = Math.max(0, Math.round((rect.left - er.left - P) / m.advance));
    var page = Math.floor(col / m.step);
    return gotoPage(page);
  }

  function report() {
    if (!state.paged) return;
    var m = measure();
    var off = currentOffset();
    if (state.restorePending && off > 0) state.restorePending = false;
    try {
      AnkeReaderBridge.pageChanged(state.chapterIndex, m.current, m.total, off);
      if (off > 0) AnkeReaderBridge.saveProgress(state.chapterIndex, off, true);
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
      if (t && t.tagName === 'IMG' && state.paged) onResize();
    }, true);
  }

  function forceEagerImages() {
    var imgs = document.querySelectorAll('img[loading="lazy"]');
    for (var i = 0; i < imgs.length; i++) imgs[i].loading = 'eager';
  }

  /* ---------- Kotlin-facing API ---------- */
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
    state.paged = !!paged && !state.huge;
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
          restoreScroll(offset);
        } else if (wasScrolled) {
          var r = window.scrollY / Math.max(1, document.body.scrollHeight - window.innerHeight);
          window.scrollTo(0, r * Math.max(1, document.body.scrollHeight - window.innerHeight));
        }
      }
      report();
    });
  }

  var resizeTimer = null;
  var resizeOffset = 0;
  var resizeScrolled = false;

  function onResize() {
    if (!state.paged) return;
    var el = scrollEl();
    var wasScrolled = !!el && el.scrollLeft > 1;
    var offset = currentOffset();
    if (offset > 0 && resizeOffset === 0) resizeOffset = offset;
    if (wasScrolled) resizeScrolled = true;
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      prepare();
      normalizeTallTables();
      if (resizeOffset > 0) {
        gotoOffset(resizeOffset);
      } else if (state.restorePending) {
        gotoOffset(state.restoreOffset);
      } else if (resizeScrolled && el) {
        var len = (state.textCtx && state.textCtx.text.length) || 0;
        if (len > 0) {
          var ratio = el.scrollLeft / Math.max(1, el.scrollWidth - el.clientWidth);
          gotoOffset(Math.round(ratio * len));
        }
      }
      resizeOffset = 0;
      resizeScrolled = false;
      report();
    }, 300);
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
      if (state.restorePending) restoreScroll(state.restoreOffset);
      return;
    }
    prepare();
    requestAnimationFrame(function () {
      normalizeTallTables();
      if (state.restorePending) gotoOffset(state.restoreOffset);
      report();
    });
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
    state.restorePending = state.restoreOffset > 0;
    if (!document.body) return;
    state.huge = (document.body.textContent || '').length > MAX_PAGED_TEXT;
    state.paged = !!opts.paged && !state.huge;
    if (state.paged) {
      state.textCtx = TextPos.build(document);
    } else {
      state.textCtx = null;
      setTimeout(function () {
        state.textCtx = TextPos.build(document);
        if (state.restorePending && state.restoreOffset > 0) restoreScroll(state.restoreOffset);
        var o = 0;
        try { o = currentOffset(); } catch (e) { /* ignore */ }
        if (o > 0) {
          try { AnkeReaderBridge.saveProgress(state.chapterIndex, o, true); } catch (e) { /* ignore */ }
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
    window.addEventListener('scroll', function () {
      if (scrollTimer) clearTimeout(scrollTimer);
      scrollTimer = setTimeout(function () {
        scrollTimer = null;
        var o = 0;
        try { o = currentOffset(); } catch (e) { /* ignore */ }
        if (o > 0) {
          try { AnkeReaderBridge.saveProgress(state.chapterIndex, o, true); } catch (e) { /* ignore */ }
        }
      }, 500);
    });
    window.addEventListener('pagehide', function () {
      var o = 0;
      try { o = currentOffset(); } catch (e) { /* ignore */ }
      if (o > 0) {
        try { AnkeReaderBridge.saveProgress(state.chapterIndex, o, true); } catch (e) { /* ignore */ }
      }
    });
    var finish = function () {
      if (state.paged) {
        prepare();
        normalizeTallTables();
        if (state.restoreOffset > 0) gotoOffset(state.restoreOffset);
        else gotoPage(0);
      } else {
        if (state.restoreOffset > 0) restoreScroll(state.restoreOffset);
      }
      report();
      try { AnkeReaderBridge.onReady(); } catch (e) { /* ignore */ }
    };
    requestAnimationFrame(finish);
    setTimeout(refresh, 150);
    setTimeout(refresh, 600);
  }

  /* long-press image hit test: returns "true" when an image is under (x,y) */
  function openImageAt(x, y) {
    var el = document.elementFromPoint(x, y);
    var img = el && el.closest ? el.closest('img') : null;
    if (img && img.src) {
      // 长按进入预览时清除系统文本选区，避免选中提示文字残留（9.20 记录）。
      var sel = window.getSelection ? window.getSelection() : null;
      if (sel) sel.removeAllRanges();
      try { AnkeReaderBridge.openImage(img.src); } catch (e) { /* ignore */ }
      return 'true';
    }
    return 'false';
  }

  window.AnkeReader = {
    init: init,
    applyTheme: applyTheme,
    applyTypography: applyTypography,
    setMode: setMode,
    flipPage: flipPage,
    currentOffset: currentOffset,
    onResize: onResize,
    setInsets: setInsets,
    gotoOffset: gotoOffset,
    openImageAt: openImageAt,
  };
})();
