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

