package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.ChapterReadResult
import io.github.gighi947.ankeshelf.data.ProgressStore
import io.github.gighi947.ankeshelf.data.Shelf
import io.github.gighi947.ankeshelf.data.TextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** M2 仓库：EPUB 登记/打开/进度持久化（JVM，无 Android 依赖）。 */
class BookRepositoryTest {

    @Test
    fun registerOpenAndProgress() {
        val tmp = kotlin.io.path.createTempDirectory("repo-").toFile()
        try {
            val paths = AppPaths(File(tmp, "AnkeShelf")).also { it.ensure() }
            val shelf = Shelf(paths.shelfFile, paths.coversDir)
            val progress = ProgressStore(paths.progressFile)
            val repo = BookRepository(paths, shelf, progress)

            val sampleUrl = checkNotNull(javaClass.classLoader!!.getResource("samples/sample_nav3.epub"))
            val copy = File(tmp, "sample_nav3.epub")
            File(sampleUrl.toURI()).copyTo(copy)

            val registerResult = repo.registerEpubFile(copy)
            assertTrue(registerResult is RepoResult.Ok)
            val rec = (registerResult as RepoResult.Ok).value
            assertNotNull(rec)
            assertTrue(rec.chapter_count > 0)
            assertTrue(rec.title.isNotBlank())

            val ui = repo.listBooks()
            assertEquals(1, ui.size)
            assertEquals(0.0, ui[0].progressPct, 0.001)

            val openResult = repo.openSession(rec)
            assertTrue(openResult is RepoResult.Ok)
            val session = (openResult as RepoResult.Ok).value
            assertEquals(rec.chapter_count, session.chapters.size)
            val chapter = session.chapterText(0)
            assertTrue(chapter is ChapterReadResult.Success)
            val len = TextExtractor.extractDomText((chapter as ChapterReadResult.Success).text).length
            assertTrue(len > 0)
            val offset = BookRepository.offsetForRatio(0.5, len)
            assertTrue(offset in 0..len)

            repo.saveProgress(rec.id, 1, offset)
            assertTrue(progress.flush().isSuccess)
            val p = repo.progressOf(rec.id)!!
            assertEquals(1, p.chapter_index)
            assertEquals(offset, p.text_offset)

            // 重载验证持久化
            val shelf2 = Shelf(paths.shelfFile, paths.coversDir).also { it.load() }
            val progress2 = ProgressStore(paths.progressFile).also { it.load() }
            assertEquals(rec.id, shelf2.listBooks().first().id)
            assertEquals(offset, progress2.get(rec.id)!!.text_offset)
            session.close()
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `register missing file reports NotFound`() {
        val tmp = kotlin.io.path.createTempDirectory("repo-").toFile()
        try {
            val paths = AppPaths(File(tmp, "AnkeShelf")).also { it.ensure() }
            val repo = BookRepository(paths, Shelf(paths.shelfFile, paths.coversDir), ProgressStore(paths.progressFile))
            val result = repo.registerEpubFile(File(tmp, "missing.epub"))
            assertEquals(BookRepoError.NotFound, (result as RepoResult.Err).error)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `register corrupt file reports Corrupt`() {
        val tmp = kotlin.io.path.createTempDirectory("repo-").toFile()
        try {
            val paths = AppPaths(File(tmp, "AnkeShelf")).also { it.ensure() }
            val repo = BookRepository(paths, Shelf(paths.shelfFile, paths.coversDir), ProgressStore(paths.progressFile))
            val bad = File(tmp, "bad.epub")
            bad.writeText("not an epub", Charsets.UTF_8)
            val result = repo.registerEpubFile(bad)
            assertEquals(BookRepoError.Corrupt, (result as RepoResult.Err).error)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `register native directory with corrupt metadata reports Corrupt`() {
        val tmp = kotlin.io.path.createTempDirectory("repo-").toFile()
        try {
            val paths = AppPaths(File(tmp, "AnkeShelf")).also { it.ensure() }
            val repo = BookRepository(paths, Shelf(paths.shelfFile, paths.coversDir), ProgressStore(paths.progressFile))
            val nativeDir = File(tmp, "native").apply { mkdirs() }
            File(nativeDir, "meta.json").writeText("{broken", Charsets.UTF_8)

            val result = repo.registerNativeDir(nativeDir, tid = 1)

            assertEquals(BookRepoError.Corrupt, (result as RepoResult.Err).error)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `reset cover clears cover_rel and deletes file`() {
        val tmp = kotlin.io.path.createTempDirectory("repo-").toFile()
        try {
            val paths = AppPaths(File(tmp, "AnkeShelf")).also { it.ensure() }
            val shelf = Shelf(paths.shelfFile, paths.coversDir)
            val repo = BookRepository(paths, shelf, ProgressStore(paths.progressFile))
            val rec = BookRecord(
                id = "a".repeat(32),
                path = File(tmp, "book.epub").absolutePath,
                title = "t",
                cover_rel = "covers/${"a".repeat(32)}.png",
            )
            shelf.upsert(rec)
            shelf.save()
            val cover = File(paths.coversDir, "${"a".repeat(32)}.png").apply { writeBytes(byteArrayOf(1)) }

            val result = repo.resetCover(rec)

            assertTrue(result is RepoResult.Ok)
            assertNull((result as RepoResult.Ok).value.cover_rel)
            assertTrue(!cover.exists())
            assertNull(shelf.get("a".repeat(32))?.cover_rel)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `open session missing file reports NotFound`() {
        val tmp = kotlin.io.path.createTempDirectory("repo-").toFile()
        try {
            val paths = AppPaths(File(tmp, "AnkeShelf")).also { it.ensure() }
            val repo = BookRepository(paths, Shelf(paths.shelfFile, paths.coversDir), ProgressStore(paths.progressFile))
            val rec = BookRecord(id = "x", path = File(tmp, "nope.epub").absolutePath, title = "x")
            val result = repo.openSession(rec)
            assertEquals(BookRepoError.NotFound, (result as RepoResult.Err).error)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
