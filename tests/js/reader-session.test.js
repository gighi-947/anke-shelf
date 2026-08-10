// ReaderSession 会话状态单测（真实加载 web/js/reader-session.js）。
// 运行：node tests/js/reader-session.test.js（无需 npm）。
'use strict';

const assert = require('assert');
const { ReaderSession } = require('../../web/js/reader-session.js');

const s = new ReaderSession('book-1', 0, 0);
assert.strictEqual(s.bookId, 'book-1');
assert.strictEqual(s.chapterIndex, 0);
assert.strictEqual(s.textOffset, 0);
assert.strictEqual(s.mode, 'scroll');
assert.strictEqual(s.isDirty(), false);
assert.strictEqual(s.lastSaved, null);

s.enterChapter(2, 10);
assert.strictEqual(s.chapterIndex, 2);
assert.strictEqual(s.textOffset, 10);
assert.strictEqual(s.isDirty(), true);

s.setPosition(123);
assert.strictEqual(s.textOffset, 123);
assert.strictEqual(s.isDirty(), true);

s.markSaved();
assert.strictEqual(s.isDirty(), false);
assert.deepStrictEqual(s.lastSaved, { chapterIndex: 2, textOffset: 123 });

s.mode = 'paged';
assert.strictEqual(s.mode, 'paged');

assert.ok(s.elapsedSeconds() >= 0);

console.log('reader-session test OK');
