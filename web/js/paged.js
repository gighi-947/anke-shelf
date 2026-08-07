/**
 * 分页渲染核心 —— CSS multi-column（foliate 同款思路）。
 *
 * 布局：iframe 固定为舞台尺寸；章节文档 body 应用
 *   height:100%; overflow:hidden; padding:0 var(--margin-px);
 *   column-width:var(--col-px); column-gap:var(--gap-px); column-fill:auto;
 * 内容纵向排满一页后右溢成新列，body.scrollLeft 每 `advance`(=colW+gap) 一页。
 *
 * 进度定位：统一 text_offset（TextPos 坐标系）。上报取当前页首可见文本，
 * 恢复按 offset 定位到所在列。
 */
(function () {
  'use strict';

  const frameEl = () => document.getElementById('chapter-frame');
  const stageEl = () => document.getElementById('reader-root');

  function isActive() {
    return !!(App.state.settings && App.state.settings.pagination && !window.__readerHugeChapter__);
  }

  /** 双页（spread）：分页模式下每屏并排两页，翻页按整页跨（一跨 = 两列）。
   *
   * 参考 flow/epub.js 的 Auto spread：
   * - 强制双页（dual_page）：任何宽高都双页；
   * - 自动（auto_dual，默认）：横向宽窗（≥800px 且宽>高）自动双页，窄窗单页；
   * - 单页（auto_dual=false）：始终单页。
   */
  function isDual() {
    if (!isActive()) return false;
    const s = App.state.settings || {};
    if (s.dual_page) return true;
    if (s.auto_dual === false) return false;
    const { w, h } = stageSize();
    return w >= 800 && w > h;
  }

  /** 一次翻页跨越的列数：双页模式为 2，普通分页为 1。 */
  function step() {
    return isDual() ? 2 : 1;
  }

  /** 舞台尺寸（分页用）。 */
  function stageSize() {
    const el = stageEl();
    return { w: el.clientWidth, h: el.clientHeight };
  }

  /** 分页布局准备：iframe 尺寸 + body 分页 CSS（变量注入 iframe html）。 */
  function prepare(doc) {
    if (!doc || !doc.documentElement) return { advance: 0 };
    const { w, h } = stageSize();
    const frame = frameEl();
    // iframe 必须按 chapter-wrap 的内容盒（去掉内边距）取尺寸：
    // 之前直接取舞台尺寸，iframe 会比可视区域大（左右各 32px、上下 44/64px），
    // 导致分页内容整体错位/被裁切，切换翻页模式时还会出现横向滚动条。
    let fw = w;
    let fh = h;
    const wrap = frame.closest('.chapter-wrap');
    if (wrap) {
      const cs = getComputedStyle(wrap);
      const pl = parseFloat(cs.paddingLeft) || 0;
      const pr = parseFloat(cs.paddingRight) || 0;
      const pt = parseFloat(cs.paddingTop) || 0;
      const pb = parseFloat(cs.paddingBottom) || 0;
      fw = Math.max(80, w - pl - pr);
      fh = Math.max(80, h - pt - pb);
    }
    const M = App.state.settings.margin_px || 40;
    const G = App.state.settings.gap_px || 28;
    const colW = isDual()
      ? Math.max(100, Math.floor((fw - 2 * M - G) / 2))
      : Math.max(100, Math.floor(fw - 2 * M));

    // CSS 变量不跨 document → 直接写 iframe html 元素样式
    const html = doc.documentElement;
    html.style.setProperty('--margin-px', M + 'px');
    html.style.setProperty('--gap-px', G + 'px');
    html.style.setProperty('--col-px', colW + 'px');
    // 双页模式补一个占位空列（epub.js forceEvenPages 思路）：内容列数为奇数时，
    // 末列与占位列组成最后一跨，保证最后一跨可滚动到达，不会出现拼接错乱/漏页。
    let spacer = doc.getElementById('__dual_spacer__');
    if (isDual()) {
      if (!spacer) {
        spacer = doc.createElement('div');
        spacer.id = '__dual_spacer__';
        spacer.setAttribute('aria-hidden', 'true');
        spacer.textContent = '\u200b';
        doc.body.appendChild(spacer);
      }
      spacer.style.cssText =
        'height:1px; overflow:hidden; break-before:column; ' +
        'column-break-before:always; visibility:hidden;';
    } else if (spacer) {
      spacer.remove();
    }

    // iframe 固定为内容盒尺寸（与滚动模式下的正文宽度/顶底留白一致）
    frame.style.width = fw + 'px';
    frame.style.height = fh + 'px';
    // chapter-wrap 占满（分页模式下正文 padding 由 body 控制）
    if (wrap) wrap.style.maxWidth = 'none';

    return { advance: colW + G, step: step() };
  }

  /** NGA 楼层里的长表格常超过一页高度且无法跨列拆分（rowspan/colspan 使表格整体化），
   *  会把页面纵向撑出界、后续内容错位。把超高表格包进限高滚动容器（保留表格布局）。 */
  function normalizeTallTables(doc) {
    if (!doc || !doc.body) return;
    const cs = getComputedStyle(doc.body);
    const pt = parseFloat(cs.paddingTop) || 0;
    const pb = parseFloat(cs.paddingBottom) || 0;
    const pageH = Math.max(1, doc.body.clientHeight - pt - pb);
    if (pageH <= 0) return;
    const maxH = Math.max(120, pageH - 8);
    doc.querySelectorAll('table').forEach((t) => {
      const parent = t.parentNode;
      if (parent && parent.classList && parent.classList.contains('nga-table-scroll')) return;
      // 用内容高度而非 getBoundingClientRect：分页裁切下 rect 高度可能只反映
      // 当前可见分片，而 scrollHeight 才是表格完整内容高度。
      if (t.scrollHeight <= pageH + 2) return;
      const wrap = doc.createElement('div');
      wrap.className = 'nga-table-scroll';
      wrap.style.maxHeight = maxH + 'px';
      wrap.style.overflow = 'auto';
      wrap.style.margin = '6px 0';
      t.parentNode.insertBefore(wrap, t);
      wrap.appendChild(t);
    });
    // 窗口/字号变化后同步已有容器高度
    doc.querySelectorAll('.nga-table-scroll').forEach((w) => {
      w.style.maxHeight = maxH + 'px';
    });
  }

  /** 当前页/总页数（双页模式下“页”指一屏整页跨）。 */
  function measure() {
    const doc = frameEl().contentDocument;
    if (!doc || !doc.body) return { advance: 0, total: 1, current: 0, step: 1 };
    const cs = getComputedStyle(doc.body);
    const M = parseFloat(cs.paddingLeft) || 0;
    const colW = parseFloat(cs.columnWidth) || 0;
    const gap = parseFloat(cs.columnGap) || 0;
    const advance = colW + gap;
    if (advance <= 0) return { advance: 0, total: 1, current: 0, step: 1 };
    // border-box + 左右 padding：总宽 = 2M + n*colW + (n-1)*gap
    // （与 epub.js Layout.calculate 的列几何一致，不再用 clientWidth 近似）
    const hasSpacer = !!doc.getElementById('__dual_spacer__');
    let cols = Math.max(1, Math.round((doc.body.scrollWidth - 2 * M + gap) / advance));
    if (hasSpacer) cols = Math.max(1, cols - 1);  // 扣除补偶占位列
    const st = step();
    let total = Math.max(1, Math.ceil(cols / st));
    let current = Math.round(doc.body.scrollLeft / (st * advance));
    current = Math.max(0, Math.min(total - 1, current));
    return { advance, total, current, step: st };
  }

  function gotoPage(n) {
    const doc = frameEl().contentDocument;
    if (!doc || !doc.body) return 0;
    const m = measure();
    const page = Math.max(0, Math.min(m.total - 1, n));
    doc.body.scrollLeft = page * m.step * m.advance;
    return page;
  }

  /** 判断某页是否为空白/大面积空白页（多数采样行无可视内容）。 */
  function isPageBlank(page) {
    const doc = frameEl().contentDocument;
    if (!doc || !doc.body) return false;
    const m = measure();
    if (!m.advance) return false;
    doc.body.scrollLeft = page * m.step * m.advance;
    const w = doc.body.clientWidth;
    const h = doc.body.clientHeight;
    if (w <= 0 || h <= 0) return false;
    // elementFromPoint 在 overflow:hidden 的横向滚动多栏 body 上不随 scrollLeft
    // 命中（会返回 BODY），改用布局命中的 caretRangeFromPoint 判定该点是否有文本。
    const hitAt = (x, y) => {
      try {
        const r = doc.caretRangeFromPoint(x, y);
        if (r && r.startContainer) {
          const n = r.startContainer;
          const data = n.nodeType === 3 ? n.data : (n.textContent || '');
          if (data && data.trim()) return true;
        }
      } catch (e) { /* ignore */ }
      const el = doc.elementFromPoint(x, y);
      if (!el || el === doc.body || el === doc.documentElement) return false;
      if (el.closest && el.closest('img,video,audio,svg,canvas,picture')) return true;
      const walker = doc.createTreeWalker(el, NodeFilter.SHOW_TEXT);
      let n;
      while ((n = walker.nextNode())) {
        if (n.data && n.data.trim()) return true;
      }
      return false;
    };
    // 双页模式左右两页各采样两列，避免采样点落在中央书缝；
    // 纵向上覆盖到顶部标题区，避免“只有楼层标题、正文被抽楼”的页面被误判为空白。
    let hits = 0;
    let samples = 0;
    let topHits = 0;
    const xs = m.step === 2 ? [0.15, 0.35, 0.65, 0.85] : [0.25, 0.5, 0.75];
    for (const fy of [0.06, 0.2, 0.4, 0.6, 0.8]) {
      for (const fx of xs) {
        samples++;
        if (hitAt(Math.max(2, Math.min(w - 2, w * fx)), Math.round(h * fy))) {
          hits++;
          if (fy === 0.06) topHits++;
        }
      }
    }
    // 顶部标题区有内容（如 NGA 抽楼页只剩楼层头）不算空白
    if (topHits > 0) return false;
    // 少于 25% 的采样点有内容 → 判定为大面积空白页
    return hits / samples < 0.25;
  }

  /** 从 page 起沿 dir（1/-1）跳过连续空白页，最多跳 5 页。 */
  function skipToContent(page, dir) {
    const m = measure();
    let p = Math.max(0, Math.min(m.total - 1, page));
    let guard = 0;
    while (guard < 5 && isPageBlank(p) && p > 0 && p < m.total - 1) {
      p += dir;
      guard++;
    }
    return p;
  }

  /** 章节加载时使用：若首页（或开头几页）空白，自动跳到首个有内容页。 */
  function firstContentPage() {
    const m = measure();
    let p = 0;
    let guard = 0;
    while (guard < 5 && isPageBlank(p) && p < m.total - 1) {
      p++;
      guard++;
    }
    return p;
  }

  /** 翻页。across=true 时章末/章首跨章。 */
  function nextPage(across) {
    const m = measure();
    if (m.current >= m.total - 1) {
      if (across) Reader.nextChapter();
      return;
    }
    gotoPage(skipToContent(m.current + 1, 1));
    Reader.onPageTurned();
  }

  function prevPage(across) {
    const m = measure();
    if (m.current <= 0) {
      if (across) Reader.prevChapter();
      return;
    }
    gotoPage(skipToContent(m.current - 1, -1));
    Reader.onPageTurned();
  }

  /** 分页定位：text_offset → 所在列。 */
  function gotoOffset(offset) {
    const doc = frameEl().contentDocument;
    const ctx = App.state.textCtx;
    if (!doc || !ctx) return 0;
    const point = TextPos.plainToPoint(ctx, offset);
    if (!point) return 0;
    const range = doc.createRange();
    range.setStart(point.node, point.charIndex);
    range.collapse(true);
    const rect = range.getBoundingClientRect();
    const cs = getComputedStyle(doc.body);
    const M = parseFloat(cs.paddingLeft) || 0;
    const colW = parseFloat(cs.columnWidth) || 0;
    const gap = parseFloat(cs.columnGap) || 0;
    const advance = colW + gap;
    if (advance <= 0) return 0;
    const st = step();
    const col = Math.max(0, Math.round((rect.left - M) / advance));
    const page = Math.floor(col / st);
    doc.body.scrollLeft = page * st * advance;
    return page;
  }

  /** 当前页首可见文本 → text_offset。
   * caretRangeFromPoint 取的是视口坐标：分页 body overflow:hidden，
   * 视口始终显示当前列内容，x 取正文起始（padding 处）即可。 */
  function currentOffset() {
    const doc = frameEl().contentDocument;
    const ctx = App.state.textCtx;
    if (!doc || !ctx) return 0;
    const cs = getComputedStyle(doc.body);
    const M = parseFloat(cs.paddingLeft) || 0;
    const x = Math.max(2, Math.min(M + 2, doc.body.clientWidth - 2));
    const y = 16;
    const off = TextPos.currentOffsetFromPoint(ctx, x, y);
    return off === null ? 0 : off;
  }

  /** 重置分页（字号/主题/窗口变化后）：重测 + 按当前 offset 保位。 */
  function onResize() {
    if (!isActive()) return;
    const offset = currentOffset();
    const doc = frameEl().contentDocument;
    prepare(doc);
    // 等重排后定位
    requestAnimationFrame(() => {
      normalizeTallTables(doc);
      measure();
      if (offset > 0) gotoOffset(offset);
      Reader.updateProgressUI();
    });
  }

  /** 绑定 iframe 文档内交互（滚轮翻页、滑动）。 */
  function setupInteraction(doc) {
    if (!doc) return;
    // 表格滚动容器内交还原生滚动，不拦截为翻页
    const isScrollable = (t) => {
      let el = t && t.nodeType === 1 ? t : null;
      while (el && el !== doc.documentElement) {
        if (el.classList && el.classList.contains('nga-table-scroll')) return true;
        el = el.parentElement;
      }
      return false;
    };
    // 滚轮 → 翻页（防抖一档一页）
    let wheelTimer = null;
    const onWheel = (e) => {
      if (isScrollable(e.target)) return;
      e.preventDefault();
      if (wheelTimer) return;
      wheelTimer = setTimeout(() => { wheelTimer = null; }, 180);
      if (e.deltaX > 0 || e.deltaY > 0) nextPage(true);
      else prevPage(true);
    };
    doc.addEventListener('wheel', onWheel, { passive: false });

    // 触屏滑动
    let touchStartX = null;
    doc.addEventListener('touchstart', (e) => {
      if (isScrollable(e.target)) { touchStartX = null; return; }
      touchStartX = e.touches[0].clientX;
    }, { passive: true });
    doc.addEventListener('touchend', (e) => {
      if (touchStartX === null) return;
      const dx = e.changedTouches[0].clientX - touchStartX;
      if (Math.abs(dx) > 40) {
        if (dx < 0) nextPage(true);
        else prevPage(true);
      }
      touchStartX = null;
    }, { passive: true });
  }

  window.Paged = {
    isActive,
    isDual,
    prepare,
    measure,
    gotoPage,
    nextPage,
    prevPage,
    firstContentPage,
    isPageBlank,
    gotoOffset,
    currentOffset,
    onResize,
    setupInteraction,
    normalizeTallTables,
  };
})();
