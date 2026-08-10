/**
 * Reader 章节导航（B4）：上一章/下一章/翻页或换章。
 * 在 reader.js 之后加载，把方法合并回 window.Reader。
 */
(function () {
  'use strict';

  Object.assign(window.Reader || {}, {
    prevChapter() {
      const i = App.state.chapterIndex - 1;
      if (i >= 0) this.loadChapter(i, 0);
    },

    nextChapter() {
      const book = App.state.book;
      if (!book) return;
      const i = App.state.chapterIndex + 1;
      if (i < book.chapters.length) this.loadChapter(i, 0);
    },

    pageOrChapter(delta) {
      if (Paged.isActive()) {
        if (delta > 0) Paged.nextPage(true);
        else Paged.prevPage(true);
      } else if (delta > 0) {
        this.nextChapter();
      } else {
        this.prevChapter();
      }
    },
  });
})();
