package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StatsStoreTest {

    @Test
    fun recordReadingAggregatesBookAndGlobal() {
        val file = Files.createTempFile("stats", ".json").toFile()
        try {
            val store = StatsStore(file)
            store.load()

            store.recordReading("book1", 5, pagesFlipped = 2)
            store.recordReading("book1", 5)
            store.recordReading("book2", 3)

            val b1 = store.getBook("book1")
            assertEquals(10, b1.total_seconds)
            assertEquals(2, b1.sessions)
            assertEquals(2, b1.pages_flipped)
            assertEquals(10, b1.today_seconds)
            assertEquals(2, b1.today_pages)
            assertTrue(b1.last_read_at.isNotBlank())
            assertTrue(b1.streak_days >= 1)

            val b2 = store.getBook("book2")
            assertEquals(3, b2.total_seconds)

            val g = store.getGlobal()
            assertEquals(13, g.total_seconds)
            assertEquals(3, g.sessions)
            assertEquals(2, g.pages_flipped)
            assertTrue(g.last_read_at.isNotBlank())
        } finally {
            file.delete()
        }
    }
}
