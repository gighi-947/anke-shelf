package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.ProgressStore
import io.github.gighi947.ankeshelf.data.Shelf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 骨碌碌导入编排：核心不变量是 **`.part` 原子替换 + 不留半成品**——
 * 成功才有 `post.epub`，取消/失败都必须清掉 `.part` 且不破坏已有书。
 */
class GululuImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun snapshot(floorCount: Int = 2): GululuSnapshot {
        val detail = buildJsonObject {
            put("bookId", 48856)
            put("name", "导入样本")
            put("author", buildJsonObject { put("nickName", "作者") })
        }
        val floorIndex = (1..floorCount).map { n ->
            buildJsonObject {
                put("floorId", 500 + n)
                put("floorNum", n)
            }
        }
        val floors = (1..floorCount).map { n ->
            json.parseToJsonElement(
                """{"id":${500 + n},"floorNum":$n,"paragraphContents":[
                     {"type":"paragraph","content":[{"type":"text","text":"第 $n 楼正文"}]}]}""",
            ).jsonObject
        }
        return GululuSnapshot(detail, floorIndex, emptyList(), floors)
    }

    private fun importer(
        appPaths: AppPaths,
        fetch: (Int, ProgressReporter?, () -> Boolean) -> GululuSnapshot,
    ): Pair<GululuImporter, File> {
        val shelf = Shelf(appPaths.shelfFile, appPaths.coversDir).also { it.load() }
        val progress = ProgressStore(appPaths.progressFile).also { it.load() }
        val repository = BookRepository(appPaths, shelf, progress)
        return GululuImporter(appPaths, repository, fetch) to
            File(appPaths.gululuLibraryDir, "48856")
    }

    @Test
    fun `导入成功后落 post_epub 并注册书架`() {
        val appPaths = AppPaths(tmp.root).also { it.ensure() }
        val (importer, folder) = importer(appPaths) { _, _, _ -> snapshot() }
        val result = importer.import(48856, GululuImageMode.ONLINE)

        assertTrue("应成功：$result", result is GululuImportResult.Ok)
        val target = File(folder, "post.epub")
        assertTrue("必须产出 post.epub", target.isFile)
        assertFalse("不得残留 .part", File(folder, "post.epub.part").exists())
        assertFalse("不得残留备份", folder.listFiles()!!.any { it.name.contains(".backup-") })

        val ok = result as GululuImportResult.Ok
        assertEquals(48856, ok.sourceId)
        assertTrue(ok.bookId.isNotEmpty())
        val shelf = Shelf(appPaths.shelfFile, appPaths.coversDir).also { it.load() }
        assertEquals("书架应有 1 本", 1, shelf.listBooks().size)
        assertEquals("导入样本", shelf.listBooks().single().title)
    }

    @Test
    fun `取消导入不留任何产物`() {
        val appPaths = AppPaths(tmp.root).also { it.ensure() }
        var importerRef: GululuImporter? = null
        val (importer, folder) = importer(appPaths) { _, _, _ ->
            // 在拉快照阶段就取消：与真实链路一样由 cancel 回调触发
            importerRef?.cancel()
            throw GululuCancelledException("骨碌碌任务已取消")
        }
        importerRef = importer
        val result = importer.import(48856, GululuImageMode.ONLINE)

        assertEquals(GululuImportResult.Cancelled, result)
        assertFalse(File(folder, "post.epub").exists())
        assertFalse(File(folder, "post.epub.part").exists())
    }

    @Test
    fun `失败时保留已有书并清掉 part`() {
        val appPaths = AppPaths(tmp.root).also { it.ensure() }
        // 先成功导入一次，形成"已有书"
        val (first, folder) = importer(appPaths) { _, _, _ -> snapshot() }
        assertTrue(first.import(48856, GululuImageMode.ONLINE) is GululuImportResult.Ok)
        val originalSize = File(folder, "post.epub").length()

        val (second, _) = importer(appPaths) { _, _, _ ->
            throw GululuApiException("骨碌碌接口请求失败（/reader/opus/detail/48856）：HTTP 503")
        }
        val result = second.import(48856, GululuImageMode.ONLINE)

        assertTrue(result is GululuImportResult.Err)
        assertTrue((result as GululuImportResult.Err).message.contains("503"))
        assertTrue("已有书必须保留", File(folder, "post.epub").isFile)
        assertEquals("已有书不得被改动", originalSize, File(folder, "post.epub").length())
        assertFalse(File(folder, "post.epub.part").exists())
    }

    @Test
    fun `缺楼层正文的快照显式失败`() {
        val appPaths = AppPaths(tmp.root).also { it.ensure() }
        val broken = GululuSnapshot(
            detail = buildJsonObject { put("bookId", 48856); put("name", "缺楼") },
            floorIndex = listOf(buildJsonObject { put("floorId", 1); put("floorNum", 1) }),
            chapterIndex = emptyList(),
            floors = emptyList(),
        )
        val (importer, folder) = importer(appPaths) { _, _, _ -> broken }
        val result = importer.import(48856, GululuImageMode.ONLINE)
        assertTrue(result is GululuImportResult.Err)
        assertFalse(File(folder, "post.epub").exists())
        assertFalse(File(folder, "post.epub.part").exists())
    }

    @Test
    fun `内嵌模式把图片打包并在正文引用相对路径`() {
        val appPaths = AppPaths(tmp.root).also { it.ensure() }
        val withImage = GululuSnapshot(
            detail = buildJsonObject { put("bookId", 48856); put("name", "带图") },
            floorIndex = listOf(buildJsonObject { put("floorId", 601); put("floorNum", 1) }),
            chapterIndex = emptyList(),
            floors = listOf(
                json.parseToJsonElement(
                    """{"id":601,"floorNum":1,"paragraphContents":[
                         {"type":"image","attrs":{"src":"https://img.example/a.png"}}]}""",
                ).jsonObject,
            ),
        )
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val shelf = Shelf(appPaths.shelfFile, appPaths.coversDir).also { it.load() }
        val repository = BookRepository(appPaths, shelf, ProgressStore(appPaths.progressFile).also { it.load() })
        val importer = GululuImporter(
            appPaths,
            repository,
            { _, _, _ -> withImage },
            GululuImages.ImageFetcher { png to "image/png" },
        )
        val result = importer.import(48856, GululuImageMode.EMBEDDED)
        assertTrue(result is GululuImportResult.Ok)
        assertEquals(1, (result as GululuImportResult.Ok).imageEmbedded)

        val target = File(File(appPaths.gululuLibraryDir, "48856"), "post.epub")
        java.util.zip.ZipFile(target).use { zip ->
            val imageEntry = zip.entries().toList().firstOrNull { it.name.startsWith("EPUB/images/") }
            assertTrue("内嵌图片应随包携带", imageEntry != null)
            val chapter = zip.getInputStream(zip.getEntry("EPUB/chapters/chapter_0001.xhtml"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue("正文应引用相对路径", chapter.contains("src=\"../images/"))
            assertFalse("内嵌模式不应留在线地址", chapter.contains("https://img.example/a.png"))
        }
    }
}
