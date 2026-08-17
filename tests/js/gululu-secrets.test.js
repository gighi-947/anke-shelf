// GululuSecrets 线索存储与章节监听回归（真实加载 web/js/gululu-secrets.js）。
// 运行：node tests/js/gululu-secrets.test.js（无需 npm）。
'use strict';

const assert = require('assert');

// ---- 最小浏览器桩 ----
const listeners = new Map(); // doc -> Set<handler>
function makeDoc() {
  const doc = {
    addEventListener(type, handler) {
      if (!listeners.has(this)) listeners.set(this, new Set());
      listeners.get(this).add(handler);
    },
    removeEventListener(type, handler) {
      if (listeners.has(this)) listeners.get(this).delete(handler);
    },
    querySelectorAll() {
      return [];
    },
  };
  return doc;
}

const storage = new Map();
global.localStorage = {
  getItem: (key) => (storage.has(key) ? storage.get(key) : null),
  setItem: (key, value) => storage.set(key, String(value)),
};
global.Toast = { show() {} };
global.Api = { gululuDecryptSecret: async () => ({ plaintext: 'x' }) };
global.document = {
  addEventListener() {},
  removeEventListener() {},
  getElementById() {
    return { appendChild() {} };
  },
  createElement() {
    return {};
  },
};
global.window = global;
require('../../web/js/gululu-secrets.js');
const Secrets = global.window.GululuSecrets;

function fireClueClick(doc, title, password) {
  const cue = {
    dataset: { gululuSecretTitle: title, gululuSecretPassword: password },
    closest(selector) {
      return selector === '.gululu-clue-cue' ? this : null;
    },
  };
  const event = { target: cue, preventDefault() {}, stopPropagation() {} };
  const handler = Array.from(listeners.get(doc) || [])[0];
  assert.ok(handler, '章节 click 委托应已绑定');
  handler(event);
}

// ---- 用例 ----

// 标题为 "__proto__" 时必须作为自有键保存/读取，而不是被原型 setter 吞掉。
Secrets.setBook({ gululu_source_id: 7 });
const doc1 = makeDoc();
Secrets.onChapterLoaded(doc1);
fireClueClick(doc1, '__proto__', 'p');
assert.ok(
  Secrets.snapshot().clueTitles.includes('__proto__'),
  '线索标题 "__proto__" 应被保存为自有键',
);

// 同一文档重复 onChapterLoaded 不得叠加 click 委托。
Secrets.setBook({ gululu_source_id: 8 });
const doc2 = makeDoc();
Secrets.onChapterLoaded(doc2);
Secrets.onChapterLoaded(doc2);
assert.strictEqual(
  (listeners.get(doc2) || new Set()).size,
  1,
  '同一文档的 click 委托应只绑定一次',
);

// 换书后旧文档委托应被解绑。
const doc3 = makeDoc();
Secrets.onChapterLoaded(doc3);
Secrets.setBook({ gululu_source_id: 9 });
assert.strictEqual((listeners.get(doc3) || new Set()).size, 0, '换书后旧文档委托应解绑');

console.log('gululu-secrets tests OK');
