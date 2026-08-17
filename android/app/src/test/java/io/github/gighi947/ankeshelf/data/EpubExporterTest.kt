package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class EpubExporterTest {

    private val nativeDir = File("src/test/resources/reference/native/write25/book")

    @Test
    fun buildsValidEpubStructure() {
        val meta = NativeBookWriter.loadMeta(nativeDir)
        val bytes = EpubExporter.build(nativeDir, meta)

        val names = mutableListOf<String>()
        val firstEntryData = ByteArrayInputStream(bytes).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                val first = entry?.name to zip.readBytes()
                while (entry != null) {
                    names.add(entry.name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                first
            }
        }

        assertEquals("mimetype", firstEntryData.first)
        assertEquals("application/epub+zip", String(firstEntryData.second, Charsets.US_ASCII))
        assertTrue(names.contains("META-INF/container.xml"))
        assertTrue(names.contains("EPUB/content.opf"))
        assertTrue(names.any { it.startsWith("EPUB/chapters/") })
        assertTrue(names.filter { it.startsWith("EPUB/chapters/") }.size == meta.chapters.size)
    }

    @Test
    fun includesEmbeddedImagesAndRewritesLocalSrc() {
        val dir = Files.createTempDirectory("epub-export").toFile()
        val chapters = File(dir, "chapters").apply { mkdirs() }
        File(chapters, "0000.xhtml").writeText(
            "<html><body><img src=\"file:///android_images/book1/abc.jpg\"/></body></html>",
            Charsets.UTF_8,
        )
        val images = File(Files.createTempDirectory("epub-images").toFile(), "book1").apply { mkdirs() }
        File(images, "abc.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val meta = NativeMeta(
            title = "t",
            chapters = listOf(
                NativeChapterMeta(
                    file = "chapters/0000.xhtml",
                    title = "t",
                    floor_count = 1,
                    first_lou = 0,
                    last_lou = 0,
                ),
            ),
        )
        val bytes = EpubExporter.build(dir, meta, images)
        val entries = mutableMapOf<String, ByteArray>()
        ByteArrayInputStream(bytes).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        assertTrue(entries.containsKey("EPUB/images/abc.jpg"))
        val chapterText = entries.getValue("EPUB/chapters/0000.xhtml").toString(Charsets.UTF_8)
        assertTrue(chapterText.contains("src=\"images/abc.jpg\""))
        assertFalse(chapterText.contains("file:///android_images/"))
        assertTrue(entries.getValue("EPUB/content.opf").toString(Charsets.UTF_8).contains("images/abc.jpg"))
    }
}
