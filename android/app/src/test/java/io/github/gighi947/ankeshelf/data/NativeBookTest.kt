package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 原生书容器：与桌面 app/native_book.py 生成的参考 JSON 差分一致。 */
class NativeBookTest {

    private fun resourceDir(rel: String): File {
        val url = checkNotNull(javaClass.classLoader!!.getResource(rel)) { "missing resource: $rel" }
        return File(url.toURI())
    }

    private fun referenceDir(scenario: String): File =
        resourceDir("reference/native/$scenario/book")

    private fun meta(dir: File): NativeMeta =
        Shelf.json.decodeFromString(File(dir, "meta.json").readText(Charsets.UTF_8))

    private fun floors(dir: File): List<NativeFloor> =
        Shelf.json.decodeFromString(File(dir, "floors.json").readText(Charsets.UTF_8))

    @Test
    fun writeContainer_matchesDesktopReference() {
        for (scenario in listOf("write25", "toc_split")) {
            val refDir = referenceDir(scenario)
            val refMeta = meta(refDir)
            val refFloors = floors(refDir)
            val tmp = kotlin.io.path.createTempDirectory("native-ref-").toFile()
            try {
                NativeBookWriter.writeContainer(
                    ngaLibraryRoot = tmp,
                    folderName = refMeta.folder_name,
                    tieziTitle = refMeta.title,
                    author = refMeta.author,
                    tid = refMeta.tid,
                    authorId = refMeta.author_id,
                    createdTime = refMeta.created_time,
                    updatedTime = refMeta.updated_time,
                    validFloors = refFloors,
                    perChapter = refMeta.per_chapter,
                    imageMode = refMeta.image_mode,
                    theme = refMeta.theme,
                    bookId = refMeta.book_id,
                    tocChapters = refMeta.toc.ifEmpty { null },
                    tocMode = refMeta.toc_mode,
                )
                val outDir = NativeBookWriter.nativeDirFor(tmp, refMeta.folder_name)
                assertEquals("meta 差分失败：$scenario", refMeta, meta(outDir))
                assertEquals("floors 差分失败：$scenario", refFloors, floors(outDir))
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    @Test
    fun appendContainer_matchesDesktopReference() {
        val ref25 = referenceDir("write25")
        val ref60 = referenceDir("append60")
        val refMeta25 = meta(ref25)
        val refFloors25 = floors(ref25)
        val refMeta60 = meta(ref60)
        val refFloors60 = floors(ref60)
        val tmp = kotlin.io.path.createTempDirectory("native-append-").toFile()
        try {
            NativeBookWriter.writeContainer(
                ngaLibraryRoot = tmp,
                folderName = "append60",
                tieziTitle = refMeta25.title,
                author = refMeta25.author,
                tid = refMeta25.tid,
                authorId = refMeta25.author_id,
                createdTime = refMeta25.created_time,
                updatedTime = refMeta25.updated_time,
                validFloors = refFloors25,
                perChapter = 20,
                imageMode = "online",
                theme = "light",
                bookId = "bookid123",
            )
            val new1 = (26..30).map { lou ->
                NativeFloor(
                    pid = 2000L + lou, lou = lou, username = "u", user_id = 1,
                    raw_content = "<p>floor-$lou</p>",
                )
            }
            val new2 = (31..60).map { lou ->
                NativeFloor(
                    pid = 3000L + lou, lou = lou, username = "u", user_id = 1,
                    raw_content = "<p>floor-$lou</p>",
                )
            }
            val c1 = NativeBookWriter.appendContainer(tmp, "append60", new1, 20, "online", "light")
            val c2 = NativeBookWriter.appendContainer(tmp, "append60", new2, 20, "online", "light")
            assertEquals(5, c1)
            assertEquals(30, c2)

            val outDir = NativeBookWriter.nativeDirFor(tmp, "append60")
            val outMeta = meta(outDir)
            assertEquals(4, outMeta.chapters.size)
            assertEquals(19, outMeta.chapters.last().floor_count)
            assertEquals(60, outMeta.last_lou)
            assertEquals("meta 差分失败：append60", refMeta60.copy(updated_time = outMeta.updated_time), outMeta)
            assertEquals("floors 差分失败：append60", refFloors60, floors(outDir))

            // 重复追加按 pid 去重
            val dup = NativeBookWriter.appendContainer(tmp, "append60", new1, 20, "online", "light")
            assertEquals(0, dup)
            assertEquals(60, floors(outDir).size)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun nativeBook_readsDesktopReference() {
        val dir = referenceDir("write25")
        val book = NativeBook(dir).open()
        assertEquals("bookid123", book.id)
        assertEquals("标题", book.title)
        assertEquals(3, book.chapters.size)
        assertEquals("序章 · 主楼", book.chapterTitle(0))
        assertEquals("第 22~25 楼", book.chapterTitle(2))
        val t2 = book.chapterText(2)
        assertTrue(t2 is ChapterReadResult.Success)
        assertTrue((t2 as ChapterReadResult.Success).text.contains("floor-25"))
        assertEquals(1, book.tocSpineIndex("chapters/0001.xhtml"))
        assertNotNull(book.readFile("meta.json"))
        assertNull(book.readFile("../meta.json"))
        assertNull(book.readFile("/meta.json"))
        assertNull(book.readFile("chapters\\0000.xhtml"))
        book.close()
    }

    @Test
    fun `chapter read failure is explicit`() {
        val book = NativeBook(referenceDir("write25")).open()
        assertEquals(ChapterReadResult.NotFound, book.chapterText(99))
        assertEquals(ChapterReadResult.NotFound, book.chapterText(-1))
        assertTrue(book.chapterText(0) is ChapterReadResult.Success)
        book.close()

        // meta 声明了章节但文件缺失 → NotFound（而非笼统 null）
        val tmp = kotlin.io.path.createTempDirectory("native-missing-").toFile()
        try {
            referenceDir("write25").copyRecursively(tmp, overwrite = true)
            File(tmp, "chapters/0002.xhtml").delete()
            val broken = NativeBook(tmp).open()
            assertEquals(ChapterReadResult.NotFound, broken.chapterText(2))
            broken.close()
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun writeContainer_groupingMatchesDesktop() {
        val floors25 = floors(referenceDir("write25"))
        val meta25 = meta(referenceDir("write25"))
        val tmp = kotlin.io.path.createTempDirectory("native-group-").toFile()
        try {
            NativeBookWriter.writeContainer(
                ngaLibraryRoot = tmp,
                folderName = "g",
                tieziTitle = "标题",
                author = "作者",
                tid = 123,
                authorId = 0,
                createdTime = "2026-01-01T00:00:00+08:00",
                updatedTime = "2026-01-01T00:00:00+08:00",
                validFloors = floors25,
                perChapter = 20,
                imageMode = "online",
                theme = "light",
                bookId = "bookid123",
            )
            val m = meta(NativeBookWriter.nativeDirFor(tmp, "g"))
            assertEquals(meta25.chapters, m.chapters)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
