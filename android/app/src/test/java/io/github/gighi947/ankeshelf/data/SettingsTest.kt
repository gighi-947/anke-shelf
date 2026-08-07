package io.github.gighi947.ankeshelf.data

import org.junit.Assert.assertEquals
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
        assertEquals(false, s.get("pagination"))
        assertEquals("", s.get("theme_mode"))
        assertEquals(false, s.get("dual_page"))
        assertEquals(true, s.get("auto_dual"))
        assertEquals("sys:weidqczfkyxk.ttf", s.get("custom_font"))
        assertEquals("", s.get("custom_bg"))
        assertEquals("", s.get("custom_primary"))
        assertEquals("", s.get("custom_accent"))
        assertEquals("", s.get("custom_text"))
        assertEquals(3, s.get("settings_version"))
    }

    @Test
    fun `legacy file migrated once`() {
        val file = File(Files.createTempDirectory("settings").toFile(), "settings.json")
        file.writeText("""{"theme":"light","pagination":false,"custom_font":""}""", Charsets.UTF_8)
        val s = Settings(file)
        s.load()
        assertEquals("light", s.get("theme"))
        assertEquals(false, s.get("pagination"))
        assertEquals("sys:weidqczfkyxk.ttf", s.get("custom_font"))
        assertEquals(3, s.get("settings_version"))
        val saved = Shelf.json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        assertEquals(3, saved["settings_version"]!!.jsonPrimitive.int)

        val s2 = Settings(file)
        s2.load()
        assertEquals(3, s2.get("settings_version"))
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
        assertEquals(false, s.get("pagination"))
        assertEquals(3, s.get("settings_version"))
    }
}
