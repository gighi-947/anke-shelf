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
   * 章节首次建坐标：先做代码块高亮，再注入宿主带来的本章高亮，最后建坐标
   * （与桌面 reader.js「代码高亮 + 标注注入后重建坐标」同顺序）。
   * 两类注入元素（.syntax / .hl-mark）按折叠规则内部无缝，text_offset 不变。
   */
  function buildTextWithHighlights(payload) {
    highlightCodeBlocks();
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

