package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class BookRecord(
    val id: String,
    val path: String,
    val title: String,
    val author: String = "",
    val language: String = "",
    val chapter_count: Int = 0,
    val cover_rel: String? = null,
    val file_size: Long = 0,
    val file_mtime: String = "",
    val added_at: String = "",
    val last_read_at: String = "",
    val nga_tid: Int = 0,
) {
    @Transient
    var progressPct: Double = 0.0
}

@Serializable
data class ShelfFile(
    val version: Int = 1,
    val books: List<BookRecord> = emptyList(),
)

@Serializable
data class ProgressEntry(
    val chapter_index: Int = 0,
    val text_offset: Int = 0,
    val updated_at: String = "",
)

@Serializable
data class ProgressFile(
    val version: Int = 2,
    val progress: Map<String, ProgressEntry> = emptyMap(),
)

/** 书架：书籍元数据（JSON，原子写，最近阅读降序）。 */
class Shelf(private val shelfFile: File, private val coversDir: File) {

    private val lock = ReentrantLock()
    private val writeLock = ReentrantLock()
    private var books: MutableMap<String, BookRecord> = mutableMapOf()

    fun load() {
        val data = readJsonOrNull<ShelfFile>(shelfFile) ?: ShelfFile()
        lock.withLock {
            books = data.books.associateBy { it.id }.toMutableMap()
        }
    }

    fun save() {
        val snapshot = lock.withLock { books.values.toList() }
        writeLock.withLock {
            atomicWriteJson(shelfFile, json.encodeToString(ShelfFile.serializer(), ShelfFile(books = snapshot)))
        }
    }

    fun listBooks(): List<BookRecord> =
        lock.withLock { books.values.toList() }.sortedByDescending { it.last_read_at }

    fun get(bookId: String): BookRecord? = lock.withLock { books[bookId] }

    fun upsert(rec: BookRecord) {
        lock.withLock {
            val old = books[rec.id]
            var updated = rec
            if (old != null && old.file_mtime == rec.file_mtime) {
                updated = rec.copy(last_read_at = old.last_read_at, added_at = old.added_at)
            }
            if (updated.added_at.isEmpty()) updated = updated.copy(added_at = nowIso())
            books[rec.id] = updated
        }
    }

    fun remove(bookId: String) {
        val rec = lock.withLock { books.remove(bookId) }
        if (rec != null && rec.cover_rel != null) {
            try {
                File(coversDir, rec.cover_rel.substringAfterLast('/')).delete()
            } catch (_: Exception) {
            }
        }
    }

    fun touch(bookId: String, throttleSeconds: Double = 60.0) {
        val now = nowIso()
        lock.withLock {
            val rec = books[bookId] ?: return
            val last = rec.last_read_at
            if (last.isNotEmpty()) {
                try {
                    val dt = OffsetDateTime.parse(last)
                    if ((System.currentTimeMillis() / 1000.0 - dt.toEpochSecond()) < throttleSeconds) return
                } catch (_: Exception) {
                }
            }
            books[bookId] = rec.copy(last_read_at = now)
            save()
        }
    }

    fun extractCover(book: EpubBook): String? {
        val data = book.getCoverBytes() ?: return null
        val ext = sniffImageExt(data)
        val rel = "covers/${book.id}.$ext"
        return try {
            coversDir.mkdirs()
            File(coversDir, "${book.id}.$ext").writeBytes(data)
            rel
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

fun sniffImageExt(data: ByteArray): String {
    if (data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() &&
        data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte()
    ) return "png"
    if (data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) return "jpg"
    if (data.size >= 4 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() &&
        data[2] == 'F'.code.toByte() && data[3] == '8'.code.toByte()
    ) return "gif"
    if (data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
        data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
        data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
        data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte()
    ) return "webp"
    val head = String(data, 0, minOf(data.size, 64), Charsets.ISO_8859_1)
    if (head.trimStart().startsWith("<svg")) return "svg"
    return "jpg"
}

/** 阅读进度：{book_id: ProgressEntry}，text_offset 坐标，原子写。 */
class ProgressStore(private val progressFile: File) {

    private val lock = ReentrantLock()
    // 文件写锁：主线程 flush 与后台 set 写盘串行，避免并发写同一个 .tmp 崩溃/损坏。
    private val writeLock = ReentrantLock()
    private var data: MutableMap<String, ProgressEntry> = mutableMapOf()
    // 对齐桌面 ProgressStore.set：每次保存立即落盘；安卓把写盘放到串行后台线程，
    // 不阻塞 UI（桌面是 Python 后台线程写盘，语义一致）。
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "progress-io").apply { isDaemon = true }
    }

    fun load() {
        val loaded = readJsonOrNull<ProgressFile>(progressFile) ?: ProgressFile()
        lock.withLock { data = loaded.progress.toMutableMap() }
    }

    fun save() {
        val snapshot = lock.withLock { data.toMap() }
        writeLock.withLock {
            atomicWriteJson(
                progressFile,
                Shelf.json.encodeToString(ProgressFile.serializer(), ProgressFile(progress = snapshot)),
            )
        }
    }

    fun get(bookId: String): ProgressEntry? = lock.withLock { data[bookId] }

    fun set(bookId: String, chapterIndex: Int, textOffset: Int) {
        runCatching { android.util.Log.d("AnkeShelf", "progress.set ch=$chapterIndex off=$textOffset") }
        val entry = ProgressEntry(
            chapter_index = maxOf(0, chapterIndex),
            text_offset = maxOf(0, textOffset),
            updated_at = nowIso(),
        )
        lock.withLock { data[bookId] = entry }
        io.execute { runCatching { save() } }
    }

    /** 立即同步落盘（退出阅读器/切章/退后台前调用），幂等。 */
    fun flush() = save()

    fun remove(bookId: String) {
        lock.withLock {
            if (data.remove(bookId) != null) save()
        }
    }

    companion object {
        /** 旧 scroll_ratio 记录 → text_offset（chapterLen 为章纯文本长）。 */
        fun migrate(old: Map<String, Any?>, chapterLen: Int?): ProgressEntry {
            if (old.containsKey("text_offset")) {
                return ProgressEntry(
                    chapter_index = (old["chapter_index"] as? Number)?.toInt() ?: 0,
                    text_offset = (old["text_offset"] as? Number)?.toInt() ?: 0,
                    updated_at = old["updated_at"] as? String ?: "",
                )
            }
            val ratio = (old["scroll_ratio"] as? Number)?.toDouble() ?: 0.0
            val textOffset = if (chapterLen != null) {
                kotlin.math.round(ratio.coerceIn(0.0, 1.0) * chapterLen).toInt()
            } else {
                0
            }
            return ProgressEntry(
                chapter_index = (old["chapter_index"] as? Number)?.toInt() ?: 0,
                text_offset = textOffset,
                updated_at = old["updated_at"] as? String ?: "",
            )
        }
    }
}

internal inline fun <reified T> readJsonOrNull(file: File): T? {
    return try {
        if (!file.exists()) null
        else Shelf.json.decodeFromString<T>(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }
}
