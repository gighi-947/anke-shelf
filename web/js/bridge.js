/**
 * HTTP API 桥接封装（前后端分离版）。
 * - 运行时通过 fetch 调用本地 /api/<name>，业务不再依赖 pywebview js_api
 * - 每次启动随机生成令牌，从 URL query 读取（?token=...），随请求头带回
 * - 浏览器直接打开（无令牌）时降级为 MOCKS，便于纯前端调试
 */
(function () {
  'use strict';

  const TOKEN = new URLSearchParams(window.location.search).get('token') || '';

  const MOCKS = {
    get_shelf: async () => [],
    import_books: async () => [],
    remove_book: async () => true,
    open_book: async () => ({ error: '浏览器调试模式：无法打开书籍' }),
    save_progress: async () => undefined,
    get_chapter_plaintext: async () => '',
    search: async () => ({ ready: true, results: [] }),
    search_more: async () => ({ hits: [], more: false }),
    is_index_ready: async () => true,
    get_settings: async () => ({
      theme: 'light', theme_mode: '', font_size: 18, line_height: 1.8, font_family: 'reader',
      custom_font: '', book_fonts: {}, page_width: 1.0, bars_pinned: false,
      custom_bg: '', custom_primary: '', custom_accent: '', custom_text: '',
      pagination: false, dual_page: false, auto_dual: true,
      shelf_view: 'grid', shelf_sort: 'recent',
    }),
    save_settings: async () => undefined,
    get_fonts: async () => ({ fonts: [], global_font: '', book_fonts: {} }),
    pick_font_file: async () => ({ error: '浏览器调试模式：无法选择字体' }),
    nga_get_config: async () => ({ uid: '', cid: '', ua: '', base_url: 'https://bbs.nga.cn', configured: false }),
    nga_save_config: async () => ({ configured: false }),
    nga_clear_config: async () => ({ uid: '', cid: '', ua: '', base_url: 'https://bbs.nga.cn', configured: false }),
    nga_start_download: async () => ({ ok: false, error: '浏览器调试模式：无法下载' }),
    nga_download_status: async () => ({ running: false, stage: 'idle', detail: '' }),
    nga_cancel: async () => undefined,
    nga_update_book: async () => ({ ok: false, error: '浏览器调试模式：无法更新' }),
    nga_update_defaults: async () => ({ ok: true, tid: 0, author_id: 0, theme: 'light', image_mode: 'online', per_chapter: 20, toc_pid: 0 }),
    export_start: async () => ({ ok: false, error: '浏览器调试模式：无法导出' }),
    export_status: async () => ({ running: false, stage: 'idle', detail: '', files: [], dest: '', error: '' }),
    export_open_dest: async () => ({ ok: false, error: '浏览器调试模式' }),
    export_diagnostics: async () => ({ ok: false, error: '浏览器调试模式：无法导出诊断包' }),
    get_annotations: async () => ({ highlights: [], bookmarks: [] }),
    save_annotation: async () => ({}),
    update_annotation: async () => ({}),
    delete_annotation: async () => true,
    add_bookmark: async () => ({}),
    delete_bookmark: async () => true,
    export_annotations: async () => '',
    record_reading: async () => undefined,
    get_stats: async () => ({ book: {}, global: {} }),
    open_data_dir: async () => ({ ok: false, error: '浏览器调试模式' }),
    uninstall_and_quit: async () => ({ ok: false }),
    get_version: async () => '1.2.0',
    toggle_fullscreen: async () => ({ ok: false, error: '浏览器调试模式' }),
    on_frontend_ready: async () => undefined,
    log_frontend: async () => undefined,
  };

  // 部分接口耗时较长（文件对话框/下载），单独放宽超时。
  const TIMEOUTS = {
    import_books: 300000,
    pick_font_file: 180000,
    nga_start_download: 60000,
    export_annotations: 30000,
  };
  const DEFAULT_TIMEOUT = 10000;

  function withTimeout(promise, ms, name) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`Bridge call ${name} timed out after ${ms}ms`));
      }, ms);
      promise.then(
        (v) => { clearTimeout(timer); resolve(v); },
        (e) => { clearTimeout(timer); reject(e); }
      );
    });
  }

  async function callHttp(name, args) {
    const res = await fetch('/api/' + name, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Anke-Token': TOKEN,
      },
      body: JSON.stringify({ args }),
    });
    let data = null;
    try {
      data = await res.json();
    } catch (e) { /* 非 JSON 响应 */ }
    if (!res.ok || !data || data.ok === false) {
      throw new Error((data && data.error) || ('HTTP ' + res.status));
    }
    return data.data;
  }

  window.Bridge = {
    /** 调用 Python 侧方法。失败统一抛错（由调用方 toast）。 */
    async call(name, ...args) {
      if (TOKEN) {
        return await withTimeout(callHttp(name, args), TIMEOUTS[name] || DEFAULT_TIMEOUT, name);
      }
      if (name in MOCKS) {
        return await MOCKS[name](...args);
      }
      throw new Error(`未知的桥接方法: ${name}`);
    },
  };
})();
