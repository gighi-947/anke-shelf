package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StatsTest {

    @Test
    fun `record and enrich`() {
        val store = StatsStore(File(Files.createTempDirectory("stats").toFile(), "statistics.json"))
        store.load()
        store.recordReading("book1", 30, 5)
        store.recordReading("book1", 30, 0)
        val b = store.getBook("book1")
        assertEquals(60, b.total_seconds)
        assertEquals(2, b.sessions)
        assertEquals(5, b.pages_flipped)
        assertEquals(30, b.avg_session_seconds)
        assertEquals(60, b.today_seconds)
        assertEquals(5, b.today_pages)
        assertEquals(60, b.week_seconds)
        assertTrue(b.last_read_at.isNotEmpty())

        val g = store.getGlobal()
        assertEquals(60, g.total_seconds)
        assertEquals(60, g.today_seconds)
    }

    @Test
    fun `empty book zeros`() {
        val store = StatsStore(File(Files.createTempDirectory("stats").toFile(), "statistics.json"))
        store.load()
        val b = store.getBook("missing")
        assertEquals(0, b.total_seconds)
        assertEquals(0, b.avg_session_seconds)
        assertEquals(0, b.streak_days)
    }
}
