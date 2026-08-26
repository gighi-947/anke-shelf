package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.BookRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FloorExportMapperTest {

    @Test
    fun `nga floors map to chapters`() {
        val root = Files.createTempDirectory("floor_export_test").toFile()
        File(root, "meta.json").writeText(
            """{"format":"ank-native/1","book_id":"nga-book","tid":123,"title":"测试","per_chapter":2,"image_mode":"online","theme":"light","chapters":[{"file":"chapters/0000.xhtml","title":"主楼","floor_count":1,"first_lou":0,"last_lou":0,"main":true},{"file":"chapters/0001.xhtml","title":"1~2","floor_count":2,"first_lou":1,"last_lou":2,"main":false}]}""",
        )
        File(root, "floors.json").writeText(
            """[{"pid":111,"lou":0,"username":"a","user_id":1,"raw_content":"","timestamp":0,"like_num":0,"comments":[]},{"pid":222,"lou":1,"username":"a","user_id":1,"raw_content":"","timestamp":0,"like_num":0,"comments":[]}]""",
        )
        val record = BookRecord(id = "nga-book", path = root.absolutePath, title = "测试", nga_tid = 123)
        val session = fakeSession()
        val list = FloorExportMapper.list(record, session)
        assertEquals("nga", list.kind)
        assertEquals(listOf(1, 0), list.floors.map { it.num })
        assertEquals("#pid222", list.floors[0].selector)
        assertEquals(1, list.floors[0].chapterIndex)
        assertEquals(0, list.floors[1].chapterIndex)
    }

    @Test
    fun `gululu floors map to snapshot chapters`() {
        val root = Files.createTempDirectory("floor_export_test").toFile()
        val lib = File(root, "68846")
        lib.mkdirs()
        val epub = File(lib, "post.epub")
        epub.writeText("dummy")
        File(lib, "snapshot.json").writeText(
            """{"version":1,"source_id":68846,"image_mode":"online","detail":{"bookId":68846},"floor_index":[{"floorId":1001,"floorNum":1,"name":"前言"},{"floorId":1002,"floorNum":2,"name":"正文"}],"chapter_index":[{"floor":1,"title":"前言"},{"floor":2,"title":"正文"}],"floors":[{"id":1001},{"id":1002}]}""",
        )
        val record = BookRecord(id = "g-book", path = epub.absolutePath, title = "骨碌碌", nga_tid = 0)
        val session = fakeSession(gululuSourceId = 68846, chapterCount = 2)
        val list = FloorExportMapper.list(record, session)
        assertEquals("gululu", list.kind)
        assertEquals(listOf(2, 1), list.floors.map { it.num })
        assertEquals("#floor-1002", list.floors[0].selector)
        assertEquals(1, list.floors[0].chapterIndex)
        assertEquals(0, list.floors[1].chapterIndex)
    }

    private fun fakeSession(gululuSourceId: Int = 0, chapterCount: Int = 1): BookSession {
        return BookSession(
            id = "fake",
            title = "fake",
            author = "",
            chapters = (0 until chapterCount).map {
                io.github.gighi947.ankeshelf.data.SpineItem(it, "c$it", "chapters/$it.xhtml")
            },
            textFn = { io.github.gighi947.ankeshelf.data.ChapterReadResult.NotFound },
            titleFn = { "第 $it 章" },
            closeFn = {},
            gululuSourceId = gululuSourceId,
        )
    }

    @Test
    fun `导出编排收敛到 pipeline，UI 不得各自内联渲染管线`() {
        // 2026-08-27 审查清理：批量面板与单楼分享此前各自内联同一段
        // "映射→HTML→视口→ngaFetcher→render→落盘"，且已在主题覆写策略上
        // 漂移；统一走 FloorExportPipeline.renderFloor。
        val panel = File(rootRepo(), "app/src/main/java/io/github/gighi947/ankeshelf/ui/download/FloorExportPanel.kt")
            .readText()
        val reader = File(rootRepo(), "app/src/main/java/io/github/gighi947/ankeshelf/ui/reader/native/NativeReaderScreen.kt")
            .readText()
        for (f in listOf(panel, reader)) {
            assertTrue(
                "UI 不得再直接调 FloorExportRenderer.render（应走 FloorExportPipeline）",
                !f.contains("FloorExportRenderer.render("),
            )
            assertTrue("UI 必须经 FloorExportPipeline.renderFloor", f.contains("FloorExportPipeline.renderFloor"))
        }
    }

    private fun rootRepo(): java.io.File =
        // gradle 单测工作目录是 android/（user.dir=…/android），回退一级到
        // android 工程根（UI 源文件在 android/app/src/main/…）
        java.io.File(System.getProperty("user.dir")).parentFile
}
