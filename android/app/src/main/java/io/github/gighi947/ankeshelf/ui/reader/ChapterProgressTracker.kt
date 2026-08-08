package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.ProgressEntry
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 阅读进度追踪器：进度保存的唯一入口（替换原先散落在 JS 桥 / Compose 回调里的实现）。
 *
 * 设计对照：
 * - 桌面端 reader.js：text_offset 锚点；滚动 500ms debounce；翻页/换章/退出立即保存；
 *   换章前先存旧章精确 offset（loadChapter 语义）。
 * - Readest：独立进度存储 + 与 lastSaved 相同则跳过（避免旧数据覆盖新数据）。
 * - Legado：翻页/换章/onPause 落盘，写入放后台线程。
 *
 * 本类约定：
 * - JS 只上报 (chapterIndex, textOffset)，由本类决定何时落盘；
 * - 每章维护“最后一次已知 offset”（内存），恢复时优先内存值，其次磁盘值；
 * - 滚动事件 500ms debounce 落盘；翻页事件立即落盘；换章/退出立即 flush；
 * - 相同 (chapter, offset) 去重，避免无意义的整文件重复写入。
 */
class ChapterProgressTracker(
    private val bookId: String,
    initialChapter: Int,
    initialOffset: Int,
    private val restoreFrom: (String) -> ProgressEntry?,
    private val persist: (bookId: String, chapterIndex: Int, textOffset: Int, pageIndex: Int, pageTotal: Int) -> Unit,
) {
    private val lock = Any()
    private val lastKnown = mutableMapOf<Int, Int>()
    private val lastPage = mutableMapOf<Int, Int>()
    private val lastTotal = mutableMapOf<Int, Int>()
    private val saved = mutableMapOf<Int, Int>()
    private val pending = mutableMapOf<Int, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "progress-debounce").apply { isDaemon = true }
    }
    @Volatile
    private var closed = false

    init {
        synchronized(lock) {
            restoreFrom(bookId)?.let { p ->
                if (p.chapter_index >= 0 && p.text_offset > 0) {
                    lastKnown[p.chapter_index] = p.text_offset
                    if (p.page_index >= 0) lastPage[p.chapter_index] = p.page_index
                    if (p.page_total > 0) lastTotal[p.chapter_index] = p.page_total
                }
            }
            if (initialOffset > 0) lastKnown[initialChapter] = initialOffset
            saved.putAll(lastKnown)
        }
    }

    /** 某章恢复锚点：先内存（本会话最新），后磁盘持久化值。 */
    fun restoreOffsetFor(chapter: Int): Int = synchronized(lock) {
        (lastKnown[chapter] ?: 0).coerceAtLeast(0)
    }

    fun restorePageFor(chapter: Int): Int = synchronized(lock) { lastPage[chapter] ?: -1 }

    fun restoreTotalFor(chapter: Int): Int = synchronized(lock) { lastTotal[chapter] ?: -1 }

    /** 滚动/页面上报：更新内存，500ms 防抖落盘（对齐桌面 scroll debounce）。 */
    fun onOffset(chapter: Int, offset: Int) {
        if (closed || offset <= 0) return
        val shouldSchedule = synchronized(lock) {
            lastKnown[chapter] = offset
            saved[chapter] != offset
        }
        if (!shouldSchedule) return
        pending.remove(chapter)?.cancel(false)
        pending[chapter] = scheduler.schedule({ persist(chapter) }, 500, TimeUnit.MILLISECONDS)
    }

    /** 翻页事件：立即落盘（对齐桌面 onPageTurned -> saveProgress），携带页码供精确恢复。 */
    fun onPageTurn(chapter: Int, offset: Int, page: Int = -1, total: Int = -1) {
        if (closed || offset <= 0) return
        synchronized(lock) {
            lastKnown[chapter] = offset
            if (page >= 0) lastPage[chapter] = page
            if (total > 0) lastTotal[chapter] = total
        }
        persist(chapter)
    }

    /** 换章：立即落盘旧章，避免异步 JS 事件在新页加载后被丢弃。 */
    fun onChapterSwitch(from: Int, to: Int) {
        persist(from)
    }

    /** 退出/退后台/离开页面：取消防抖并立即落盘所有已知章节。 */
    fun flush() {
        if (closed) return
        val chapters = synchronized(lock) { lastKnown.keys.toList() }
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        chapters.forEach { persist(it) }
    }

    /** 屏幕销毁后的延迟关闭：阻止页面销毁期间迟到的桥事件覆盖正确进度。 */
    fun close() {
        closed = true
        pending.values.forEach { it.cancel(false) }
        pending.clear()
    }

    private fun persist(chapter: Int) {
        if (closed) return
        val offset = synchronized(lock) {
            val o = lastKnown[chapter] ?: return
            if (o <= 0 || saved[chapter] == o) return
            saved[chapter] = o
            o
        }
        val page = synchronized(lock) { lastPage[chapter] ?: -1 }
        val total = synchronized(lock) { lastTotal[chapter] ?: -1 }
        persist(bookId, chapter, offset, page, total)
    }
}
