package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

class EpubTest {

    @Test
    fun `isZipFile normal corrupt bad missing`() {
        assertTrue(isZipFile(SampleEpubs.copy("nav3")))
        assertTrue(isZipFile(SampleEpubs.copy("corrupt")))
        assertFalse(isZipFile(SampleEpubs.copy("bad")))
        assertFalse(isZipFile(File(Files.createTempDirectory("t").toFile(), "no_such.xyz")))
    }

    @Test
    fun `nav3 metadata spine toc cover`() {
        val book = EpubBook(SampleEpubs.copy("nav3")).open()
        try {
            assertEquals("测试书：引力波之旅", book.title)
            assertEquals("测试作者", book.author)
            assertEquals("zh-CN", book.language)
            assertEquals(5, book.chapters.size)
            assertEquals("OEBPS/ch01.xhtml", book.chapters[0].href)
            assertTrue(book.chapters.all { it.linear })

            assertEquals("第一章 起航", book.toc[0].label)
            assertEquals(2, book.toc[0].children.size)
            assertEquals("1.1 引力波的发现", book.toc[0].children[0].label)
            assertEquals(
                listOf("第一章 起航", "第二章 深海", "第三章 黎明", "第四章 归途", "第五章 尾声"),
                book.toc.map { it.label },
            )
            assertEquals(0, book.tocSpineIndex("OEBPS/ch01.xhtml"))
            assertEquals(4, book.tocSpineIndex("OEBPS/ch05.xhtml"))
            assertNull(book.tocSpineIndex("OEBPS/no_such.xhtml"))

            assertEquals("OEBPS/cover.png", book.coverHref)
            val cover = book.getCoverBytes()
            assertNotNull(cover)
            assertTrue(cover!!.size >= 8 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte())

            val pic = book.readFile("OEBPS/images/pic.png")
            assertNotNull(pic)
            ZipFile(book.path).use { zf ->
                zf.getInputStream(zf.getEntry("OEBPS/images/pic.png")).use {
                    assertTrue(pic!!.contentEquals(it.readBytes()))
                }
            }
            assertNull(book.readFile("OEBPS/nope/missing.png"))

            val text = book.chapterText(0)
            assertNotNull(text)
            assertTrue(text!!.contains("引力波"))
            assertTrue(text.contains("css/style.css"))
            assertEquals("第一章 起航", book.chapterTitle(0))
        } finally {
            book.close()
        }
    }

    @Test
    fun `ncx2 toc fallback`() {
        val book = EpubBook(SampleEpubs.copy("ncx2")).open()
        try {
            assertEquals("旧式测试书", book.title)
            assertEquals("老作者", book.author)
            assertEquals(4, book.chapters.size)
            assertEquals("第一章 起航", book.toc[0].label)
            assertEquals(1, book.toc[0].children.size)
            assertEquals("1.1 引力波的发现", book.toc[0].children[0].label)
            assertEquals("第二章 深海", book.toc[1].label)
            assertEquals("第一章 起航", book.tocMap["OEBPS/ch01.xhtml"])
        } finally {
            book.close()
        }
    }

    @Test
    fun `corrupt head parses`() {
        val book = EpubBook(SampleEpubs.copy("corrupt")).open()
        try {
            assertEquals(5, book.chapters.size)
            assertEquals("测试书：引力波之旅", book.title)
        } finally {
            book.close()
        }
    }

    @Test
    fun `case insensitive href fallback`() {
        val book = EpubBook(SampleEpubs.copy("case")).open()
        try {
            assertEquals(1, book.chapters.size)
            val data = book.readFile(book.chapters[0].href)
            assertNotNull(data)
            assertTrue(String(data!!, Charsets.UTF_8).contains("大小写测试"))
        } finally {
            book.close()
        }
    }

    @Test
    fun `errors`() {
        assertThrows(EpubError::class.java) {
            EpubBook(SampleEpubs.copy("bad")).open()
        }
        assertThrows(EpubError::class.java) {
            EpubBook(File("D:/no_such_dir/nope.epub")).open()
        }
        // 假 DRM 书
        val dir = Files.createTempDirectory("drm").toFile()
        val drm = File(dir, "drm.epub")
        ZipOutputStream(drm.outputStream()).use { zf ->
            zf.putNextEntry(ZipEntry("mimetype"))
            zf.write("application/epub+zip".toByteArray())
            zf.closeEntry()
            zf.putNextEntry(ZipEntry("META-INF/encryption.xml"))
            zf.write("<encryption/>".toByteArray())
            zf.closeEntry()
        }
        val e = assertThrows(EpubError::class.java) {
            EpubBook(drm).open()
        }
        assertTrue(e.message!!.contains("DRM"))
    }
}
