package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageTest {

    @Test
    fun `atomic write json and text leave no tmp`() {
        val dir = Files.createTempDirectory("storage").toFile()
        val p = File(dir, "data.json")
        atomicWriteJson(p, """{"a": 1}""")
        assertEquals("""{"a": 1}""", p.readText(Charsets.UTF_8))
        assertEquals(emptyList<File>(), dir.listFiles()?.filter { it.name.endsWith(".tmp") })

        val meta = File(dir, "meta.txt")
        atomicWriteText(meta, "hello")
        assertEquals("hello", meta.readText(Charsets.UTF_8))
        assertEquals(emptyList<File>(), dir.listFiles()?.filter { it.name.endsWith(".tmp") })
    }

    @Test
    fun `nowIso format`() {
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+\d{2}:\d{2}$""").matches(nowIso()))
    }
}
