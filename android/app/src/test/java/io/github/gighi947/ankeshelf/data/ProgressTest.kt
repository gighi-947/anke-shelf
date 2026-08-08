package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.File
import java.nio.file.Files

class ProgressTest {

    @Test
    fun `set get roundtrip and clamp`() {
        val store = ProgressStore(File(Files.createTempDirectory("progress").toFile(), "progress.json"))
        store.set("a".repeat(32), 3, 1204)
        store.flush()
        store.load()
        val p = store.get("a".repeat(32))
        assertEquals(3, p!!.chapter_index)
        assertEquals(1204, p.text_offset)
        assertTrue(p.updated_at.isNotEmpty())

        store.set("a".repeat(32), 1, -5)
        assertEquals(0, store.get("a".repeat(32))!!.text_offset)
        store.set("a".repeat(32), 1, 100)
        assertEquals(100, store.get("a".repeat(32))!!.text_offset)
    }

    @Test
    fun `remove and missing file`() {
        val store = ProgressStore(File(Files.createTempDirectory("progress").toFile(), "progress.json"))
        store.load()
        assertNull(store.get("a".repeat(32)))
        store.set("a".repeat(32), 1, 10)
        store.remove("a".repeat(32))
        assertNull(store.get("a".repeat(32)))
    }

    @Test
    fun `save version 2`() {
        val file = File(Files.createTempDirectory("progress").toFile(), "progress.json")
        val store = ProgressStore(file)
        store.set("a".repeat(32), 0, 0)
        store.flush()
        val data = Shelf.json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        assertEquals(2, data["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun `set persists immediately via background write`() {
        val file = File(Files.createTempDirectory("progress").toFile(), "progress.json")
        val store = ProgressStore(file)
        store.set("a".repeat(32), 2, 777)
        // 对齐桌面：每次 set 立即在后台串行线程落盘；等写盘完成再重载。
        Thread.sleep(2200)
        val reloaded = ProgressStore(file).also { it.load() }
        assertEquals(2, reloaded.get("a".repeat(32))!!.chapter_index)
        assertEquals(777, reloaded.get("a".repeat(32))!!.text_offset)
    }

    @Test
    fun `migrate`() {
        val keep = ProgressStore.migrate(
            mapOf("chapter_index" to 2, "text_offset" to 500),
            1000,
        )
        assertEquals(500, keep.text_offset)

        val ratio = ProgressStore.migrate(
            mapOf("chapter_index" to 2, "scroll_ratio" to 0.5),
            1000,
        )
        assertEquals(500, ratio.text_offset)

        val noLen = ProgressStore.migrate(
            mapOf("chapter_index" to 2, "scroll_ratio" to 0.5),
            null,
        )
        assertEquals(0, noLen.text_offset)

        val clamped = ProgressStore.migrate(
            mapOf("chapter_index" to 2, "scroll_ratio" to 1.5),
            100,
        )
        assertEquals(100, clamped.text_offset)
    }
}
