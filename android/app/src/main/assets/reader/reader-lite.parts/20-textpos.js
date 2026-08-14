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

