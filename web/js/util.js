/**
 * 前端公共小工具：快捷键展示、时长/日期格式化。
 */
(function () {
  'use strict';

  function displayKey(key) {
    if (!key) return '未设置';
    const map = {
      ' ': '空格', Space: '空格', ArrowRight: '→', ArrowLeft: '←', ArrowUp: '↑', ArrowDown: '↓',
      PageUp: 'PageUp', PageDown: 'PageDown', Home: 'Home', End: 'End',
    };
    return map[key] || key;
  }

  function fmtDuration(secs) {
    secs = Math.max(0, Math.round(secs || 0));
    if (secs < 60) return secs + ' 秒';
    const mins = Math.floor(secs / 60);
    if (mins < 60) return mins + ' 分钟';
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m ? h + ' 小时 ' + m + ' 分' : h + ' 小时';
  }

  function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' });
  }

  window.Util = { displayKey, fmtDuration, fmtDate };
})();
