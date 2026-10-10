package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.ChapterReadResult
import io.github.gighi947.ankeshelf.data.NativeBookWriter
import io.github.gighi947.ankeshelf.data.NativeFloor
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

    @Test
    fun `refresh native record updates chapter count after append`() {
        val tmp = kotlin.io.path.createTempDirectory("repo-native-").toFile()
        try {
            val paths = AppPaths(File(tmp, "AnkeShelf")).also { it.ensure() }
            val repo = BookRepository(paths, Shelf(paths.shelfFile, paths.coversDir), ProgressStore(paths.progressFile))
            val nativeDir = NativeBookWriter.nativeDirFor(tmp, "book")

            val main = NativeFloor(pid = 0, lou = 0, raw_content = "<p>main</p>")
            val floors = listOf(
                NativeFloor(pid = 1, lou = 1, raw_content = "<p>1</p>"),
                NativeFloor(pid = 2, lou = 2, raw_content = "<p>2</p>"),
            )
            NativeBookWriter.writeContainer(
                ngaLibraryRoot = tmp,
                folderName = "book",
                tieziTitle = "t",
                author = "a",
                tid = 1,
                authorId = 0,
                createdTime = "2026-01-01T00:00:00+08:00",
                updatedTime = "2026-01-01T00:00:00+08:00",
                validFloors = listOf(main) + floors,
                perChapter = 2,
                imageMode = "online",
                theme = "light",
                bookId = "bookid123",
            )

            val registerResult = repo.registerNativeDir(nativeDir, tid = 1)
            assertTrue(registerResult is RepoResult.Ok)
            val rec = (registerResult as RepoResult.Ok).value
            assertEquals(2, rec.chapter_count)

            val append = listOf(
                NativeFloor(pid = 3, lou = 3, raw_content = "<p>3</p>"),
                NativeFloor(pid = 4, lou = 4, raw_content = "<p>4</p>"),
            )
            val added = NativeBookWriter.appendContainer(
                ngaLibraryRoot = tmp,
                folderName = "book",
                newFloors = append,
                perChapter = 2,
                imageMode = "online",
                theme = "light",
            )
            assertTrue("应有新楼层追加", added > 0)

            // 热更新后书架记录的 chapter_count 必须同步刷新，否则进度百分比与恢复位置
            // 会按旧上限截断（issue：热更新后原生书最大进度上限无变化）。
            val refreshResult = repo.refreshNativeRecord(rec.id)
            assertTrue(refreshResult is RepoResult.Ok)
            val updated = (refreshResult as RepoResult.Ok).value
            assertEquals(3, updated.chapter_count)
            assertEquals(3, repo.recordOf(rec.id)?.chapter_count)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `progress percent uses refreshed chapter count instead of clamping`() {
        val tmp = kotlin.io.path.createTempDirectory("repo-native-").toFile()
        try {
            val paths = AppPaths(File(tmp, "AnkeShelf")).also { it.ensure() }
            val repo = BookRepository(paths, Shelf(paths.shelfFile, paths.coversDir), ProgressStore(paths.progressFile))
            val nativeDir = NativeBookWriter.nativeDirFor(tmp, "book")

            val main = NativeFloor(pid = 0, lou = 0, raw_content = "<p>main</p>")
            val floors = listOf(
                NativeFloor(pid = 1, lou = 1, raw_content = "<p>1</p>"),
                NativeFloor(pid = 2, lou = 2, raw_content = "<p>2</p>"),
            )
            NativeBookWriter.writeContainer(
                ngaLibraryRoot = tmp,
                folderName = "book",
                tieziTitle = "t",
                author = "a",
                tid = 1,
                authorId = 0,
                createdTime = "2026-01-01T00:00:00+08:00",
                updatedTime = "2026-01-01T00:00:00+08:00",
                validFloors = listOf(main) + floors,
                perChapter = 2,
                imageMode = "online",
                theme = "light",
                bookId = "bookid123",
            )

            val registerResult = repo.registerNativeDir(nativeDir, tid = 1)
            assertTrue(registerResult is RepoResult.Ok)
            val rec = (registerResult as RepoResult.Ok).value

            NativeBookWriter.appendContainer(
                ngaLibraryRoot = tmp,
                folderName = "book",
                newFloors = listOf(
                    NativeFloor(pid = 3, lou = 3, raw_content = "<p>3</p>"),
                    NativeFloor(pid = 4, lou = 4, raw_content = "<p>4</p>"),
                ),
                perChapter = 2,
                imageMode = "online",
                theme = "light",
            )
            repo.refreshNativeRecord(rec.id)

            // 进度落在新增的第 3 章（索引 2），刷新后应正确计算为 2/3 而非被旧上限截断。
            repo.saveProgress(rec.id, chapterIndex = 2, textOffset = 10)
            val ui = repo.listBooks().first()
            assertEquals((2.0 / 3.0) * 100.0, ui.progressPct, 0.001)
            assertEquals(3, ui.totalChapters)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
