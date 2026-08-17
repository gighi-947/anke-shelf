package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.File
import java.nio.file.Files

class SettingsTest {

    @Test
    fun `new install defaults`() {
        val s = Settings(File(Files.createTempDirectory("settings").toFile(), "settings.json"))
        s.load()
        val d = s.getAll()
        assertEquals(false, d.pagination)
        assertEquals("", d.theme_mode)
        assertEquals(false, d.dual_page)
        assertEquals(true, d.auto_dual)
        assertEquals("sys:weidqczfkyxk.ttf", d.custom_font)
        assertEquals("", d.custom_bg)
        assertEquals("", d.custom_primary)
        assertEquals("", d.custom_accent)
        assertEquals("", d.custom_text)
        assertEquals(3, d.settings_version)
    }

    @Test
    fun `legacy file migrated once`() {
        val file = File(Files.createTempDirectory("settings").toFile(), "settings.json")
        file.writeText("""{"theme":"light","pagination":false,"custom_font":""}""", Charsets.UTF_8)
        val s = Settings(file)
        s.load()
        val d = s.getAll()
        assertEquals("light", d.theme)
        assertEquals(false, d.pagination)
        assertEquals("sys:weidqczfkyxk.ttf", d.custom_font)
        assertEquals(3, d.settings_version)
        val saved = Shelf.json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        assertEquals(3, saved["settings_version"]!!.jsonPrimitive.int)

        val s2 = Settings(file)
        s2.load()
        assertEquals(3, s2.getAll().settings_version)
    }

    @Test
    fun `v2 paged default migrated to scroll`() {
        val file = File(Files.createTempDirectory("settings").toFile(), "settings.json")
        file.writeText(
            """{"theme":"dark","pagination":true,"dual_page":false,"auto_dual":true,"settings_version":2}""",
            Charsets.UTF_8,
        )
        val s = Settings(file)
        s.load()
        assertEquals(false, s.getAll().pagination)
        assertEquals(3, s.getAll().settings_version)
    }

    @Test
    fun `corrupt file is isolated and defaults kept`() {
        val dir = Files.createTempDirectory("settings").toFile()
        val file = File(dir, "settings.json")
        file.writeText("{not-json", Charsets.UTF_8)
        val s = Settings(file)
        s.load()
        assertEquals(3, s.getAll().settings_version)
        assertEquals("dark", s.getAll().theme)
        assertFalse(file.exists())
        assertNotNull(dir.listFiles()?.singleOrNull { it.name.startsWith("settings.json.corrupt-") })
    }
}
