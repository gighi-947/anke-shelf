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
 *
 * 决策逻辑全部下沉到 [ProgressModel]（纯函数、虚拟时钟），本类只负责
 * 真实调度器与落盘执行；事件序列可用 fixtures 离线回放。
 */
class ChapterProgressTracker(
    private val bookId: String,
    initialChapter: Int,
    initialOffset: Int,
    private val restoreFrom: (String) -> ProgressEntry?,
    private val persist: (
        bookId: String,
        chapterIndex: Int,
        textOffset: Int,
        pageIndex: Int,
        pageTotal: Int,
        scrollRatio: Double,
    ) -> Unit,
) {
    private val lock = Any()
    private var state: ProgressState
    private val pending = mutableMapOf<Int, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "progress-debounce").apply { isDaemon = true }
    }

    init {
        val stored = restoreFrom(bookId)
        state = ProgressModel.initialState(stored, initialChapter, initialOffset)
        runCatching {
            android.util.Log.d(
                "AnkeShelf",
                "tracker init off=${stored?.text_offset} page=${stored?.page_index} " +
                    "param off=$initialOffset",
            )
        }
    }

    /** 某章恢复锚点：先内存（本会话最新），后磁盘持久化值。 */
    fun restoreOffsetFor(chapter: Int): Int = synchronized(lock) {
        state.restoreOffsetFor(chapter)
    }

    /** 某章滚动模式比例锚点：-1 = 文本锚点；0..1 = 全图页按比例恢复。 */
    fun restoreRatioFor(chapter: Int): Double = synchronized(lock) {
        state.restoreRatioFor(chapter)
    }

    fun restorePageFor(chapter: Int): Int = synchronized(lock) { state.restorePageFor(chapter) }

    fun restoreTotalFor(chapter: Int): Int = synchronized(lock) { state.restoreTotalFor(chapter) }

    /** 滚动/页面上报：更新内存，500ms 防抖落盘（对齐桌面 scroll debounce）。 */
    fun onOffset(chapter: Int, offset: Int, ratio: Double = -1.0) {
        synchronized(lock) {
            state = ProgressModel.apply(
                state,
                ProgressEvent.Scroll(chapter, offset, ratio, System.currentTimeMillis()),
            ).state
            rescheduleDebounceLocked(chapter)
        }
    }

    /** 分页换章前刷新旧章文本锚点；页码沿用最后一次翻页事件，滚动比例强制清除。 */
    fun onPagedAnchor(chapter: Int, offset: Int) {
        synchronized(lock) {
            state = ProgressModel.apply(
                state,
                ProgressEvent.PagedAnchor(chapter, offset),
            ).state
        }
    }

    /** 翻页事件：立即落盘（对齐桌面 onPageTurned -> saveProgress），携带页码供精确恢复。 */
    fun onPageTurn(chapter: Int, offset: Int, page: Int = -1, total: Int = -1) {
        val decision = synchronized(lock) {
            val d = ProgressModel.apply(
                state,
                ProgressEvent.PageTurn(chapter, offset, page, total, System.currentTimeMillis()),
            )
            state = d.state
            d
        }
        runPersists(decision.persists)
    }

    /** 换章：立即落盘旧章，避免异步 JS 事件在新页加载后被丢弃。 */
    fun onChapterSwitch(from: Int) {
        val decision = synchronized(lock) {
            pending.remove(from)?.cancel(false)
            val d = ProgressModel.apply(
                state,
                ProgressEvent.ChapterSwitch(from),
            )
            state = d.state
            d
        }
        runPersists(decision.persists)
    }

    /** 退出/退后台/离开页面：取消防抖并立即落盘所有已知章节。 */
    fun flush() {
        val decision = synchronized(lock) {
            pending.values.forEach { it.cancel(false) }
            pending.clear()
            val d = ProgressModel.apply(state, ProgressEvent.Flush)
            state = d.state
            d
        }
        runPersists(decision.persists)
    }

    /** 屏幕销毁后的延迟关闭：阻止页面销毁期间迟到的桥事件覆盖正确进度。 */
    fun close() {
        synchronized(lock) {
            state = ProgressModel.apply(state, ProgressEvent.Close).state
            pending.values.forEach { it.cancel(false) }
            pending.clear()
        }
        scheduler.shutdownNow()
    }

    private fun rescheduleDebounceLocked(chapter: Int) {
        pending.remove(chapter)?.cancel(false)
        val due = state.debounceUntil[chapter] ?: return
        val delay = (due - System.currentTimeMillis()).coerceAtLeast(0)
        pending[chapter] = scheduler.schedule({ onDebounceDue(chapter, due) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun onDebounceDue(chapter: Int, due: Long) {
        val decision = synchronized(lock) {
            pending.remove(chapter)
            val d = ProgressModel.apply(state, ProgressEvent.DebounceDue(chapter, due))
            state = d.state
            d
        }
        runPersists(decision.persists)
    }

    private fun runPersists(persists: List<ProgressPersist>) {
        persists.forEach { p -> persist(bookId, p.chapter, p.offset, p.page, p.total, p.ratio) }
    }
}
