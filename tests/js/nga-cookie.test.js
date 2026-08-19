// NGA Cookie 解析纯函数单测（真实加载 web/js/nga-cookie.js）。
// 运行：node tests/js/nga-cookie.test.js（无需 npm）。
'use strict';

const assert = require('assert');
const parseNgaCookieText = require('../../web/js/nga-cookie.js');

// 完整 Cookie 头
assert.deepStrictEqual(
  parseNgaCookieText('ngaPassportUid=12345; ngaPassportCid=abcdef; other=x'),
  { uid: '12345', cid: 'abcdef' },
);

// 带说明文字的粘贴文本
assert.deepStrictEqual(
  parseNgaCookieText('点击链接阅读：https://bbs.nga.cn/read.php?tid=1 ngaPassportUid=9876 ngaPassportCid=xyz'),
  { uid: '9876', cid: 'xyz' },
);

// 大小写不敏感
assert.deepStrictEqual(
  parseNgaCookieText('NGAPASSPORTUID=111; ngapassportcid=222'),
  { uid: '111', cid: '222' },
);

// 引号包裹值
assert.deepStrictEqual(
  parseNgaCookieText('ngaPassportUid="333"; ngaPassportCid="444"'),
  { uid: '333', cid: '444' },
);

// 缺失时返回空串，不抛错
assert.deepStrictEqual(
  parseNgaCookieText(''),
  { uid: '', cid: '' },
);
assert.deepStrictEqual(
  parseNgaCookieText('foo=bar'),
  { uid: '', cid: '' },
);

console.log('nga-cookie test OK');
