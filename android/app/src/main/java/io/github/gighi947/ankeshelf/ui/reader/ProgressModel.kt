package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.ProgressEntry

/** 一次需要落盘的进度条目。 */
data class ProgressPersist(
    val chapter: Int,
    val offset: Int,
    val page: Int,
    val total: Int,
    val ratio: Double,
)

/**
 * 进度决策层状态：全部字段 + 每章防抖到期时刻（虚拟时钟）。
 * 只有滚动模式读 ratio；只有分页模式读 page/total（模式隔离，9.50/9.58）。
 */
data class ProgressState(
    val lastKnown: Map<Int, Int> = emptyMap(),
    val lastRatio: Map<Int, Double> = emptyMap(),
    val lastPage: Map<Int, Int> = emptyMap(),
    val lastTotal: Map<Int, Int> = emptyMap(),
    val saved: Map<Int, Int> = emptyMap(),
    val savedRatio: Map<Int, Double> = emptyMap(),
    val debounceUntil: Map<Int, Long> = emptyMap(),
    val closed: Boolean = false,
) {
    fun restoreOffsetFor(chapter: Int): Int = (lastKnown[chapter] ?: 0).coerceAtLeast(0)
    fun restoreRatioFor(chapter: Int): Double = lastRatio[chapter] ?: -1.0
    fun restorePageFor(chapter: Int): Int = lastPage[chapter] ?: -1
    fun restoreTotalFor(chapter: Int): Int = lastTotal[chapter] ?: -1
}

/** 进度事件：所有写入都带来源语义与虚拟时刻，可离线回放。 */
sealed interface ProgressEvent {
    val at: Long

    data class Scroll(
        val chapter: Int,
        val offset: Int,
        val page: Int = -1,
        val total: Int = -1,
        val ratio: Double = -1.0,
        override val at: Long,
    ) : ProgressEvent

    data class ScrollKeepPage(
        val chapter: Int,
        val offset: Int,
        val ratio: Double = -1.0,
        override val at: Long,
    ) : ProgressEvent

    data class PageTurn(
        val chapter: Int,
        val offset: Int,
        val page: Int = -1,
        val total: Int = -1,
        val ratio: Double = -1.0,
        override val at: Long,
    ) : ProgressEvent

    data class ChapterSwitch(
        val from: Int,
        val to: Int,
        override val at: Long,
    ) : ProgressEvent

    data class Flush(override val at: Long) : ProgressEvent
    data class Close(override val at: Long) : ProgressEvent
    data class DebounceDue(val chapter: Int, override val at: Long) : ProgressEvent
}

data class ProgressDecision(val state: ProgressState, val persists: List<ProgressPersist>)

/**
 * 纯决策层：输入旧状态 + 事件 → 新状态 + 待落盘条目。
 * 无真实时钟与调度器，防抖到期由测试/宿主用 [ProgressEvent.DebounceDue] 显式驱动，
 * 因此同一事件序列夹具可在 Kotlin 与 JS 桥两侧复现（进度回放）。
 */
object ProgressModel {
    const val DEBOUNCE_MS = 500L

    fun initialState(stored: ProgressEntry?, initialChapter: Int, initialOffset: Int): ProgressState {
        var lastKnown = emptyMap<Int, Int>()
        var lastRatio = emptyMap<Int, Double>()
        var lastPage = emptyMap<Int, Int>()
        var lastTotal = emptyMap<Int, Int>()
        if (stored != null && stored.chapter_index >= 0 && stored.text_offset > 0) {
            val ch = stored.chapter_index
            lastKnown = mapOf(ch to stored.text_offset)
            if (stored.scroll_ratio in 0.0..1.0) lastRatio = mapOf(ch to stored.scroll_ratio)
            if (stored.page_index >= 0) lastPage = mapOf(ch to stored.page_index)
            if (stored.page_total > 0) lastTotal = mapOf(ch to stored.page_total)
        }
        if (initialOffset > 0) lastKnown = lastKnown + (initialChapter to initialOffset)
        return ProgressState(
            lastKnown = lastKnown,
            lastRatio = lastRatio,
            lastPage = lastPage,
            lastTotal = lastTotal,
            saved = lastKnown,
            savedRatio = lastRatio,
        )
    }

    fun apply(current: ProgressState, event: ProgressEvent): ProgressDecision {
        if (current.closed && event !is ProgressEvent.Close) {
            return ProgressDecision(current, emptyList())
        }
        return when (event) {
            is ProgressEvent.Close ->
                ProgressDecision(current.copy(closed = true, debounceUntil = emptyMap()), emptyList())
            is ProgressEvent.Scroll -> scroll(current, event)
            is ProgressEvent.ScrollKeepPage -> scrollKeepPage(current, event)
            is ProgressEvent.PageTurn -> pageTurn(current, event)
            is ProgressEvent.ChapterSwitch -> chapterSwitch(current, event)
            is ProgressEvent.Flush -> flush(current)
            is ProgressEvent.DebounceDue -> debounceDue(current, event)
        }
    }

    private fun scroll(current: ProgressState, e: ProgressEvent.Scroll): ProgressDecision {
        if (e.offset <= 0) return ProgressDecision(current, emptyList())
        val next = applyScrollFields(current, e.chapter, e.offset, e.page, e.total, e.ratio)
        return scheduleDebounce(next, e.chapter, e.at)
    }

    private fun scrollKeepPage(current: ProgressState, e: ProgressEvent.ScrollKeepPage): ProgressDecision {
        if (e.offset <= 0) return ProgressDecision(current, emptyList())
        val next = current.copy(
            lastKnown = current.lastKnown + (e.chapter to e.offset),
            lastRatio = applyRatio(current.lastRatio, e.chapter, e.ratio),
        )
        return scheduleDebounce(next, e.chapter, e.at)
    }

    private fun pageTurn(current: ProgressState, e: ProgressEvent.PageTurn): ProgressDecision {
        if (e.offset <= 0) return ProgressDecision(current, emptyList())
        val next = applyScrollFields(current, e.chapter, e.offset, e.page, e.total, e.ratio)
        val persist = persistOf(next, e.chapter)
        return ProgressDecision(
            if (persist != null) withSaved(next, persist) else next,
            listOfNotNull(persist),
        )
    }

    private fun chapterSwitch(current: ProgressState, e: ProgressEvent.ChapterSwitch): ProgressDecision {
        val persist = persistOf(current, e.from)
        return ProgressDecision(
            if (persist != null) withSaved(current, persist) else current,
            listOfNotNull(persist),
        )
    }

    private fun flush(current: ProgressState): ProgressDecision {
        var state = current.copy(debounceUntil = emptyMap())
        val persists = mutableListOf<ProgressPersist>()
        for (chapter in current.lastKnown.keys.toList()) {
            val persist = persistOf(state, chapter)
            if (persist != null) {
                state = withSaved(state, persist)
                persists += persist
            }
        }
        return ProgressDecision(state, persists)
    }

    private fun debounceDue(current: ProgressState, e: ProgressEvent.DebounceDue): ProgressDecision {
        // 到期时刻与记录不一致 = 已被新事件重置或取消，忽略这次迟到的到期。
        if (current.debounceUntil[e.chapter] != e.at) {
            return ProgressDecision(current, emptyList())
        }
        val next = current.copy(debounceUntil = current.debounceUntil - e.chapter)
        val persist = persistOf(next, e.chapter)
        return ProgressDecision(
            if (persist != null) withSaved(next, persist) else next,
            listOfNotNull(persist),
        )
    }

    private fun scheduleDebounce(current: ProgressState, chapter: Int, at: Long): ProgressDecision {
        val changed = current.saved[chapter] != current.lastKnown[chapter] ||
            effectiveRatio(current.savedRatio, chapter) != effectiveRatio(current.lastRatio, chapter)
        val next = if (changed) {
            current.copy(debounceUntil = current.debounceUntil + (chapter to (at + DEBOUNCE_MS)))
        } else {
            current.copy(debounceUntil = current.debounceUntil - chapter)
        }
        return ProgressDecision(next, emptyList())
    }

    private fun applyScrollFields(
        current: ProgressState,
        chapter: Int,
        offset: Int,
        page: Int,
        total: Int,
        ratio: Double,
    ): ProgressState {
        var next = current.copy(lastKnown = current.lastKnown + (chapter to offset))
        next = next.copy(lastRatio = applyRatio(next.lastRatio, chapter, ratio))
        next = if (page >= 0) {
            next.copy(lastPage = next.lastPage + (chapter to page))
        } else {
            next.copy(lastPage = next.lastPage - chapter)
        }
        next = if (total > 0) {
            next.copy(lastTotal = next.lastTotal + (chapter to total))
        } else {
            next.copy(lastTotal = next.lastTotal - chapter)
        }
        return next
    }

    private fun applyRatio(map: Map<Int, Double>, chapter: Int, ratio: Double): Map<Int, Double> =
        if (ratio in 0.0..1.0) map + (chapter to ratio) else map - chapter

    private fun persistOf(state: ProgressState, chapter: Int): ProgressPersist? {
        val offset = state.lastKnown[chapter] ?: return null
        val ratio = effectiveRatio(state.lastRatio, chapter)
        if (offset <= 0) return null
        if (
            state.saved[chapter] == offset &&
            effectiveRatio(state.savedRatio, chapter) == ratio
        ) {
            return null
        }
        return ProgressPersist(
            chapter = chapter,
            offset = offset,
            page = state.lastPage[chapter] ?: -1,
            total = state.lastTotal[chapter] ?: -1,
            ratio = ratio,
        )
    }

    private fun effectiveRatio(map: Map<Int, Double>, chapter: Int): Double = map[chapter] ?: -1.0

    private fun withSaved(state: ProgressState, persist: ProgressPersist): ProgressState = state.copy(
        saved = state.saved + (persist.chapter to persist.offset),
        savedRatio = state.savedRatio + (persist.chapter to persist.ratio),
    )
}
