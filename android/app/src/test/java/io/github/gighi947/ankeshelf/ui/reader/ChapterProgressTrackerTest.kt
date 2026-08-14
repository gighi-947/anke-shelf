package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.ProgressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterProgressTrackerTest {

    private class FakeStore {
        val entries = mutableMapOf<String, ProgressEntry>()
        val persisted = mutableListOf<Persisted>()

        data class Persisted(
            val chapter: Int,
            val offset: Int,
            val page: Int,
            val total: Int,
            val ratio: Double,
        )

        fun restore(bookId: String): ProgressEntry? = entries[bookId]

        fun persist(bookId: String, chapter: Int, offset: Int, page: Int, total: Int, ratio: Double) {
            persisted += Persisted(chapter, offset, page, total, ratio)
            entries[bookId] = ProgressEntry(
                chapter_index = chapter,
                text_offset = offset,
                page_index = page,
                page_total = total,
                scroll_ratio = ratio,
                updated_at = "t",
            )
        }
    }

    private fun tracker(
        store: FakeStore,
        bookId: String = "book",
        initialChapter: Int = 0,
        initialOffset: Int = 0,
    ) = ChapterProgressTracker(
        bookId = bookId,
        initialChapter = initialChapter,
        initialOffset = initialOffset,
        restoreFrom = store::restore,
        persist = store::persist,
    )

    @Test
    fun `restore prefers persisted entry and in-memory updates`() {
        val store = FakeStore().apply {
            entries["book"] = ProgressEntry(chapter_index = 2, text_offset = 123, updated_at = "t")
        }
        val t = tracker(store, initialChapter = 2, initialOffset = 123)
        assertEquals(123, t.restoreOffsetFor(2))

        t.onOffset(2, 456)
        assertEquals(456, t.restoreOffsetFor(2))
        assertEquals(0, t.restoreOffsetFor(5))
    }

    @Test
    fun `scroll offset persists after debounce and identical offset dedups`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 100)
        Thread.sleep(700)
        assertTrue(store.persisted.any { it.chapter == 0 && it.offset == 100 && it.ratio == -1.0 })
        val afterFirst = store.persisted.size

        t.onOffset(0, 100)
        Thread.sleep(700)
        assertEquals(afterFirst, store.persisted.size)

        t.onOffset(0, 200)
        Thread.sleep(700)
        assertTrue(store.persisted.any { it.chapter == 0 && it.offset == 200 })
    }

    @Test
    fun `page turn persists immediately`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onPageTurn(3, 777)
        assertTrue(store.persisted.any { it.chapter == 3 && it.offset == 777 })
    }

    @Test
    fun `chapter switch flushes old chapter without waiting for debounce`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 111)
        t.onChapterSwitch(0)
        assertTrue(store.persisted.any { it.chapter == 0 && it.offset == 111 })
    }

    @Test
    fun `flush cancels pending debounce and persists all known chapters`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 999)
        t.onOffset(3, 300)
        t.onOffset(1, 100)
        t.flush()
        assertTrue(store.persisted.any { it.chapter == 0 && it.offset == 999 })
        assertTrue(store.persisted.any { it.chapter == 3 && it.offset == 300 })
        assertTrue(store.persisted.any { it.chapter == 1 && it.offset == 100 })

        val afterFlush = store.persisted.size
        Thread.sleep(700)
        assertEquals(afterFlush, store.persisted.size)
    }

    @Test
    fun `zero and negative offsets are ignored`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 0)
        t.onPageTurn(0, -5)
        t.flush()
        assertTrue(store.persisted.isEmpty())
    }

    @Test
    fun `scroll ratio persists and restores for image-only viewport`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 500, ratio = 0.5)
        t.flush()
        assertEquals(0.5, store.entries["book"]?.scroll_ratio ?: -2.0, 0.001)

        // 新 tracker 从持久化条目恢复比例锚点。
        val t2 = tracker(store, initialChapter = 0, initialOffset = 500)
        assertEquals(0.5, t2.restoreRatioFor(0), 0.001)
        assertEquals(500, t2.restoreOffsetFor(0))
    }

    @Test
    fun `same offset with different ratio must persist again`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 100, ratio = -1.0)
        t.flush()
        val afterText = store.persisted.size

        // 文本锚点 → 全图页：offset 恰好相同但比例不同，必须重新落盘。
        t.onOffset(0, 100, ratio = 0.4)
        t.flush()
        assertTrue(store.persisted.size > afterText)
        assertTrue(store.persisted.any { it.chapter == 0 && it.offset == 100 && it.ratio == 0.4 })
        assertEquals(0.4, t.restoreRatioFor(0), 0.001)
    }

    @Test
    fun `scroll ratio clears when text anchor becomes available`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 300, ratio = 0.7)
        t.flush()
        assertEquals(0.7, t.restoreRatioFor(0), 0.001)

        // 滚动到有文本的位置：比例锚点清除，恢复走 text_offset。
        t.onOffset(0, 320, ratio = -1.0)
        t.flush()
        assertEquals(-1.0, t.restoreRatioFor(0), 0.001)
        assertTrue(store.persisted.any { it.chapter == 0 && it.offset == 320 && it.ratio == -1.0 })
    }

    @Test
    fun `close shuts down private scheduler`() {
        val tracker = tracker(FakeStore())
        tracker.onOffset(0, 100)
        val field = ChapterProgressTracker::class.java.getDeclaredField("scheduler").apply {
            isAccessible = true
        }
        val scheduler = field.get(tracker) as java.util.concurrent.ScheduledExecutorService

        tracker.close()

        assertTrue(scheduler.isShutdown)
    }
}
