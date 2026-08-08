/*
 * AnkeShelf 安卓阅读引擎（安卓专用，不复用桌面 web/ 代码）。
 *
 * 功能：
 * - CSS multi-column 分页（scrollLeft 按 advance 翻页），双页模式按屏幕
 *   比例/列宽自动判定；超大章（> MAX_PAGED_TEXT）自动回退滚动模式。
 * - text_offset 双向映射：TextPos.build / plainToPoint / currentOffset。
 * - Kotlin 桥：saveProgress(chapterIndex, value, isOffset)、
 *   onPageChanged(page, total, offset)、requestChapter(delta)、log。
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
  };

  function log(msg) {
    try { AnkeReaderBridge.log('[reader] ' + msg); } catch (e) { /* ignore */ }
  }

  function clamp(v, lo, hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  function viewW() { return window.innerWidth || 1; }
  function viewH() { return window.innerHeight || 1; }
  function scrollEl() { return document.getElementById('paged-scroll'); }

  /* ---------------- PagedMath（纯函数，window.PagedMath 供跨端对照测试） ---------------- */
  function contentWidth(fw, pageWidth, fontSize) {
    var maxW = Math.round(46 * (pageWidth || 1) * (fontSize || 18));
    return Math.max(120, Math.min(fw, maxW));
  }

  /* 自动双页：仅横屏且够宽；宽高比过方（<1.2）或超宽（>2.6）不自动双页，
   * 避免狭长比例下列宽过窄/过宽影响阅读。 */
  function shouldAutoDual(fw, fh) {
    if (fw < 800 || fw <= fh) return false;
    var aspect = fw / fh;
    if (aspect < 1.2 || aspect > 2.6) return false;
    return true;
  }

  function isDual(paged, dualPage, autoDual, fw, fh) {
    if (!paged) return false;
    if (dualPage) return true;
    if (autoDual === false) return false;
    return shouldAutoDual(fw, fh);
  }

  function geometry(fw, fh, s) {
    var dual = isDual(s.paged, s.dualPage, s.autoDual, fw, fh);
    // flow/epub.js 列几何 + 右缘安全余量：
    // 右 padding 用较小值 PR（约 gap/3），列宽 = 内容可用宽（不拉伸），
    // 下一列起点 = M + colW + G = fw + (G - PR)，比视口右缘多出约 19px，
    // 亚像素/物理像素舍入也不会漏出下一页内容。
    var cw = fw;
    var M = clamp(s.margin || 40, 8, 160);
    var G = clamp(s.gap || 28, 8, 120);
    var PR = Math.max(4, Math.round(G / 3));
    var colW = dual
      ? Math.max(120, (cw - M - PR - G) / 2)
      : Math.max(120, cw - M - PR);
    // 双页后列宽过窄时回退单页（极端狭长屏幕保护）
    if (dual && colW < 300) {
      dual = false;
      colW = Math.max(120, cw - M - PR);
    }
    return {
      dual: dual,
      colW: colW,
      advance: colW + G,
      margin: M,
      paddingRight: PR,
      gap: G,
      contentWidth: cw,
    };
  }

  function pages(scrollWidth, g, hasSpacer) {
    // 容器左 padding = margin、右 padding = paddingRight：
    // scrollWidth = margin + n*colW + (n-1)*gap + paddingRight
    var cols = Math.max(
      1,
      Math.round((scrollWidth - g.margin - (g.paddingRight || 0) + g.gap) / g.advance),
    );
    if (hasSpacer) cols = Math.max(1, cols - 1);
    var step = g.dual ? 2 : 1;
    return { cols: cols, total: Math.max(1, Math.ceil(cols / step)), step: step };
  }

  window.PagedMath = {
    contentWidth: contentWidth,
    shouldAutoDual: shouldAutoDual,
    isDual: isDual,
    geometry: geometry,
    pages: pages,
    clamp: clamp,
    maxPagedText: MAX_PAGED_TEXT,
  };

  /* ---------------- TextPos：DOM 坐标 <-> 纯文本 text_offset ---------------- */
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
      for (var i = 0; i < items.length; i++) {
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

  window.TextPos = TextPos;

  /* ---------------- 分页核心 ---------------- */
  function currentGeometry() {
    return geometry(viewW(), viewH(), state);
  }

  function prepare() {
    var el = scrollEl();
    if (!el) return;
    var g = currentGeometry();
    // Android WebView 对 html/body 百分比高度链解析不稳定（首帧 body 高度可能为 0），
    // 直接用像素高度锁定视口，保证 multi-column 以整屏高度分栏。
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
    el.style.maxWidth = g.contentWidth + 'px';
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
    if (!el) return { total: 1, current: 0, advance: 0, step: 1, geometry: null };
    var g = currentGeometry();
    // 与桌面 paged.js 一致：翻页步进必须用浏览器实际渲染的列宽/间距，
    // 否则 column-width 被钳制到内容宽时会出现逐屏累积偏移。
    var cs = getComputedStyle(el);
    var colW = parseFloat(cs.columnWidth) || 0;
    var gap = parseFloat(cs.columnGap) || 0;
    var advance = colW + gap;
    if (advance <= 0) return { total: 1, current: 0, advance: 0, step: 1, geometry: g };
    var pl = parseFloat(cs.paddingLeft) || 0;
    var pr = parseFloat(cs.paddingRight) || 0;
    var hasSpacer = !!document.getElementById('__dual_spacer__');
    var cols = Math.max(1, Math.round((el.scrollWidth - pl - pr + gap) / advance));
    if (hasSpacer) cols = Math.max(1, cols - 1);
    var step = g.dual ? 2 : 1;
    var total = Math.max(1, Math.ceil(cols / step));
    var current = clamp(Math.round((el.scrollLeft || 0) / (step * advance)), 0, total - 1);
    return { total: total, current: current, advance: advance, step: step, geometry: g };
  }

  function gotoPage(n) {
    var el = scrollEl();
    if (!el) return 0;
    var m = measure();
    var page = clamp(Math.round(n), 0, m.total - 1);
    el.scrollLeft = page * m.step * m.advance;
    return page;
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

  function firstContentPage() {
    var m = measure();
    var p = 0;
    var guard = 0;
    while (guard < 5 && isPageBlank(p) && p < m.total - 1) {
      p++;
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
    var x = Math.max(2, Math.min(er.left + state.margin + 2, window.innerWidth - 2));
    // 采样点必须落在正文文本区（避开顶部安全区 padding），
    // 否则 caretRangeFromPoint 在空白处返回 null -> offset=0，
    // 唤出系统栏触发重排时会把阅读位置误跳回第一页。
    var y = Math.max(2, er.top + (state.topInset || 0) + 8);
    var off = TextPos.currentOffsetFromPoint(ctx, x, y);
    return off === null ? 0 : off;
  }

  function restoreScroll(offset) {
    var len = (state.textCtx && state.textCtx.text.length) || 0;
    var ratio = len > 0 ? clamp(offset / len, 0, 1) : 0;
    window.scrollTo(0, ratio * Math.max(1, document.body.scrollHeight - window.innerHeight));
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
    var col = Math.max(0, Math.round((rect.left - er.left - m.geometry.margin) / m.advance));
    var page = Math.floor(col / m.step);
    return gotoPage(page);
  }

  function report() {
    if (!state.paged) return;
    var m = measure();
    var off = currentOffset();
    try {
      AnkeReaderBridge.pageChanged(m.current, m.total, off);
      AnkeReaderBridge.saveProgress(state.chapterIndex, off, true);
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
      // 纵向超高或横向超宽的表格都包进滚动容器，避免内容溢出到相邻列
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

  function bindImages() {
    var onImgChange = function () {
      if (state.paged) onResize();
    };
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      imgs[i].addEventListener('load', onImgChange);
      imgs[i].addEventListener('error', onImgChange);
    }
  }

  /* ---------------- Kotlin 下发接口 ---------------- */
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

  /** 动态注入内置字体 @font-face 并异步加载（不阻塞页面加载）。 */
  function loadReaderFont() {
    if (window.__readerFontLoaded__) return;
    window.__readerFontLoaded__ = true;
    var style = document.createElement('style');
    style.textContent =
      '@font-face{font-family:"LXGW WenKai";' +
      'src:url("../fonts/LXGWWenKai-Regular.ttf") format("truetype");' +
      'font-weight:400;font-display:swap;}';
    document.head.appendChild(style);
    if (document.fonts && document.fonts.load) {
      document.fonts.load('16px "LXGW WenKai"').then(function () {
        log('font ready ms=' + Math.round(performance.now()));
        requestAnimationFrame(function () {
          onResize();
        });
      }).catch(function () { /* 字体加载失败时保持系统字体 */ });
    }
  }

  function setMode(paged) {
    if (!document.body) return;
    var el = scrollEl();
    var wasScrolled = !!el && (state.paged ? el.scrollLeft > 1 : window.scrollY > 1);
    var offset = currentOffset();
    state.paged = !!paged && !state.huge;
    if (paged && state.huge) {
      log('超大章回退滚动模式（> ' + MAX_PAGED_TEXT + ' 字符）');
    }
    document.body.classList.toggle('paged', state.paged);
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
        if (offset > 0) {
          restoreScroll(offset);
        } else if (wasScrolled) {
          var ratio = window.scrollY / Math.max(1, document.body.scrollHeight - window.innerHeight);
          window.scrollTo(0, ratio * Math.max(1, document.body.scrollHeight - window.innerHeight));
        }
      }
      report();
    });
  }

  var resizeTimer = null;
  var resizeOffset = 0;
  var resizeScrolled = false;
  var savedPosOffset = 0;
  var positionMarked = false;

  /** 系统栏显示/隐藏前由 Kotlin 调用：标记当前阅读位置。 */
  function markPosition() {
    savedPosOffset = currentOffset();
    positionMarked = true;
  }

  /** 系统栏动画结束后由 Kotlin 调用：按标记位置恢复，绝不跳回第一页。 */
  function restorePosition() {
    if (savedPosOffset > 0) {
      prepare();
      normalizeTallTables();
      gotoOffset(savedPosOffset);
      report();
    }
    savedPosOffset = 0;
    positionMarked = false;
  }

  function onResize() {
    if (!state.paged) return;
    var el = scrollEl();
    var wasScrolled = !!el && el.scrollLeft > 1;
    var offset = currentOffset();
    // 系统栏动画中 scrollLeft 可能被浏览器临时重置，导致采样 offset=0；
    // 只保留首个有效 offset（动画中后续采样会随 padding 变化失真），
    // 动画结束后用它恢复，避免跳回第一页。
    if (offset > 0 && resizeOffset === 0) resizeOffset = offset;
    if (wasScrolled) resizeScrolled = true;
    // 系统栏显示/隐藏动画期间会连续触发多次 resize/insets 变化，
    // 合并到最后一次再统一 prepare + 恢复，避免逐帧布局变化把滚动位置重置。
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      prepare();
      normalizeTallTables();
      if (!positionMarked) {
        // 系统栏动画期间位置恢复统一由 restorePosition 完成，这里只重排。
        if (resizeOffset > 0) {
          gotoOffset(resizeOffset);
        } else if (resizeScrolled && el) {
          // 采样失败兜底：按当前滚动比例粗恢复，绝不跳回第一页。
          var len = (state.textCtx && state.textCtx.text.length) || 0;
          if (len > 0) {
            var ratio = el.scrollLeft / Math.max(1, el.scrollWidth - el.clientWidth);
            gotoOffset(Math.round(ratio * len));
          }
        }
      }
      resizeOffset = 0;
      resizeScrolled = false;
      report();
    }, 300);
  }

  /** 系统栏/挖孔安全区变化时更新正文上下留白（Kotlin 侧调用）。 */
  function setInsets(top, bottom) {
    state.topInset = Math.max(0, top || 0);
    state.bottomInset = Math.max(0, bottom || 0);
    var root = document.documentElement;
    root.style.setProperty('--reader-top-inset', state.topInset + 'px');
    root.style.setProperty('--reader-bottom-inset', state.bottomInset + 'px');
    if (state.paged) {
      onResize();
    }
  }

  function refresh() {
    if (!state.paged) return;
    prepare();
    requestAnimationFrame(function () {
      normalizeTallTables();
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
    if (!document.body) return;
    state.textCtx = TextPos.build(document);
    state.huge = state.textCtx.text.length > MAX_PAGED_TEXT;
    state.paged = !!opts.paged && !state.huge;
    log('init paged=' + state.paged + ' huge=' + state.huge +
        ' len=' + state.textCtx.text.length + ' offset=' + (opts.offset || 0) +
        ' insets=' + state.topInset + '/' + state.bottomInset +
        ' ms=' + Math.round(performance.now()));
    if (opts.paged && state.huge) {
      log('超大章回退滚动模式（> ' + MAX_PAGED_TEXT + ' 字符）');
    }
    document.body.classList.toggle('paged', state.paged);
    // 滚动模式底部换章按钮（分页模式下由 CSS 隐藏）。
    var prevBtn = document.getElementById('android-prev-chapter');
    var nextBtn = document.getElementById('android-next-chapter');
    if (prevBtn) {
      prevBtn.addEventListener('click', function () {
        try { AnkeReaderBridge.requestChapter(-1); } catch (e) { /* ignore */ }
      });
    }
    if (nextBtn) {
      nextBtn.addEventListener('click', function () {
        try { AnkeReaderBridge.requestChapter(1); } catch (e) { /* ignore */ }
      });
    }
    if (opts.theme) applyTheme(opts.theme);
    applyTypography({ fontSize: state.fontSize, lineHeight: state.lineHeight });
    loadReaderFont();
    var root = document.documentElement;
    root.style.setProperty('--reader-top-inset', state.topInset + 'px');
    root.style.setProperty('--reader-bottom-inset', state.bottomInset + 'px');
    bindImages();
    // 在 JS 层直接拦截链接点击，避免 WebView 产生导航尝试
    // （shouldOverrideUrlLoading 虽拦截，但导航尝试可能重置阅读状态）。
    document.addEventListener('click', function (e) {
      var t = e.target;
      var a = t && t.closest ? t.closest('a[href]') : null;
      if (a) {
        e.preventDefault();
        e.stopPropagation();
      }
    }, true);
    // WebView 首帧尺寸可能尚未稳定（高度为 0/极小），尺寸变化后需重排保位。
    window.addEventListener('resize', function () {
      onResize();
    });
    var finish = function () {
      if (state.paged) {
        prepare();
        normalizeTallTables();
        if (opts.offset > 0) gotoOffset(opts.offset);
        else gotoPage(firstContentPage());
      } else {
        if (opts.offset > 0) restoreScroll(opts.offset);
      }
      report();
    };
    // 首屏不等待字体（26MB LXGW 动态异步加载）：立即用系统字体布局并恢复位置。
    requestAnimationFrame(finish);
    // WebView 首帧高度可能不稳定，延时重排兜底（稳定后 report 幂等）。
    setTimeout(refresh, 150);
    setTimeout(refresh, 600);
  }

  window.AnkeReader = {
    init: init,
    applyTheme: applyTheme,
    applyTypography: applyTypography,
    setMode: setMode,
    gotoOffset: gotoOffset,
    flipPage: flipPage,
    currentOffset: currentOffset,
    onResize: onResize,
    refresh: refresh,
    setInsets: setInsets,
    markPosition: markPosition,
    restorePosition: restorePosition,
    measure: measure,
    isPaged: function () { return state.paged; },
    isHuge: function () { return state.huge; },
  };
})();
