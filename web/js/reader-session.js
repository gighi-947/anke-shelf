/**
 * 阅读会话（B4）：当前书/章节/位置的内存状态。
 * 进度持久化走 ProgressSaver 唯一出口，session 只承载章节导航所需的
 * 最小状态（bookId / chapterIndex / textOffset）。
 */
(function () {
  'use strict';

  class ReaderSession {
    constructor(bookId, chapterIndex = 0, textOffset = 0) {
      this.bookId = bookId;
      this.chapterIndex = chapterIndex;
      this.textOffset = textOffset;
    }

    enterChapter(index, textOffset = 0) {
      this.chapterIndex = index;
      this.textOffset = textOffset;
    }

    setPosition(textOffset) {
      this.textOffset = textOffset;
    }
  }

  if (typeof window !== 'undefined') window.ReaderSession = ReaderSession;
  if (typeof module !== 'undefined' && module.exports) module.exports = { ReaderSession };
})();
