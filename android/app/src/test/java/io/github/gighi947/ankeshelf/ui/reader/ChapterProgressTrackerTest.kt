package io.github.gighi947.ankeshelf.ui.reader

import io.github.gighi947.ankeshelf.data.ProgressEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterProgressTrackerTest {

    private class FakeStore {
        val entries = mutableMapOf<String, ProgressEntry>()
        val persisted = mutableListOf<Triple<String, Int, Int>>()

        fun restore(bookId: String): ProgressEntry? = entries[bookId]

        fun persist(bookId: String, chapter: Int, offset: Int) {
            persisted += Triple(bookId, chapter, offset)
            entries[bookId] = ProgressEntry(chapter_index = chapter, text_offset = offset, updated_at = "t")
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
        val store = FakeStore().apply { entries["book"] = ProgressEntry(2, 123, "t") }
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
        assertTrue(store.persisted.contains(Triple("book", 0, 100)))
        val afterFirst = store.persisted.size

        t.onOffset(0, 100)
        Thread.sleep(700)
        assertEquals(afterFirst, store.persisted.size)

        t.onOffset(0, 200)
        Thread.sleep(700)
        assertTrue(store.persisted.contains(Triple("book", 0, 200)))
    }

    @Test
    fun `page turn persists immediately`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onPageTurn(3, 777)
        assertTrue(store.persisted.contains(Triple("book", 3, 777)))
    }

    @Test
    fun `chapter switch flushes old chapter without waiting for debounce`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 111)
        t.onChapterSwitch(0, 1)
        assertTrue(store.persisted.contains(Triple("book", 0, 111)))
    }

    @Test
    fun `flush cancels pending debounce and persists all known chapters`() {
        val store = FakeStore()
        val t = tracker(store)
        t.onOffset(0, 999)
        t.onOffset(3, 300)
        t.onOffset(1, 100)
        t.flush()
        assertTrue(store.persisted.contains(Triple("book", 0, 999)))
        assertTrue(store.persisted.contains(Triple("book", 3, 300)))
        assertTrue(store.persisted.contains(Triple("book", 1, 100)))

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
}
