package io.github.gighi947.ankeshelf.contracts

import io.github.gighi947.ankeshelf.data.NativeBook
import io.github.gighi947.ankeshelf.data.NativeBookWriter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B1 契约：Android NativeBook 读取 contracts/fixtures/native-book/basic-nga。 */
class ContractNativeBookTest {

    private val fixture = File(contractsRoot(), "fixtures/native-book/basic-nga")

    @Test
    fun `meta and chapters match fixture`() {
        val book = NativeBook(fixture).open()
        assertEquals("fixture-basic-nga-0001", book.id)
        assertEquals("测试安科：契约样本", book.title)
        assertEquals("样本楼主", book.author)
        assertEquals(3, book.chapters.size)
        assertEquals("序章 · 主楼", book.toc[0].label)
        assertEquals("第 1~2 楼", book.toc[1].label)
        assertNotNull(book.readFile("chapters/0001.xhtml"))
        assertTrue(book.chapterText(1)!!.contains("第一楼正文"))
        assertNull(book.readFile("../meta.json")) // 路径穿越拒绝
        book.close()
    }

    @Test
    fun `floors match fixture`() {
        val meta = NativeBookWriter.loadMeta(fixture)
        assertEquals("ank-native/1", meta.format)
        assertEquals(2, meta.per_chapter)
        assertEquals(4, meta.last_lou)
        assertEquals("online", meta.image_mode)

        val floors = NativeBookWriter.loadFloors(fixture)
        assertEquals(5, floors.size)
        assertEquals(0L, floors[0].pid)
        assertEquals(4, floors.last().lou)
        assertEquals(1, floors[2].comments.size) // 楼中楼
        assertEquals("读者C", floors[2].comments[0].username)
    }

    private fun contractsRoot(): File {
        System.getProperty("contracts.dir")?.let { return File(it) }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "contracts")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("contracts/ 未找到；可用 -Dcontracts.dir=... 指定")
    }
}
