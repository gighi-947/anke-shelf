// ReaderSession 会话状态单测（真实加载 web/js/reader-session.js）。
// 运行：node tests/js/reader-session.test.js（无需 npm）。
// 2026-08-22 审查清理：session 只保留章节导航所需的最小状态
// （bookId/chapterIndex/textOffset）；脏标记/耗时/模式等零消费者成员已删除，
// 进度持久化统一走 ProgressSaver（见 reader-save.test.js）。
'use strict';

const assert = require('assert');
const { ReaderSession } = require('../../web/js/reader-session.js');

const s = new ReaderSession('book-1', 0, 0);
assert.strictEqual(s.bookId, 'book-1');
assert.strictEqual(s.chapterIndex, 0);
assert.strictEqual(s.textOffset, 0);

s.enterChapter(2, 10);
assert.strictEqual(s.chapterIndex, 2);
assert.strictEqual(s.textOffset, 10);

s.setPosition(123);
assert.strictEqual(s.textOffset, 123);

console.log('reader-session test OK');
