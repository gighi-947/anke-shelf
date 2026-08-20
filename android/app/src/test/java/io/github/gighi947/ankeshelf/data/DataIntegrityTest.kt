package io.github.gighi947.ankeshelf.data

import io.github.gighi947.ankeshelf.service.BookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 批 3：数据完整性校验入口、进度百分比精度、契约字段往返。
 * 三者都守"双端数据不被本端弄丢/弄错"的不变量。
 */
class DataIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `完整性校验区分缺失_正常_损坏_结构错误`() {
        val missing = File(tmp.root, "missing.json")
        val good = File(tmp.root, "good.json").apply { writeText("""{"version":2,"progress":{}}""") }
        val broken = File(tmp.root, "broken.json").apply { writeText("{corrupt") }
        val array = File(tmp.root, "array.json").apply { writeText("""[1,2,3]""") }

        val m = verifyJsonFile(missing)
        assertTrue("缺失文件视为健康（尚未创建）", m.ok)
        assertEquals("missing", m.error)

        val g = verifyJsonFile(good)
        assertTrue(g.ok)
        assertEquals(2, g.version)
        assertTrue(g.size > 0)

        val b = verifyJsonFile(broken)
        assertFalse("无法解析必须显式报错", b.ok)
        assertTrue(b.error.isNotBlank())

        val a = verifyJsonFile(array)
        assertFalse("顶层非对象属于结构损坏", a.ok)
    }

    @Test
    fun `五存储校验覆盖全部权威文件`() {
        val paths = AppPaths(tmp.root).also { it.ensure() }
        val names = verifyDataIntegrity(paths).map { it.name }
        assertEquals(
            listOf("shelf.json", "progress.json", "settings.json", "annotations.json", "statistics.json"),
            names,
        )
    }

    @Test
    fun `书架百分比含章内比例`() {
        // 分页：第 2 章（索引 1）第 5/10 页 → (1 + 0.5) / 4 = 37.5%
        val paged = ProgressEntry(chapter_index = 1, text_offset = 100, page_index = 5, page_total = 10)
        assertEquals(37.5, BookRepository.progressPercent(paged, 4), 0.001)

        // 滚动：第 1 章（索引 0）比例 0.25 → 0.25 / 4 = 6.25%
        val scroll = ProgressEntry(chapter_index = 0, text_offset = 100, scroll_ratio = 0.25)
        assertEquals(6.25, BookRepository.progressPercent(scroll, 4), 0.001)

        // 两者都缺省：退回纯章号占比（不倒退于旧行为）
        val plain = ProgressEntry(chapter_index = 2, text_offset = 100)
        assertEquals(50.0, BookRepository.progressPercent(plain, 4), 0.001)

        assertEquals(0.0, BookRepository.progressPercent(null, 4), 0.001)
        assertEquals(0.0, BookRepository.progressPercent(plain, 0), 0.001)
    }

    @Test
    fun `设置往返保留骨碌碌沉浸偏好与未知字段兼容`() {
        val file = File(tmp.root, "settings.json")
        // 桌面写入的 settings.json（含 gululu_immersive 与安卓不认识的字段）
        file.writeText(
            """
            {"settings_version":3,"theme":"sepia",
             "gululu_immersive":{"autoMusic":false,"backgrounds":true,"vfx":false,"volume":0.8},
             "window_size":[1024,720],"unknown_future_field":42}
            """.trimIndent(),
        )
        val settings = Settings(file)
        settings.load()
        val loaded = settings.getAll()
        assertEquals("sepia", loaded.theme)
        assertFalse("必须读到桌面写入的 autoMusic=false", loaded.gululu_immersive.autoMusic)
        assertEquals(0.8, loaded.gululu_immersive.volume, 0.0001)

        // 安卓保存其他设置后，骨碌碌偏好不得被清成默认值（跨端数据丢失回归）
        settings.update(SettingsPatch(font_size = 20))
        val text = file.readText()
        assertTrue("落盘必须仍带 autoMusic=false", text.contains("\"autoMusic\": false"))
        assertTrue("落盘必须仍带 volume=0.8", text.contains("0.8"))
        assertEquals(20, Settings(file).also { it.load() }.getAll().font_size)
    }
}
