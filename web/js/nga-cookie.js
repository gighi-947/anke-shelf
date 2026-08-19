/**
 * NGA Cookie 解析：从任意文本/完整 Cookie 头中提取 uid/cid。
 * 浏览器挂 window.parseNgaCookieText；Node 测试走 module.exports。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.parseNgaCookieText = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function extract(raw, name) {
    const re = new RegExp(name + '\\s*=\\s*["\']?([^;"\'\\s]+)', 'i');
    const m = raw.match(re);
    return m ? m[1].trim() : '';
  }

  function parseNgaCookieText(text) {
    const raw = String(text || '');
    return {
      uid: extract(raw, 'ngaPassportUid'),
      cid: extract(raw, 'ngaPassportCid'),
    };
  }

  return parseNgaCookieText;
});
