package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

val HL_COLORS: List<String> = listOf("yellow", "green", "blue", "pink", "purple", "cyan")

@Serializable
data class Highlight(
    val id: String,
    val chapter_index: Int,
    val start_offset: Int,
    val end_offset: Int,
    val text: String,
    val color: String = "yellow",
    val note: String = "",
    val created_at: String = "",
    val updated_at: String = "",
)

@Serializable
data class Bookmark(
    val id: String,
    val chapter_index: Int,
    val offset: Int,
    val text: String,
    val created_at: String = "",
)

@Serializable
data class AnnotationBook(
    val highlights: List<Highlight> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
)

@Serializable
data class AnnotationsFile(
    val version: Int = 1,
    val books: Map<String, AnnotationBook> = emptyMap(),
)

/** 标注存储（annotations.json）：高亮 + 书签 CRUD、导出；text_offset 坐标。 */
class AnnotationStore(private val file: File) {

    private val lock = ReentrantLock()
    private val writeGuard = StoreWriteGuard()
    private var books: MutableMap<String, AnnotationBook> = mutableMapOf()

    fun load(): List<StoreLoadIssue> {
        val (loaded, issue) = loadGuarded(file, writeGuard) { AnnotationsFile() }
        books = loaded.books.toMutableMap()
        return listOfNotNull(issue)
    }

    fun save() {
        if (writeGuard.writeBlocked()) {
            logWarn("AnkeShelf", "annotations.json 读取失败过，跳过写入以保护原文件")
            return
        }
        val snapshot = lock.withLock { books.toMap() }
        atomicWriteJson(
            file,
            Shelf.json.encodeToString(AnnotationsFile.serializer(), AnnotationsFile(books = snapshot)),
        )
    }

    private fun book(bookId: String): AnnotationBook =
        books.getOrPut(bookId) { AnnotationBook() }

    fun getHighlights(bookId: String): List<Highlight> =
        lock.withLock { book(bookId).highlights }

    fun getBookmarks(bookId: String): List<Bookmark> =
        lock.withLock { book(bookId).bookmarks }

    fun getAll(bookId: String): AnnotationBook =
        lock.withLock { book(bookId) }

    fun addHighlight(
        bookId: String,
        chapterIndex: Int,
        startOffset: Int,
        endOffset: Int,
        text: String,
        color: String = "yellow",
        note: String = "",
    ): Highlight {
        val start = maxOf(0, startOffset)
        val end = maxOf(0, endOffset)
        if (end <= start) throw IllegalArgumentException("高亮区间无效")
        val now = nowIso()
        val ann = Highlight(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            chapter_index = maxOf(0, chapterIndex),
            start_offset = start,
            end_offset = end,
            text = text.take(2000),
            color = if (color in HL_COLORS) color else "yellow",
            note = note.take(5000),
            created_at = now,
            updated_at = now,
        )
        lock.withLock {
            val b = book(bookId)
            books[bookId] = b.copy(highlights = b.highlights + ann)
            save()
        }
        return ann
    }

    fun updateAnnotation(bookId: String, annId: String, patch: AnnotationPatch): Highlight? =
        lock.withLock {
            val b = book(bookId)
            val idx = b.highlights.indexOfFirst { it.id == annId }
            if (idx < 0) return null
            val old = b.highlights[idx]
            val updated = old.copy(
                note = patch.note?.take(5000) ?: old.note,
                color = patch.color?.takeIf { it in HL_COLORS } ?: old.color,
                text = patch.text?.take(2000) ?: old.text,
                updated_at = nowIso(),
            )
            val list = b.highlights.toMutableList()
            list[idx] = updated
            books[bookId] = b.copy(highlights = list)
            save()
            updated
        }

    fun deleteAnnotation(bookId: String, annId: String): Boolean = lock.withLock {
        val b = book(bookId)
        val list = b.highlights.filterNot { it.id == annId }
        if (list.size != b.highlights.size) {
            books[bookId] = b.copy(highlights = list)
            save()
            true
        } else {
            false
        }
    }

    fun addBookmark(bookId: String, chapterIndex: Int, offset: Int, text: String): Bookmark {
        val bm = Bookmark(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            chapter_index = maxOf(0, chapterIndex),
            offset = maxOf(0, offset),
            text = text.take(200),
            created_at = nowIso(),
        )
        lock.withLock {
            val b = book(bookId)
            books[bookId] = b.copy(bookmarks = b.bookmarks + bm)
            save()
        }
        return bm
    }

    fun deleteBookmark(bookId: String, bmId: String): Boolean = lock.withLock {
        val b = book(bookId)
        val list = b.bookmarks.filterNot { it.id == bmId }
        if (list.size != b.bookmarks.size) {
            books[bookId] = b.copy(bookmarks = list)
            save()
            true
        } else {
            false
        }
    }

    fun removeBook(bookId: String) {
        lock.withLock {
            if (books.remove(bookId) != null) save()
        }
    }

    /** 导出为 markdown 或 json（chapterTitleFn(index) -> 章节标题）。 */
    fun export(
        bookId: String,
        fmt: String,
        bookTitle: String,
        chapterTitleFn: (Int) -> String,
    ): String {
        val snapshot = lock.withLock {
            val b = book(bookId)
            b.highlights to b.bookmarks
        }
        if (fmt == "json") {
            return Shelf.json.encodeToString(
                ExportJson.serializer(),
                ExportJson(book = bookTitle, highlights = snapshot.first, bookmarks = snapshot.second),
            )
        }
        val lines = mutableListOf("# $bookTitle 标注导出", "")
        val byChapter = sortedMapOf<Int, MutableList<Pair<String, Any>>>()
        snapshot.first.forEach { byChapter.getOrPut(it.chapter_index) { mutableListOf() }.add("hl" to it) }
        snapshot.second.forEach { byChapter.getOrPut(it.chapter_index) { mutableListOf() }.add("bm" to it) }
        for ((ci, items) in byChapter) {
            lines.add("## ${chapterTitleFn(ci)}")
            for ((kind, item) in items) {
                if (kind == "hl") {
                    val h = item as Highlight
                    lines.add("> ${h.text}")
                    if (h.note.isNotEmpty()) lines.add("笔记：${h.note}")
                } else {
                    val bm = item as Bookmark
                    lines.add("🔖 ${bm.text}")
                }
                lines.add("")
            }
        }
        return lines.joinToString("\n")
    }
}

data class AnnotationPatch(
    val note: String? = null,
    val color: String? = null,
    val text: String? = null,
)

@Serializable
private data class ExportJson(
    val book: String,
    val highlights: List<Highlight>,
    val bookmarks: List<Bookmark>,
)
