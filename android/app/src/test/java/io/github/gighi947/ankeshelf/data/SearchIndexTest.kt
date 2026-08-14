package io.github.gighi947.ankeshelf.data

import io.github.gighi947.ankeshelf.service.BookSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SearchIndexTest {

    private fun session(texts: List<String>): BookSession = BookSession(
        id = "test-book",
        title = "测试书",
        author = "",
        chapters = texts.indices.map { SpineItem(it, "id$it", "c$it.xhtml") },
        textFn = { i ->
            texts.getOrNull(i)?.let { ChapterReadResult.Success(it) }
                ?: ChapterReadResult.NotFound
        },
        titleFn = { "第 ${it + 1} 章" },
        closeFn = {},
    )

    @Test
    fun perChapterLimitAndSearchMore() {
        val chapter = (0 until 120).joinToString("") { "词x" }
        val idx = SearchIndex(session(listOf(chapter)))
        idx.ensureBuiltSync()

        val resp = idx.search("词", perChapter = 50)
        assertTrue(resp.ready)
        assertEquals(120, resp.total_hits)
        assertEquals(1, resp.hit_chapters)
        assertEquals(1, resp.total_chapters)
        val g = resp.results.single()
        assertEquals(120, g.chapter_hits)
        assertTrue(g.more)
        assertEquals(50, g.hits.size)

        val last = g.hits.last().offset
        val (more1, hasMore1) = idx.searchMore("词", 0, last, perChapter = 50)
        assertEquals(50, more1.size)
        assertTrue(hasMore1)

        val last2 = more1.last().offset
        val (more2, hasMore2) = idx.searchMore("词", 0, last2, perChapter = 50)
        assertEquals(20, more2.size)
        assertFalse(hasMore2)
    }

    @Test
    fun caseSensitiveAndWholeWord() {
        val idx = SearchIndex(session(listOf("Apple apple APPLE")))
        idx.ensureBuiltSync()

        val insensitive = idx.search("apple", caseSensitive = false)
        assertEquals(3, insensitive.total_hits)

        val sensitive = idx.search("apple", caseSensitive = true)
        assertEquals(1, sensitive.total_hits)

        // 全词只约束词边界，不改变大小写匹配（与桌面 _word_re 语义一致）。
        val whole = idx.search("apple", wholeWord = true)
        assertEquals(3, whole.total_hits)

        val boundary = SearchIndex(session(listOf("cat category cat")))
        boundary.ensureBuiltSync()
        assertEquals(2, boundary.search("cat", wholeWord = true).total_hits)
    }

    @Test
    fun overlappingHitsAreCountedLikeDesktop() {
        val idx = SearchIndex(session(listOf("aaa")))
        idx.ensureBuiltSync()
        val resp = idx.search("aa")
        // 桌面 _count_hits 用 str.count（非重叠），但 _iter_hits 用 pos+1（重叠），
        // 因此总命中数=1、返回的命中偏移=[0,1]。
        assertEquals(1, resp.total_hits)
        assertEquals(listOf(0, 1), resp.results.single().hits.map { it.offset })
    }

    @Test
    fun offsetsUsePlainTextCoordinates() {
        val idx = SearchIndex(session(listOf("<p>安科</p><div>世界</div>")))
        idx.ensureBuiltSync()
        val resp = idx.search("世界")
        assertEquals(1, resp.total_hits)
        // extractDomText 对块级元素插入换行：纯文本为 "安科\n世界"，偏移 3。
        assertEquals(3, resp.results.single().hits.single().offset)
        assertEquals("安科 世界", resp.results.single().hits.single().snippet)
    }

    @Test
    fun historyDedupesAndKeepsTen() {
        val file = Files.createTempFile("search-history", ".json").toFile()
        try {
            val store = SearchHistoryStore(file)
            store.load()
            repeat(12) { i -> store.add("book1", "q$i") }
            store.add("book1", "q5")
            assertEquals(10, store.list("book1").size)
            assertEquals("q5", store.list("book1").first())
            assertEquals(1, store.list("book1").count { it == "q5" })
            assertEquals(0, store.list("book2").size)
        } finally {
            file.delete()
        }
    }
}
