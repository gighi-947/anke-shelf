// 进度写入唯一出口测试（真实加载 web/js/reader-save.js）。
// 运行：node tests/js/reader-save.test.js（无需 npm）。
//
// 背景回归（2026-08-22 审查清理）：Bridge 失败必 throw，而 reader.js 曾在
// 3 处裸调用 Api.saveProgress（滚动防抖/翻页/换章/锚点跳转），后端不可用时
// 进度静默丢失且产生 unhandled rejection。修复后所有进度写入必须经过
// ProgressSaver.persistProgress（错误出口：toast 一次 + console.error）。
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

// ---- 结构守卫：reader.js 不得绕过 ProgressSaver 直写进度 ----
const readerSrc = fs.readFileSync(
  path.join(__dirname, '../../web/js/reader.js'), 'utf8');
assert.ok(
  !/Api\.saveProgress/.test(readerSrc),
  'reader.js 不得直接调用 Api.saveProgress，必须走 ProgressSaver.persistProgress',
);
assert.ok(
  /ProgressSaver\.persistProgress/.test(readerSrc),
  'reader.js 的进度写入应经过 ProgressSaver.persistProgress',
);

// ---- 行为测试：失败被吞并提示，成功不提示 ----
let toastCalls = [];
let apiCalls = [];
let nextError = null;

global.Api = {
  saveProgress: async (bookId, chapterIndex, offset) => {
    apiCalls.push({ bookId, chapterIndex, offset });
    if (nextError) { const e = nextError; nextError = null; throw e; }
    return { ok: true };
  },
};
global.Toast = {
  show: (msg, isError) => { toastCalls.push({ msg, isError }); },
};

const { ProgressSaver } = require('../../web/js/reader-save.js');

(async () => {
  // 成功路径：参数原样传递，不弹错误。
  await ProgressSaver.persistProgress('book-1', 2, 123);
  assert.deepStrictEqual(apiCalls, [{ bookId: 'book-1', chapterIndex: 2, offset: 123 }]);
  assert.strictEqual(toastCalls.length, 0);

  // 失败路径：promise 正常 resolve（调用方无需 catch），toast 错误一次。
  nextError = new Error('HTTP 500');
  await ProgressSaver.persistProgress('book-1', 2, 124);
  assert.strictEqual(toastCalls.length, 1, '失败必须 toast 一次');
  assert.strictEqual(toastCalls[0].isError, true, '失败 toast 必须标记为错误');
  assert.ok(/进度/.test(toastCalls[0].msg), '错误提示需说明是进度保存失败');
  assert.ok(/500/.test(toastCalls[0].msg), '错误提示需带后端原因');

  // 连续失败：同一故障期只提示一次（滚动防抖每 500ms 一次，不能刷屏）。
  nextError = new Error('HTTP 500');
  await ProgressSaver.persistProgress('book-1', 2, 125);
  nextError = new Error('HTTP 500');
  await ProgressSaver.persistProgress('book-1', 2, 126);
  assert.strictEqual(toastCalls.length, 1, '连续失败只 toast 一次');

  // 恢复成功后再次失败：重新提示（新一轮故障用户需要知道）。
  await ProgressSaver.persistProgress('book-1', 2, 127);
  nextError = new Error('backend dead');
  await ProgressSaver.persistProgress('book-1', 2, 128);
  assert.strictEqual(toastCalls.length, 2, '成功后的新失败应重新提示');

  console.log('reader-save test OK');
})().catch((e) => {
  console.error(e && e.message ? e.message : e);
  process.exit(1);
});
