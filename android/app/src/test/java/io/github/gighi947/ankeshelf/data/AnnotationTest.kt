package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AnnotationTest {

    @Test
    fun `highlight crud`() {
        val store = AnnotationStore(File(Files.createTempDirectory("ann").toFile(), "annotations.json"))
        store.load()
        val h = store.addHighlight("book1", 2, 10, 20, "text", color = "blue", note = "n")
        assertEquals("blue", h.color)
        assertEquals(1, store.getHighlights("book1").size)

        val updated = store.updateAnnotation("book1", h.id, AnnotationPatch(note = "n2", color = "red"))
        assertEquals("n2", updated!!.note)
        assertEquals("blue", updated.color) // red 不在 6 色内 → 保持旧色

        assertTrue(store.deleteAnnotation("book1", h.id))
        assertFalse(store.deleteAnnotation("book1", h.id))
        assertEquals(0, store.getHighlights("book1").size)
    }

    @Test
    fun `invalid range rejected`() {
        val store = AnnotationStore(File(Files.createTempDirectory("ann").toFile(), "annotations.json"))
        try {
            store.addHighlight("b", 0, 5, 5, "x")
            throw AssertionError("should throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("高亮区间无效", e.message)
        }
    }

    @Test
    fun `bookmark crud`() {
        val store = AnnotationStore(File(Files.createTempDirectory("ann").toFile(), "annotations.json"))
        store.load()
        val bm = store.addBookmark("b", 1, 42, "mark")
        assertEquals(42, bm.offset)
        assertTrue(store.deleteBookmark("b", bm.id))
        assertFalse(store.deleteBookmark("b", bm.id))
    }

    @Test
    fun `export markdown groups by chapter`() {
        val store = AnnotationStore(File(Files.createTempDirectory("ann").toFile(), "annotations.json"))
        store.load()
        store.addHighlight("b", 0, 1, 2, "高亮文本", note = "笔记")
        store.addBookmark("b", 1, 5, "书签")
        val md = store.export("b", "markdown", "书名") { "第 ${it + 1} 章" }
        assertTrue(md.contains("# 书名 标注导出"))
        assertTrue(md.contains("## 第 1 章"))
        assertTrue(md.contains("> 高亮文本"))
        assertTrue(md.contains("笔记：笔记"))
        assertTrue(md.contains("## 第 2 章"))
        assertTrue(md.contains("🔖 书签"))
    }
}
