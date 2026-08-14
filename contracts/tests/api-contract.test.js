// API 契约对照：加载 web/js/api-client.js 的 METHODS，与 Python app/api 清单双向比对。
// 运行：node contracts/tests/api-contract.test.js
//   （Python 可用系统 python；本地可设 PYTHON=<绝对路径> 指定。）
'use strict';

const assert = require('assert');
const { spawnSync } = require('child_process');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const { METHODS } = require('../../web/js/api-client.js');
const jsNames = new Set(METHODS.map(([snake]) => snake));

const python = process.env.PYTHON || 'python';
const script =
  'import json; from app.api import api_manifest; print(json.dumps(api_manifest()))';
const res = spawnSync(python, ['-c', script], { cwd: ROOT, encoding: 'utf8' });
if (res.status !== 0) {
  console.error((res.stderr || res.stdout || '').trim());
  process.exit(1);
}

const manifest = JSON.parse(res.stdout.trim());
const pyNames = new Set(manifest.map((m) => m.name));
const missingInJs = [...pyNames].filter((n) => !jsNames.has(n));
const missingInPy = [...jsNames].filter((n) => !pyNames.has(n));

assert.deepStrictEqual(
  missingInJs,
  [],
  `JS api-client.js 缺少后端方法：${missingInJs.join(', ')}`,
);
assert.deepStrictEqual(
  missingInPy,
  [],
  `JS api-client.js 多出未知方法：${missingInPy.join(', ')}`,
);
assert.strictEqual(pyNames.size, jsNames.size, '两端方法数量不一致');

console.log(`api contract OK: ${pyNames.size} methods（Python ↔ JS 一致）`);
