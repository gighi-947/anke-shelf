package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.ProgressStore
import io.github.gighi947.ankeshelf.data.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

            val sampleUrl = checkNotNull(javaClass.classLoader.getResource("samples/sample_nav3.epub"))
            val copy = File(tmp, "sample_nav3.epub")
            File(sampleUrl.toURI()).copyTo(copy)

            val rec = repo.registerEpubFile(copy)
            assertNotNull(rec)
            assertTrue(rec!!.chapter_count > 0)
            assertTrue(rec.title.isNotBlank())

            val ui = repo.listBooks()
            assertEquals(1, ui.size)
            assertEquals(0.0, ui[0].progressPct, 0.001)

            val session = repo.openSession(rec)!!
            assertEquals(rec.chapter_count, session.chapters.size)
            assertNotNull(session.chapterText(0))
            val len = repo.chapterPlainLength(session, 0)
            assertTrue(len > 0)
            val offset = BookRepository.offsetForRatio(0.5, len)
            assertTrue(offset in 0..len)

            repo.saveProgress(rec.id, 1, offset)
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
}
