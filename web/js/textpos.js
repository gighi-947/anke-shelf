/**
 * 文本坐标系统 —— 章节内「折叠纯文本」字符偏移（text_offset）。
 *
 * 与 app/text.py 的 extract_dom_text 输出逐字符对齐（差分测试守护）：
 * 1. 顺序遍历所有文本节点（跳过 script/style）；
 * 2. 相邻文本节点之间一个分隔空格；
 * 3. `\s+` → 单个空格 折叠，trim。
 *
 * 提供：
 * - build(doc)        建立坐标上下文 {text, ranges, mapRaw}
 * - plainToPoint(ctx, offset)  偏移 → 文本节点 + 字符索引（可精确分割）
 * - rangeToOffsets(ctx, range) DOM Range → [start, end]
 * - currentOffset(ctx)         当前视口首个可见文本 → 偏移（分页上报用）
 */
(function () {
  'use strict';

  const RE_WS_CHAR = /\s/;

  function isSkipNode(node) {
    const p = node.parentElement;
    return p && (p.tagName === 'SCRIPT' || p.tagName === 'STYLE');
  }

  /**
   * 注入元素（高亮 mark / 代码高亮 span）内的文本节点：这些是显示层注入，
   * 不应产生「相邻文本节点边界空格」，否则 text_offset 会与 Python 端
   * （基于原始 HTML）漂移。识别：.hl-mark 或 .syntax 祖先。
   */
  function isInjectedText(node) {
    let el = node.parentElement;
    while (el) {
      if (el.classList && (el.classList.contains('hl-mark') || el.classList.contains('syntax'))) {
        return true;
      }
      el = el.parentElement;
    }
    return false;
  }

  /** 单遍构建坐标上下文。 */
  function build(doc) {
    const items = [];
    const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT, {
      acceptNode(n) {
        return isSkipNode(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      },
    });
    let node;
    while ((node = walker.nextNode())) {
      items.push({ node, text: node.data, isInj: isInjectedText(node) });
    }

    const folded = foldItems(items);
    return {
      doc,
      text: folded.text,
      ranges: folded.ranges,
      mapRaw: folded.mapRaw,
      buildCount: (buildCount + 1),
    };
  }

  /**
   * 纯函数：文本项数组 → 折叠纯文本与坐标映射（供 build() 与 Node 契约单测共用）。
   * items: [{ text, isInj }]；与 Python app/text.py 的折叠规则一致
   * （相邻文本块间一个空格、`\s+` → 单空格、trim）。
   * 注入节点链（.hl-mark / .syntax）内部无缝，与外部节点间保留分隔空格。
   */
  function foldItems(items) {
    let raw = '';
    let sawPrev = false;
    let lastWasInj = false;
    for (const it of items) {
      if (sawPrev && !(it.isInj && lastWasInj)) {
        raw += ' ';
      }
      sawPrev = true;
      lastWasInj = !!it.isInj;
      it.rawStart = raw.length;
      raw += it.text;
      it.rawEnd = raw.length;
    }

    const text = raw.replace(/\s+/g, ' ').trim();
    const mapRaw = new Int32Array(raw.length);
    let tIdx = 0;
    let prevSpace = false;
    let i = 0;
    while (i < raw.length && RE_WS_CHAR.test(raw[i])) { mapRaw[i] = 0; i++; }
    for (; i < raw.length; i++) {
      if (RE_WS_CHAR.test(raw[i])) {
        if (prevSpace) { mapRaw[i] = tIdx - 1; }
        else { mapRaw[i] = tIdx; tIdx++; prevSpace = true; }
      } else {
        mapRaw[i] = tIdx; tIdx++; prevSpace = false;
      }
    }

    const ranges = [];
    for (const it of items) {
      if (it.rawEnd <= it.rawStart) continue;
      ranges.push({
        node: it.node,
        start: mapRaw[it.rawStart],
        end: mapRaw[it.rawEnd - 1] + 1,
        rawStart: it.rawStart,
      });
    }

    return { raw, text, mapRaw, ranges };
  }

  let buildCount = 0;

  /** 节点内字符(raw 索引) → plain 偏移。 */
  function nodeCharToPlain(ctx, range, charIndex) {
    return ctx.mapRaw[range.rawStart + charIndex] | 0;
  }

  /** plain 偏移 → {node, charIndex}：定位到映射到该偏移的第一个字符。 */
  function plainToPoint(ctx, offset) {
    const { ranges } = ctx;
    if (!ranges.length) return null;
    // 二分：最大的 start <= offset 的 range
    let lo = 0, hi = ranges.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (ranges[mid].start <= offset) lo = mid + 1;
      else hi = mid;
    }
    let idx = lo - 1;
    if (idx < 0) idx = 0;
    if (idx >= ranges.length) idx = ranges.length - 1;
    const r = ranges[idx];
    const inPlain = offset - r.start;          // 节点内 plain 偏移
    const node = r.node;
    const data = node.data;
    // 在节点内找第一个映射到 inPlain 的字符
    let ci = 0;
    let plainPos = r.start;
    for (let k = 0; k < data.length; k++) {
      const p = nodeCharToPlain(ctx, r, k);
      if (p >= r.start + inPlain) { ci = k; plainPos = p; break; }
      ci = k + 1;
    }
    if (ci > data.length) ci = data.length;
    return { node, charIndex: ci };
  }

  /** DOM Range → [start, end]（plain 坐标）。无法映射返回 null。 */
  function rangeToOffsets(ctx, range) {
    const s = pointToOffset(ctx, range.startContainer, range.startOffset, false);
    const e = pointToOffset(ctx, range.endContainer, range.endOffset, true);
    if (s === null || e === null) return null;
    return [Math.min(s, e), Math.max(s, e)];
  }

  function pointToOffset(ctx, container, offset, isEnd) {
    // 容器为文本节点
    if (container.nodeType === Node.TEXT_NODE) {
      if (isSkipNode(container)) return null;
      const idx = rangesIndex(ctx, container);
      if (idx === -1) return null;
      const r = ctx.ranges[idx];
      const ci = Math.max(0, Math.min(offset, container.data.length));
      // 取字符位置：若 offset 在节点中间，映射到该字符；端点则取边界
      if (ci >= container.data.length) return r.end;
      return nodeCharToPlain(ctx, r, ci);
    }
    // 容器为元素：找该点相邻的第一个/最后一个文本节点
    if (container.nodeType === Node.ELEMENT_NODE) {
      const walker = ctx.doc.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
        acceptNode(n) {
          return isSkipNode(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
        },
      });
      const texts = [];
      let n;
      while ((n = walker.nextNode())) texts.push(n);
      if (!texts.length) return null;
      if (isEnd) {
        const idx = rangesIndex(ctx, texts[texts.length - 1]);
        if (idx === -1) return null;
        return ctx.ranges[idx].end;
      }
      const idx = rangesIndex(ctx, texts[0]);
      if (idx === -1) return null;
      return ctx.ranges[idx].start;
    }
    return null;
  }

  function rangesIndex(ctx, node) {
    for (let i = 0; i < ctx.ranges.length; i++) {
      if (ctx.ranges[i].node === node) return i;
    }
    return -1;
  }

  /** 当前视口首个可见文本字符 → plain 偏移（分页上报用；需在 iframe doc 内调用）。 */
  function currentOffsetFromPoint(ctx, x, y) {
    try {
      const range = ctx.doc.caretRangeFromPoint(x, y);
      if (!range) return null;
      return pointToOffset(ctx, range.startContainer, range.startOffset, false);
    } catch (e) {
      return null;
    }
  }

  if (typeof window !== 'undefined') {
    window.TextPos = {
      build,
      plainToPoint,
      rangeToOffsets,
      currentOffsetFromPoint,
    };
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { foldItems };
  }
})();
