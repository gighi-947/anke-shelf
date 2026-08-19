/**
 * HTTP API 桥接封装（前后端分离版）。
 * - 运行时通过 fetch 调用本地 /api/<name>，业务不再依赖 pywebview js_api
 * - 每次启动随机生成令牌，从 URL query 读取（?token=...），落 sessionStorage
 *   后立即抹掉地址栏 query，随请求头带回（防 token 进历史/日志）
 * - 无令牌时明确提示需要真实后端服务（不再内置 50 个调试 MOCKS）
 */
(function () {
  'use strict';

  const TOKEN = new URLSearchParams(window.location.search).get('token') ||
      sessionStorage.getItem('anke_token') || '';
  if (TOKEN && window.location.search) {
    // 启动 URL 的 token 只用于首屏：落 sessionStorage 后立即抹掉 query，
    // 刷新后从 sessionStorage 恢复，避免 token 留在地址栏/历史/本地日志。
    sessionStorage.setItem('anke_token', TOKEN);
    history.replaceState(null, '', window.location.pathname);
  }

  // 部分接口耗时较长（文件对话框/下载），单独放宽超时。
  const TIMEOUTS = {
    import_books: 300000,
    pick_font_file: 180000,
    nga_start_download: 60000,
    gululu_start_import: 60000,
    gululu_start_export: 60000,
    gululu_start_update: 60000,
    gululu_get_comments: 60000,
    export_annotations: 30000,
  };
  const DEFAULT_TIMEOUT = 10000;

  async function callHttp(name, args) {
    const timeout = TIMEOUTS[name] || DEFAULT_TIMEOUT;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const res = await fetch('/api/' + name, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Anke-Token': TOKEN,
        },
        body: JSON.stringify({ args }),
        signal: controller.signal,
      });
      let data = null;
      try {
        data = await res.json();
      } catch (e) { /* 非 JSON 响应 */ }
      if (!res.ok || !data || data.ok === false) {
        throw new Error((data && data.error) || ('HTTP ' + res.status));
      }
      // 业务错误已由 server 转为 HTTP 4xx/5xx，这里只需原样返回成功 data。
      return data.data;
    } catch (e) {
      if (e.name === 'AbortError') {
        // 与旧超时错误文案保持一致；底层 fetch 已被取消，不再悬空等待。
        throw new Error(`Bridge call ${name} timed out after ${timeout}ms`);
      }
      throw e;
    } finally {
      clearTimeout(timer);
    }
  }

  window.Bridge = {
    /** 调用 Python 侧方法。失败统一抛错（由调用方 toast）。 */
    async call(name, ...args) {
      if (TOKEN) {
        return await callHttp(name, args);
      }
      throw new Error('浏览器调试模式需要真实后端服务（请运行 python -m app.main）');
    },
  };
})();
