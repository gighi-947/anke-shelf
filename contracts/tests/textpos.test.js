// 文本规范化契约 —— Windows JS 折叠核心（真实加载 web/js/textpos.js 的 foldItems）。
// 运行：node contracts/tests/textpos.test.js（无需 npm，Windows CI 直接执行）。
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { foldItems } = require('../../web/js/textpos.js');

function fold(texts) {
  const items = texts.map((t) =>
    typeof t === 'string' ? { text: t, isInj: false } : t,
  );
  return foldItems(items);
}

// ---- 折叠语义（与 TEXT_NORMALIZATION_SPEC 一致） ----

assert.strictEqual(fold(['Hello ', 'World']).text, 'Hello World', '相邻文本块间一个空格');
assert.strictEqual(
  fold([{ text: 'x', isInj: true }, { text: 'y', isInj: true }]).text,
  'xy',
  '注入节点链内部无缝',
);
assert.strictEqual(
  fold([{ text: 'x', isInj: true }, { text: 'y', isInj: false }]).text,
  'x y',
  '注入链与外部节点间保留分隔空格',
);
assert.strictEqual(fold(['  a\n\t b  ']).text, 'a b', '\\s+ 折叠 + trim');

const collapsed = fold(['  ab']);
assert.strictEqual(collapsed.text, 'ab');
assert.strictEqual(collapsed.mapRaw[2], 0, '首字符映射到 plain 0');
assert.strictEqual(collapsed.ranges.length, 1);

// ---- 已知分歧：星形字符按 UTF-16 code unit 计数（canonical=2，Python 码点） ----
const astral = fold(['a👋b']);
assert.strictEqual(astral.text, 'a👋b');
assert.strictEqual(astral.text.indexOf('b'), 3, 'JS 按 UTF-16，emoji 占 2 个 code unit');

// ---- 契约用例结构自洽性（B1 先暴露漂移，B2 统一） ----
const casesPath = path.join(__dirname, '..', 'text', 'text-cases.json');
const cases = JSON.parse(fs.readFileSync(casesPath, 'utf8')).cases;
assert.ok(Array.isArray(cases) && cases.length >= 15, 'text-cases.json 用例完整');

for (const c of cases) {
  assert.ok(c.id && typeof c.html === 'string' && typeof c.expected === 'string', c.id);
  assert.strictEqual(c.expected, c.expected.trim(), `${c.id}: expected 已 trim`);
  assert.ok(!/\s{2,}/.test(c.expected), `${c.id}: expected 无连续空白`);
  for (const p of c.points || []) {
    if (c.id === 'astral') continue; // 已知分歧：JS 按 UTF-16，单独断言
    assert.strictEqual(
      c.expected.indexOf(p.quote),
      p.offset,
      `${c.id}: quote "${p.quote}" 应在 canonical offset ${p.offset}`,
    );
  }
}

const astralCase = cases.find((c) => c.id === 'astral');
assert.strictEqual(
  astralCase.expected.indexOf(astralCase.points[0].quote),
  3,
  'astral: JS 按 UTF-16，emoji 后 quote 的 offset=3（canonical=2，B2 统一）',
);

console.log(`textpos contract OK: ${cases.length} cases`);
