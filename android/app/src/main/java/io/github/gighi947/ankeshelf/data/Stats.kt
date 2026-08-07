package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class DayEntry(
    val seconds: Int = 0,
    val pages: Int = 0,
)

@Serializable
data class StatsEntry(
    val total_seconds: Int = 0,
    val sessions: Int = 0,
    val pages_flipped: Int = 0,
    val last_read_at: String = "",
    val days: Map<String, DayEntry> = emptyMap(),
)

@Serializable
data class StatsFile(
    val version: Int = 1,
    val books: Map<String, StatsEntry> = emptyMap(),
    val global: StatsEntry = StatsEntry(),
)

data class EnrichedStats(
    val total_seconds: Int,
    val sessions: Int,
    val pages_flipped: Int,
    val last_read_at: String,
    val days: Map<String, DayEntry>,
    val today_seconds: Int,
    val today_pages: Int,
    val week_seconds: Int,
    val avg_session_seconds: Int,
    val streak_days: Int,
)

/** 阅读统计（statistics.json）：全局 + 每书 + 按天。 */
class StatsStore(private val file: File) {

    private val lock = ReentrantLock()
    private var books: MutableMap<String, StatsEntry> = mutableMapOf()
    private var global: StatsEntry = StatsEntry()

    fun load() {
        val data = readJsonOrNull<StatsFile>(file) ?: StatsFile()
        lock.withLock {
            books = data.books.toMutableMap()
            global = data.global
        }
    }

    fun save() {
        val snapshot = lock.withLock { books.toMap() to global }
        atomicWriteJson(
            file,
            Shelf.json.encodeToString(
                StatsFile.serializer(),
                StatsFile(books = snapshot.first, global = snapshot.second),
            ),
        )
    }

    fun recordReading(bookId: String, seconds: Int, pagesFlipped: Int = 0) {
        val secs = maxOf(0, seconds)
        val pages = maxOf(0, pagesFlipped)
        val today = LocalDate.now().toString()
        lock.withLock {
            var b = books[bookId] ?: StatsEntry()
            var day = b.days[today] ?: DayEntry()
            b = b.copy(
                total_seconds = b.total_seconds + secs,
                pages_flipped = if (pages > 0) b.pages_flipped + pages else b.pages_flipped,
                sessions = if (secs > 0) b.sessions + 1 else b.sessions,
                last_read_at = if (secs > 0) nowIso() else b.last_read_at,
            )
            if (secs > 0) {
                day = DayEntry(seconds = day.seconds + secs, pages = day.pages + pages)
                b = b.copy(days = b.days + (today to day))
            }
            books[bookId] = b

            var g = global
            var gDay = g.days[today] ?: DayEntry()
            g = g.copy(total_seconds = g.total_seconds + secs)
            if (secs > 0) {
                gDay = DayEntry(seconds = gDay.seconds + secs, pages = gDay.pages + pages)
                g = g.copy(days = g.days + (today to gDay))
            }
            global = g
            save()
        }
    }

    fun getBook(bookId: String): EnrichedStats =
        lock.withLock { enrich(books[bookId] ?: StatsEntry()) }

    fun getGlobal(): EnrichedStats = lock.withLock { enrich(global) }

    fun removeBook(bookId: String) {
        lock.withLock {
            if (books.remove(bookId) != null) save()
        }
    }

    private fun enrich(rec: StatsEntry): EnrichedStats {
        val today = LocalDate.now().toString()
        val weekStart = LocalDate.now().minusDays(6).toString()
        val todaySecs = rec.days[today]?.seconds ?: 0
        val todayPages = rec.days[today]?.pages ?: 0
        val weekSecs = rec.days
            .filterKeys { it >= weekStart && it <= today }
            .values.sumOf { it.seconds }
        return EnrichedStats(
            total_seconds = rec.total_seconds,
            sessions = rec.sessions,
            pages_flipped = rec.pages_flipped,
            last_read_at = rec.last_read_at,
            days = rec.days,
            today_seconds = todaySecs,
            today_pages = todayPages,
            week_seconds = weekSecs,
            avg_session_seconds = if (rec.sessions > 0) kotlin.math.round(rec.total_seconds.toDouble() / rec.sessions).toInt() else 0,
            streak_days = streakDays(rec.days),
        )
    }

    private fun streakDays(days: Map<String, DayEntry>): Int {
        var d = LocalDate.now()
        if (days[d.toString()] == null) d = d.minusDays(1)
        var streak = 0
        while (true) {
            val entry = days[d.toString()] ?: break
            if (entry.seconds <= 0) break
            streak++
            d = d.minusDays(1)
        }
        return streak
    }
}
