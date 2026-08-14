package io.github.gighi947.ankeshelf.service

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.ProgressStore
import io.github.gighi947.ankeshelf.data.Shelf
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookRepositoryDeviceTest {

    @Test
    fun unreadableNativeMetadataReportsIo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "repo-device-${System.nanoTime()}")
        val paths = AppPaths(File(root, "data")).also { it.ensure() }
        val nativeDir = File(root, "native").apply { mkdirs() }
        val meta = File(nativeDir, "meta.json").apply {
            writeText(
                """{"format":"ank-native/1","book_id":"device-io","title":"test","chapters":[]}""",
                Charsets.UTF_8,
            )
        }
        val repo = BookRepository(
            paths,
            Shelf(paths.shelfFile, paths.coversDir),
            ProgressStore(paths.progressFile),
        )

        Os.chmod(meta.absolutePath, 0)
        try {
            val result = repo.registerNativeDir(nativeDir, tid = 1)

            assertTrue(
                "expected Io for unreadable meta.json, got $result",
                result is RepoResult.Err && result.error is BookRepoError.Io,
            )
        } finally {
            Os.chmod(meta.absolutePath, 0x180)
            root.deleteRecursively()
        }
    }
}
