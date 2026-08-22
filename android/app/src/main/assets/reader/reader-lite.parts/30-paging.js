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
    // 翻页只采样一次：页顶 offset 同时供落盘（report）与翻页锚点复用。
    var o = currentOffsetSafe();
    report(true, o);
    // 分页模式没有滚动事件，翻页时刷新骨碌碌上下文（当前楼/视效/背景/自动音乐）
    reportGululuContext();
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
    var off = offsetAtPoint(ctx, x, y);
    return off === null ? 0 : off;
  }

  // 滚动模式采样：视口中线（45%），语义 = 当前阅读行，与 restoreScrollOffset 严格对应。
  function currentOffsetScroll() {
    var ctx = state.textCtx;
    if (!ctx) return 0;
    var x = Math.max(2, Math.min(window.innerWidth / 2, window.innerWidth - 2));
    var y = sampleOffsetY();
    var off = offsetAtPoint(ctx, x, y);
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

  // 采样点向下扫描到页底（覆盖“采样点是图片、下方还有文本”的场景；
  // 分页页顶与滚动中线共用同一扫描策略）。
  function offsetAtPoint(ctx, x, y) {
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
      } catch (e) {
        // 文本锚点定位失败（Range 几何异常等）降级为线性比例滚动；
        // 图片密集章可差数页，必须留诊断痕迹（进度一致性红线）。
        try { log('[restore:fallback-ratio] anchor failed off=' + offset + ' err=' + (e && e.message)); } catch (ignored) { /* ignore */ }
      }
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
  // offset：翻页路径由 flipPage 采样一次后传入复用；doSave=false 的纯 UI
  // 上报根本不采样（此前 setMode/resize/settle 每次都白付一次页顶扫描）。
  function report(doSave, offset) {
    if (!state.paged) return;
    var m = measure();
    var off = doSave ? (typeof offset === 'number' ? offset : currentOffset()) : 0;
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

