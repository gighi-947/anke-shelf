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
      ? Math.max(100, Math.round((fw - 2 * M - G) / 2))
      : Math.max(100, Math.round(fw - 2 * M));

    // CSS 变量不跨 document → 直接写 iframe html 元素样式
    const html = doc.documentElement;
    html.style.setProperty('--margin-px', M + 'px');
    html.style.setProperty('--gap-px', G + 'px');
    html.style.setProperty('--col-px', colW + 'px');

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
    const pageH = doc.body.clientHeight;
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
    const colW = parseFloat(cs.columnWidth) || 0;
    const gap = parseFloat(cs.columnGap) || 0;
    const advance = colW + gap;
    if (advance <= 0) return { advance: 0, total: 1, current: 0, step: 1 };
    const scrollW = doc.body.scrollWidth;
    // 双页模式下首屏已容纳 2 列，列数 = 溢出列数 + 屏内列数（step），
    // 否则奇数总列数时会少算最后一屏（flow/epub.js 按 spread 计数）。
    const cols = Math.max(1, Math.floor((scrollW - doc.body.clientWidth) / advance) + step());
    const st = step();
    let total = Math.max(1, Math.ceil(cols / st));
    // 校验末列完整性（避免取整截断）
    if (doc.body.scrollWidth - ((total * st - 1) * advance + doc.body.clientWidth) > 1) total++;
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
    let hits = 0;
    let samples = 0;
    // 双页模式左右两页各采样一列，避免采样点落在中央书缝
    const xs = m.step === 2 ? [w * 0.25, w * 0.75] : [Math.max(2, w / 2)];
    for (const fy of [0.15, 0.35, 0.5, 0.65, 0.85]) {
      for (const fx of xs) {
        samples++;
        const el = doc.elementFromPoint(
          Math.max(2, Math.min(w - 2, fx)), Math.round(h * fy)
        );
        if (!el || el === doc.body || el === doc.documentElement) continue;
        if (el.closest && el.closest('img,video,audio,svg,canvas,picture')) { hits++; continue; }
        const walker = doc.createTreeWalker(el, NodeFilter.SHOW_TEXT);
        let n;
        while ((n = walker.nextNode())) { if (n.data && n.data.trim()) { hits++; break; } }
      }
    }
    // 少于 40% 的采样点有内容 → 判定为大面积空白页
    return hits / samples < 0.4;
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

  /** 绑定 iframe 文档内交互（滚轮翻页、热区、滑动）。 */
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

  // 宿主热区
  function bindHotZones() {
    const left = document.getElementById('hot-left');
    const right = document.getElementById('hot-right');
    const flash = (el) => {
      el.classList.add('pulse');
      setTimeout(() => el.classList.remove('pulse'), 150);
    };
    left.addEventListener('click', () => { if (isActive()) { flash(left); prevPage(true); } });
    right.addEventListener('click', () => { if (isActive()) { flash(right); nextPage(true); } });
  }

  document.addEventListener('DOMContentLoaded', () => bindHotZones());

  window.Paged = {
    isActive,
    isDual,
    prepare,
    measure,
    gotoPage,
    nextPage,
    prevPage,
    firstContentPage,
    gotoOffset,
    currentOffset,
    onResize,
    setupInteraction,
    normalizeTallTables,
  };
})();
