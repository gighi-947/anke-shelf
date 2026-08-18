/**
 * 前端 API 客户端（B3b）：UI 不再直接调用 Bridge.call，统一走 Api.<method>()。
 * 方法名与后端 /api/<name> 契约一一对应（见 app/api/__init__.py 的 _HANDLERS）；
 * 参数原样透传，返回 Promise。
 */
(function () {
  'use strict';

  const METHODS = [
    ['on_frontend_ready', 'onFrontendReady'],
    ['toggle_fullscreen', 'toggleFullscreen'],
    ['log_frontend', 'logFrontend'],
    ['get_version', 'getVersion'],
    ['open_data_dir', 'openDataDir'],
    ['uninstall_and_quit', 'uninstallAndQuit'],
    ['export_diagnostics', 'exportDiagnostics'],
    ['verify_data_integrity', 'verifyDataIntegrity'],
    ['backup_create', 'backupCreate'],
    ['backup_verify', 'backupVerify'],
    ['backup_restore', 'backupRestore'],
    ['get_shelf', 'getShelf'],
    ['import_books', 'importBooks'],
    ['remove_book', 'removeBook'],
    ['open_book', 'openBook'],
    ['rename_book', 'renameBook'],
    ['save_progress', 'saveProgress'],
    ['get_chapter_plaintext', 'getChapterPlaintext'],
    ['search', 'search'],
    ['search_more', 'searchMore'],
    ['is_index_ready', 'isIndexReady'],
    ['get_annotations', 'getAnnotations'],
    ['save_annotation', 'saveAnnotation'],
    ['update_annotation', 'updateAnnotation'],
    ['delete_annotation', 'deleteAnnotation'],
    ['add_bookmark', 'addBookmark'],
    ['delete_bookmark', 'deleteBookmark'],
    ['export_annotations', 'exportAnnotations'],
    ['record_reading', 'recordReading'],
    ['get_stats', 'getStats'],
    ['nga_get_config', 'ngaGetConfig'],
    ['nga_save_config', 'ngaSaveConfig'],
    ['nga_clear_config', 'ngaClearConfig'],
    ['nga_update_book', 'ngaUpdateBook'],
    ['nga_update_defaults', 'ngaUpdateDefaults'],
    ['export_start', 'exportStart'],
    ['export_status', 'exportStatus'],
    ['export_open_dest', 'exportOpenDest'],
    ['export_cancel', 'exportCancel'],
    ['nga_start_download', 'ngaStartDownload'],
    ['nga_download_status', 'ngaDownloadStatus'],
    ['nga_cancel', 'ngaCancel'],
    ['gululu_start_import', 'gululuStartImport'],
    ['gululu_start_export', 'gululuStartExport'],
    ['gululu_start_update', 'gululuStartUpdate'],
    ['gululu_get_comments', 'gululuGetComments'],
    ['gululu_decrypt_secret', 'gululuDecryptSecret'],
    ['gululu_import_status', 'gululuImportStatus'],
    ['gululu_cancel', 'gululuCancel'],
    ['get_fonts', 'getFonts'],
    ['pick_font_file', 'pickFontFile'],
    ['get_settings', 'getSettings'],
    ['save_settings', 'saveSettings'],
  ];

  const Api = {};
  for (const [snake, camel] of METHODS) {
    Api[camel] = (...args) => Bridge.call(snake, ...args);
  }
  if (typeof window !== 'undefined') {
    window.Api = Api;
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { METHODS };
  }
})();
