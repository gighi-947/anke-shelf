// reader-lite.js 模块化守卫：parts 拼接必须与现役文件字节级一致（防拆分漂移）。
// 运行：node contracts/tests/reader-lite-parts.test.js
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const readerDir = path.join(root, 'android', 'app', 'src', 'main', 'assets', 'reader');
const partsDir = path.join(readerDir, 'reader-lite.parts');
const outFile = path.join(readerDir, 'reader-lite.js');

const parts = fs.readdirSync(partsDir).filter((f) => f.endsWith('.js')).sort();
assert.ok(parts.length >= 4, 'parts 至少 4 个模块');
const bundle = parts.map((f) => fs.readFileSync(path.join(partsDir, f), 'utf8')).join('');
const current = fs.readFileSync(outFile, 'utf8');
assert.strictEqual(current, bundle, 'reader-lite.js 必须等于 parts 拼接（先运行 bundle-reader-lite.js --write）');

console.log(`reader-lite parts OK: ${parts.length} parts, ${bundle.length} bytes`);
