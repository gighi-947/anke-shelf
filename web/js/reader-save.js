/**
 * 进度写入唯一出口：所有进度保存点（滚动防抖 / 翻页 / 换章 / 锚点跳转）
 * 必须经过 persistProgress。Bridge 失败必 throw（见 bridge.js），这里统一
 * 提供错误出口：console.error + toast（同一故障期只提示一次，避免滚动防抖
 * 每 500ms 刷屏；恢复成功后再次失败会重新提示）。返回的 promise 永远
 * resolve，调用方无需再 catch。
 */
(function () {
  'use strict';

  let saveErrorShown = false;

  function persistProgress(bookId, chapterIndex, textOffset) {
    return Api.saveProgress(bookId, chapterIndex, textOffset).then(
      () => { saveErrorShown = false; },
      (e) => {
        const reason = e && e.message ? e.message : String(e);
        console.error('[progress] 保存失败:', reason);
        if (!saveErrorShown) {
          saveErrorShown = true;
          Toast.show('阅读进度保存失败：' + reason, true);
        }
      },
    );
  }

  const ProgressSaver = { persistProgress };
  if (typeof window !== 'undefined') window.ProgressSaver = ProgressSaver;
  if (typeof module !== 'undefined' && module.exports) module.exports = { ProgressSaver };
})();
