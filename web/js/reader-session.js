/**
 * 阅读会话（B4）：一次阅读会话的状态与脏标记。
 * 进度持久化仍走现有 save 链路；session 记录“当前章节/位置、是否已落盘”，
 * 供后续 controller/navigation/position 拆分时统一取用。
 */
(function () {
  'use strict';

  class ReaderSession {
    constructor(bookId, chapterIndex = 0, textOffset = 0) {
      this.bookId = bookId;
      this.chapterIndex = chapterIndex;
      this.textOffset = textOffset;
      this.mode = 'scroll'; // 'scroll' | 'paged'
      this.startedAt = Date.now();
      this.dirty = false;
      this.lastSaved = null; // { chapterIndex, textOffset }
    }

    enterChapter(index, textOffset = 0) {
      this.chapterIndex = index;
      this.textOffset = textOffset;
      this.dirty = true;
    }

    setPosition(textOffset) {
      this.textOffset = textOffset;
      this.dirty = true;
    }

    markSaved() {
      this.lastSaved = { chapterIndex: this.chapterIndex, textOffset: this.textOffset };
      this.dirty = false;
    }

    isDirty() {
      return this.dirty;
    }

    elapsedSeconds() {
      return Math.max(0, Math.round((Date.now() - this.startedAt) / 1000));
    }
  }

  if (typeof window !== 'undefined') window.ReaderSession = ReaderSession;
  if (typeof module !== 'undefined' && module.exports) module.exports = { ReaderSession };
})();
