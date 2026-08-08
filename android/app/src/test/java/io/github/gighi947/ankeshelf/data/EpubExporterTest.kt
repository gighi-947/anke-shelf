package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
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
}
