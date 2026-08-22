// 空白页判定数学单测（A2：提前退出 + 布局代际缓存，2026-08-22）。
// 运行：node tests/js/paged-blank.test.js（无需 npm）。
//
// 背景：isPageBlank 每次翻页扫 15（单页）/20（双页）个采样点，是翻页的
// 固定命中测试成本。A2 改为：顶行命中或命中数达到 ceil(25%×总采样) 即
// 提前判定非空白（与整扫后的 hits/samples < 25% 判据数学等价），并对
// 同一布局代际缓存判定（prepare() 推进代际作废缓存）。
// 本文件锁定判定的边界值；扫描集成由 UI 实机 harness 回归覆盖。
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const { isBlankVerdict, nonBlankThreshold } = require('../../web/js/paged.js');

// ---- 提前退出阈值边界 ----
assert.strictEqual(nonBlankThreshold(15), 4, '单页 15 采样：4 次命中即不可能 <25%');
assert.strictEqual(nonBlankThreshold(20), 5, '双页 20 采样：5 次命中即达 25%');

// ---- 整扫判据等价性（hits < threshold ⟺ hits/samples < 25%）----
assert.strictEqual(isBlankVerdict(3, 15, 0), true, '3/15=20% → 空白');
assert.strictEqual(isBlankVerdict(4, 15, 0), false, '4/15≈27% → 非空白（提前退出边界）');
assert.strictEqual(isBlankVerdict(4, 20, 0), true, '双页 4/20=20% → 空白');
assert.strictEqual(isBlankVerdict(5, 20, 0), false, '双页 5/20=25% → 非空白（正好达标）');

// ---- 顶行规则：顶行有内容直接非空白（可立即退出）----
assert.strictEqual(isBlankVerdict(0, 15, 1), false, '顶行命中 → 非空白');
assert.strictEqual(isBlankVerdict(0, 20, 2), false, '双页顶行命中 → 非空白');

// ---- 结构守卫：提前退出 + 布局代际缓存已接入扫描与 prepare ----
const src = fs.readFileSync(path.join(__dirname, '../../web/js/paged.js'), 'utf8');
assert.ok(src.includes('hits >= threshold'), '命中达阈值应提前判定非空白');
assert.ok(src.includes('if (ri === 0) return false'), '顶行命中应直接判非空白');
assert.ok(src.includes('layoutGen += 1'), 'prepare 必须推进布局代际（作废空白缓存）');
assert.ok(src.includes('blankCache'), 'isPageBlank 必须走布局代际缓存');

console.log('paged-blank test OK');
