// reader-lite.js 模块合并脚本：
// - 无参数：校验 parts 拼接结果与现役 assets/reader/reader-lite.js 完全一致（CI 守卫）；
// - --write：用 parts 重新生成现役文件。
// parts 按行边界切片、保留原始行尾，按文件名排序后直接拼接（无额外分隔符），
// 因此与现役文件字节级一致。
'use strict';

const fs = require('fs');
const path = require('path');

const readerDir = path.join(
  __dirname,
  '..',
  'app',
  'src',
  'main',
  'assets',
  'reader',
);
const partsDir = path.join(readerDir, 'reader-lite.parts');
const outFile = path.join(readerDir, 'reader-lite.js');

const parts = fs.readdirSync(partsDir)
  .filter((f) => f.endsWith('.js'))
  .sort();
if (parts.length === 0) {
  console.error('reader-lite.parts 为空');
  process.exit(1);
}

const bundle = parts.map((f) => fs.readFileSync(path.join(partsDir, f), 'utf8')).join('');

if (process.argv[2] === '--write') {
  fs.writeFileSync(outFile, bundle, 'utf8');
  console.log(`bundle written: ${outFile} (${parts.length} parts, ${bundle.length} bytes)`);
} else {
  const current = fs.readFileSync(outFile, 'utf8');
  if (current !== bundle) {
    console.error('reader-lite.js 与 parts 不一致：请运行 node android/scripts/bundle-reader-lite.js --write');
    process.exit(1);
  }
  console.log(`reader-lite bundle OK: ${parts.length} parts, ${bundle.length} bytes`);
}
