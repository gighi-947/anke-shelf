// API 契约守卫无法启动 Python 时，必须输出可操作的失败原因。
'use strict';

const assert = require('assert');
const { spawnSync } = require('child_process');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const contractScript = path.join(__dirname, 'api-contract.test.js');
const missingPython = path.join(__dirname, `missing-python-${process.pid}`);
const res = spawnSync(process.execPath, [contractScript], {
  cwd: ROOT,
  encoding: 'utf8',
  env: { ...process.env, PYTHON: missingPython },
});

if (res.error) throw res.error;
assert.strictEqual(res.status, 1, 'Python 启动失败时契约守卫应退出 1');

const output = `${res.stdout || ''}\n${res.stderr || ''}`;
assert.match(output, /无法启动 Python/, '应说明 Python 无法启动');
assert.ok(output.includes(missingPython), '应输出失败的 Python 可执行路径');
assert.match(output, /ENOENT/, '应保留底层错误代码');

console.log('api contract launch diagnostics OK');
