// Android 阅读桥 ready 握手契约：真实加载现役 reader-lite.js（vm 沙箱，无 DOM），
// 校验导出的桥版本与 emitReady 发出的结构化 payload。
// 运行：node contracts/tests/bridge-contract.test.js（无需 npm）。
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const src = fs.readFileSync(
  path.join(
    __dirname,
    '..',
    '..',
    'android',
    'app',
    'src',
    'main',
    'assets',
    'reader',
    'reader-lite.js',
  ),
  'utf8',
);

const windowObj = {};
const context = vm.createContext({ window: windowObj });
vm.runInContext(src, context);
const AnkeReader = windowObj.AnkeReader;

assert.ok(AnkeReader, 'window.AnkeReader 已导出');
assert.strictEqual(AnkeReader.bridgeVersion(), 1, '桥版本必须为 1');

let received = null;
context.AnkeReaderBridge = {
  onReady(payload) {
    received = JSON.parse(payload);
  },
};
AnkeReader.emitReady();

assert.ok(received, 'emitReady 必须上报 ready');
assert.strictEqual(received.bridgeVersion, 1, 'ready 版本必须为 1');
assert.ok(
  Array.isArray(received.capabilities) && received.capabilities.length > 0,
  'ready 必须携带能力清单',
);

console.log(
  `bridge contract OK: version=${received.bridgeVersion} capabilities=${received.capabilities.join(',')}`,
);
