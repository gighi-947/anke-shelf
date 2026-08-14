package io.github.gighi947.ankeshelf.data

import io.github.gighi947.ankeshelf.service.LogEvents
import io.github.gighi947.ankeshelf.service.BookSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class SearchHit(
    val offset: Int,
    val snippet: String,
)

data class SearchChapterGroup(
    val chapter_index: Int,
    val chapter_title: String,
    val text_len: Int,
    val chapter_hits: Int,
    val more: Boolean,
    val hits: List<SearchHit>,
)

data class SearchResponse(
    val ready: Boolean,
    val total_hits: Int = 0,
    val hit_chapters: Int = 0,
    val total_chapters: Int = 0,
    val results: List<SearchChapterGroup> = emptyList(),
)

/**
 * 全文搜索：惰性内存索引 + 子串查询（对齐桌面 app/search.py）。
 *
 * - 中文无需分词，子串匹配天然支持；
 * - 索引按需构建、不落盘，避免陈旧索引；
 * - 每章限量返回（默认 50），靠后章节的高频词不会被前几章占满；
 * - 命中 offset 与进度/标注共用 text_offset 坐标系（TextExtractor 同折叠规则）。
 */
class SearchIndex(private val session: BookSession) {

    private data class ChapterText(
        val index: Int,
        val title: String,
        val text: String,
    )

    private val lock = ReentrantLock()
    private var chapters: List<ChapterText>? = null
    private var building = false

    val totalChapters: Int get() = session.chapters.size

    fun isReady(): Boolean = lock.withLock { chapters != null }

    /** 后台构建索引；重复调用安全。 */
    fun ensureBuilt(scope: CoroutineScope) {
        lock.withLock {
            if (chapters != null || building) return
            building = true
        }
        scope.launch(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            val list = (0 until session.chapters.size).map { i ->
                val raw = session.chapterText(i)
                ChapterText(
                    index = i,
                    title = session.chapterTitle(i),
                    text = if (raw != null) TextExtractor.extractDomText(raw) else "",
                )
            }
            lock.withLock { chapters = list }
            LogEvents.event(
                "search",
                "index_built",
                "task_id" to ("search-" + LogEvents.bookIdHash(session.id)),
                "book_id_hash" to LogEvents.bookIdHash(session.id),
                "chapters" to list.size,
                "duration_ms" to (System.currentTimeMillis() - t0),
            )
        }
    }

    /** 同步构建（JVM 测试用）。 */
    fun ensureBuiltSync() {
        lock.withLock {
            if (chapters != null || building) return
            building = true
        }
        val t0 = System.currentTimeMillis()
        val list = (0 until session.chapters.size).map { i ->
            val raw = session.chapterText(i)
            ChapterText(
                index = i,
                title = session.chapterTitle(i),
                text = if (raw != null) TextExtractor.extractDomText(raw) else "",
            )
        }
        lock.withLock { chapters = list }
        LogEvents.event(
            "search",
            "index_built",
            "task_id" to ("search-" + LogEvents.bookIdHash(session.id)),
            "book_id_hash" to LogEvents.bookIdHash(session.id),
            "chapters" to list.size,
            "duration_ms" to (System.currentTimeMillis() - t0),
        )
    }

    /** 释放底层书籍会话（页面销毁/换书时调用）。 */
    fun close() {
        session.close()
    }

    fun search(
        query: String,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        perChapter: Int = 50,
        snippetLen: Int = 40,
    ): SearchResponse {
        val chapters = lock.withLock { chapters }
            ?: return SearchResponse(ready = false, total_chapters = session.chapters.size)
        val q = query.trim()
        if (q.isEmpty()) {
            return SearchResponse(
                ready = true,
                total_chapters = chapters.size,
            )
        }
        var totalHits = 0
        var hitChapters = 0
        val results = ArrayList<SearchChapterGroup>()
        for (ch in chapters) {
            val hits = ArrayList<SearchHit>()
            var more = false
            var n = 0
            var pos = nextHit(ch.text, q, 0, caseSensitive, wholeWord)
            while (pos >= 0) {
                if (n >= perChapter) {
                    more = true
                    break
                }
                hits.add(SearchHit(pos, makeSnippet(ch.text, pos, q, snippetLen)))
                n++
                pos = nextHit(ch.text, q, pos + 1, caseSensitive, wholeWord)
            }
            val chTotal = countHits(ch.text, q, caseSensitive, wholeWord)
            totalHits += chTotal
            if (hits.isNotEmpty()) {
                hitChapters++
                results.add(
                    SearchChapterGroup(
                        chapter_index = ch.index,
                        chapter_title = ch.title,
                        text_len = ch.text.length,
                        chapter_hits = chTotal,
                        more = more || chTotal > n,
                        hits = hits,
                    ),
                )
            }
        }
        return SearchResponse(
            ready = true,
            total_hits = totalHits,
            hit_chapters = hitChapters,
            total_chapters = chapters.size,
            results = results,
        )
    }

    fun searchMore(
        query: String,
        chapterIndex: Int,
        afterOffset: Int,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        perChapter: Int = 50,
        snippetLen: Int = 40,
    ): Pair<List<SearchHit>, Boolean> {
        val chapters = lock.withLock { chapters } ?: return Pair(emptyList<SearchHit>(), false)
        val q = query.trim()
        if (q.isEmpty()) return Pair(emptyList<SearchHit>(), false)
        val ch = chapters.firstOrNull { it.index == chapterIndex }
            ?: return Pair(emptyList<SearchHit>(), false)
        var pos = nextHit(ch.text, q, maxOf(0, afterOffset) + 1, caseSensitive, wholeWord)
        val hits = ArrayList<SearchHit>()
        var n = 0
        var more = false
        while (n < perChapter) {
            if (pos < 0) {
                more = false
                break
            }
            hits.add(SearchHit(pos, makeSnippet(ch.text, pos, q, snippetLen)))
            n++
            pos = nextHit(ch.text, q, pos + 1, caseSensitive, wholeWord)
        }
        if (n >= perChapter) {
            // 已取满 perChapter，再试探一条判断是否还有更多。
            more = nextHit(ch.text, q, if (hits.isNotEmpty()) hits.last().offset + 1 else 0, caseSensitive, wholeWord) >= 0
        }
        return hits to more
    }

    private fun nextHit(
        text: String,
        q: String,
        start: Int,
        caseSensitive: Boolean,
        wholeWord: Boolean,
    ): Int {
        val hay = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) q else q.lowercase()
        if (wholeWord) {
            val rx = wordRegex(needle)
            return rx.find(hay, start)?.range?.first ?: -1
        }
        return hay.indexOf(needle, start)
    }

    private fun countHits(text: String, q: String, caseSensitive: Boolean, wholeWord: Boolean): Int {
        val hay = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) q else q.lowercase()
        return if (wholeWord) {
            wordRegex(needle).findAll(hay).count()
        } else {
            Regex.escape(needle).toRegex().findAll(hay).count()
        }
    }

    private fun makeSnippet(text: String, pos: Int, q: String, snippetLen: Int): String {
        val s = maxOf(0, pos - snippetLen)
        val e = minOf(text.length, pos + q.length + snippetLen)
        return text.substring(s, e)
    }

    private fun wordRegex(needle: String): Regex =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(needle) + "(?![A-Za-z0-9_])")
}

@Serializable
data class SearchHistoryFile(
    val version: Int = 1,
    val history: Map<String, List<String>> = emptyMap(),
)

/** 搜索历史：每书 ≤10 条（对齐桌面 localStorage 语义，落盘便于跨会话保留）。 */
class SearchHistoryStore(private val file: File) {

    private val lock = ReentrantLock()
    private var data: MutableMap<String, List<String>> = mutableMapOf()

    fun load() {
        val loaded = when (val r = readJsonStore<SearchHistoryFile>(file)) {
            is StoreLoadResult.Ok -> r.value
            StoreLoadResult.Missing -> SearchHistoryFile()
            is StoreLoadResult.Corrupt -> {
                logWarn("AnkeShelf", "search_history.json 损坏，回退默认：${r.detail}")
                SearchHistoryFile()
            }
            is StoreLoadResult.IoError -> {
                logWarn("AnkeShelf", "search_history.json 读取失败：${r.detail}")
                SearchHistoryFile()
            }
        }
        lock.withLock { data = loaded.history.toMutableMap() }
    }

    fun list(bookId: String): List<String> = lock.withLock { data[bookId] ?: emptyList() }

    fun add(bookId: String, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        lock.withLock {
            val list = (data[bookId] ?: emptyList()).filter { it != q }.toMutableList()
            list.add(0, q)
            data[bookId] = list.take(10)
            atomicWriteJson(
                file,
                Shelf.json.encodeToString(SearchHistoryFile.serializer(), SearchHistoryFile(history = data.toMap())),
            )
        }
    }
}
