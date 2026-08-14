package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.File
import java.nio.file.Files

class ShelfTest {

    private fun makeRec(path: String, title: String, mtime: String = "2026-01-01T00:00:00+08:00") =
        BookRecord(
            id = "a".repeat(32),
            path = path,
            title = title,
            author = "作者",
            chapter_count = 10,
            file_mtime = mtime,
        )

    @Test
    fun `upsert list sort roundtrip`() {
        val dir = Files.createTempDirectory("shelf").toFile()
        val covers = File(dir, "covers").apply { mkdirs() }
        val shelf = Shelf(File(dir, "shelf.json"), covers)
        val a = makeRec("/a.epub", "甲")
        val b = makeRec("/b.epub", "乙").copy(id = "b".repeat(32))
        shelf.upsert(a)
        shelf.upsert(b)
        shelf.save()

        val shelf2 = Shelf(File(dir, "shelf.json"), covers)
        shelf2.load()
        assertEquals(2, shelf2.listBooks().size)
        assertEquals("甲", shelf2.get("a".repeat(32))?.title)
    }

    @Test
    fun `touch sorts to front and throttles`() {
        val dir = Files.createTempDirectory("shelf").toFile()
        val covers = File(dir, "covers").apply { mkdirs() }
        val shelf = Shelf(File(dir, "shelf.json"), covers)
        val a = makeRec("/a.epub", "甲")
        val b = makeRec("/b.epub", "乙").copy(id = "b".repeat(32))
        shelf.upsert(a)
        shelf.upsert(b)
        shelf.save()
        shelf.touch("b".repeat(32))
        shelf.save()
        assertEquals("b".repeat(32), shelf.listBooks()[0].id)

        shelf.touch("a".repeat(32), throttleSeconds = 3600.0)
        val first = shelf.get("a".repeat(32))!!.last_read_at
        shelf.touch("a".repeat(32), throttleSeconds = 3600.0)
        assertEquals(first, shelf.get("a".repeat(32))!!.last_read_at)
    }

    @Test
    fun `upsert same mtime keeps last read`() {
        val dir = Files.createTempDirectory("shelf").toFile()
        val covers = File(dir, "covers").apply { mkdirs() }
        val shelf = Shelf(File(dir, "shelf.json"), covers)
        shelf.upsert(makeRec("/a.epub", "甲"))
        shelf.touch("a".repeat(32), throttleSeconds = 0.0)
        shelf.upsert(makeRec("/a.epub", "甲"))
        assertTrue(shelf.get("a".repeat(32))!!.last_read_at.isNotEmpty())
    }

    @Test
    fun `remove deletes cover`() {
        val dir = Files.createTempDirectory("shelf").toFile()
        val covers = File(dir, "covers").apply { mkdirs() }
        val shelf = Shelf(File(dir, "shelf.json"), covers)
        val rec = makeRec("/a.epub", "甲").copy(cover_rel = "covers/${"a".repeat(32)}.png")
        shelf.upsert(rec)
        val cover = File(covers, "${"a".repeat(32)}.png").apply { writeBytes(byteArrayOf(1)) }
        shelf.remove("a".repeat(32))
        assertFalse(cover.exists())
        assertNull(shelf.get("a".repeat(32)))
    }

    @Test
    fun `atomic write valid json and corrupt load`() {
        val dir = Files.createTempDirectory("shelf").toFile()
        val covers = File(dir, "covers").apply { mkdirs() }
        val shelf = Shelf(File(dir, "shelf.json"), covers)
        shelf.upsert(makeRec("/a.epub", "甲"))
        shelf.save()
        val data = Shelf.json.parseToJsonElement(shelfFile(dir).readText(Charsets.UTF_8)).jsonObject
        assertEquals(1, data["version"]!!.jsonPrimitive.int)
        assertEquals(emptyList<File>(), dir.listFiles()?.filter { it.name.endsWith(".tmp") })

        shelfFile(dir).writeText("{corrupt", Charsets.UTF_8)
        shelf.load()
        assertEquals(0, shelf.listBooks().size)
        assertFalse(shelfFile(dir).exists())
        assertEquals(
            1,
            dir.listFiles()?.count { it.name.startsWith("shelf.json.corrupt-") },
        )
    }

    private fun shelfFile(dir: File) = File(dir, "shelf.json")

    @Test
    fun `sniff image ext`() {
        assertEquals("png", sniffImageExt(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals("jpg", sniffImageExt(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("gif", sniffImageExt("GIF89a".toByteArray()))
        assertEquals("webp", sniffImageExt("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()))
        assertEquals("svg", sniffImageExt("<svg>".toByteArray()))
        assertEquals("jpg", sniffImageExt("?????".toByteArray()))
    }
}
