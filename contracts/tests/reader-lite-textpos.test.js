// 跨端折叠规则 golden 对照：Windows web/js/textpos.js 与 Android reader-lite.js
// 的 foldItems 必须逐字符、逐坐标一致（注入节点无缝 / 注释不分隔 / \s+ 折叠 / trim）。
// 运行：node contracts/tests/reader-lite-textpos.test.js
'use strict';

const assert = require('assert');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const win = require(path.join(root, 'web', 'js', 'textpos.js'));
const android = require(
  path.join(root, 'android', 'app', 'src', 'main', 'assets', 'reader', 'reader-lite.js'),
);

assert.strictEqual(typeof win.foldItems, 'function', 'Windows foldItems 可加载');
assert.strictEqual(typeof android.foldItems, 'function', 'Android foldItems 可加载');

// 每个用例是一组文本项（模拟 DOM 文本节点序列）。
// isInj = 显示层注入节点（.hl-mark / .syntax）；noSep = 仅由注释分隔。
const CASES = [
  { id: 'plain-two-nodes', items: [{ text: 'Hello ' }, { text: 'World' }] },
  { id: 'injected-chain', items: [{ text: 'x', isInj: true }, { text: 'y', isInj: true }] },
  { id: 'injected-boundary', items: [{ text: 'x', isInj: true }, { text: 'y' }] },
  { id: 'injected-mixed', items: [{ text: '前' }, { text: '高亮', isInj: true }, { text: '亮', isInj: true }, { text: '后' }] },
  { id: 'comment-only-sep', items: [{ text: 'a', noSep: true }, { text: 'b', noSep: true }] },
  { id: 'collapse-and-trim', items: [{ text: '  a\n\t b  ' }] },
  { id: 'leading-space', items: [{ text: '  ab' }] },
  { id: 'astral', items: [{ text: 'a👋b' }] },
  { id: 'empty-node', items: [{ text: '甲' }, { text: '' }, { text: '乙' }] },
  { id: 'all-whitespace', items: [{ text: '   ' }, { text: '\n\n' }] },
  { id: 'cjk-floor', items: [{ text: '第 1 楼' }, { text: '正文内容' }, { text: '  尾部空白  ' }] },
  {
    id: 'highlight-inside-paragraph',
    items: [
      { text: '这是' },
      { text: '被高亮的', isInj: true },
      { text: '文字' },
      { text: '。' },
    ],
  },
];

function clone(items) {
  return items.map((it) => ({
    text: it.text,
    isInj: !!it.isInj,
    noSep: !!it.noSep,
  }));
}

for (const c of CASES) {
  const a = win.foldItems(clone(c.items));
  const b = android.foldItems(clone(c.items));
  assert.strictEqual(b.text, a.text, `${c.id}: text 一致`);
  assert.strictEqual(b.raw, a.raw, `${c.id}: raw 一致`);
  assert.deepStrictEqual(
    Array.from(b.mapRaw),
    Array.from(a.mapRaw),
    `${c.id}: mapRaw 一致`,
  );
  assert.deepStrictEqual(
    b.ranges.map((r) => [r.start, r.end, r.rawStart]),
    a.ranges.map((r) => [r.start, r.end, r.rawStart]),
    `${c.id}: ranges 一致`,
  );
}

// 注入节点规则的语义断言（防止任何一端"顺手删掉" isInj/noSep 分支）。
const injected = android.foldItems(clone([{ text: 'x', isInj: true }, { text: 'y', isInj: true }]));
assert.strictEqual(injected.text, 'xy', '注入节点链内部无缝（高亮不得移动 text_offset）');
const commented = android.foldItems(clone([{ text: 'a', noSep: true }, { text: 'b', noSep: true }]));
assert.strictEqual(commented.text, 'ab', '注释分隔的相邻文本节点不插分隔');

// Android 桥能力必须声明 annotation（宿主据此启用标注交互）。
const ready = android.AnkeReader.bridgeReadyPayload();
assert.ok(ready.capabilities.includes('annotation'), '桥能力包含 annotation');
assert.strictEqual(ready.bridgeVersion, 1, '桥版本保持 1（能力为追加式扩展）');

console.log(`reader-lite textpos cross-end OK: ${CASES.length} cases`);
