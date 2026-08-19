package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class SettingsData(
    val settings_version: Int = 3,
    val theme: String = "dark",
    val theme_mode: String = "",
    val font_size: Int = 18,
    val line_height: Double = 1.8,
    val ui_font_scale: Double = 1.0,
    val font_family: String = "reader",
    val custom_font: String = "sys:weidqczfkyxk.ttf",
    val book_fonts: Map<String, String> = emptyMap(),
    val custom_bg: String = "",
    val custom_primary: String = "",
    val custom_accent: String = "",
    val custom_text: String = "",
    val page_width: Double = 1.0,
    val bars_pinned: Boolean = false,
    val pagination: Boolean = false,
    val dual_page: Boolean = false,
    val auto_dual: Boolean = true,
    val shelf_view: String = "grid",
    val shelf_sort: String = "recent",
    val hide_title_brackets: Boolean = false,
    val margin_px: Int = 40,
    val gap_px: Int = 28,
    val brightness: Double = 0.0,
    val rsvp_rate: Int = 300,
    val autoscroll_speed: Double = 2.0,
    val show_ruler: Boolean = false,
    val show_statusbar: Boolean = true,
    val shortcuts: Map<String, String> = mapOf(
        "next_page" to "ArrowRight",
        "prev_page" to "ArrowLeft",
        "next_chapter" to "ArrowDown",
        "prev_chapter" to "ArrowUp",
        "toggle_theme" to "t",
        "toggle_sidebar" to "s",
        "toggle_bars" to "b",
        "bookmark" to "m",
        "help" to "?",
        "toggle_fullscreen" to "F11",
    ),
    val window_size: List<Int> = listOf(1024, 720),
    val last_open_book: String? = null,
)

/** 设置补丁：非空字段才更新（等价桌面 Settings.update 的类型检查语义）。 */
@Serializable
data class SettingsPatch(
    val theme: String? = null,
    val theme_mode: String? = null,
    val font_size: Int? = null,
    val line_height: Double? = null,
    val ui_font_scale: Double? = null,
    val font_family: String? = null,
    val custom_font: String? = null,
    val book_fonts: Map<String, String>? = null,
    val custom_bg: String? = null,
    val custom_primary: String? = null,
    val custom_accent: String? = null,
    val custom_text: String? = null,
    val page_width: Double? = null,
    val bars_pinned: Boolean? = null,
    val pagination: Boolean? = null,
    val dual_page: Boolean? = null,
    val auto_dual: Boolean? = null,
    val shelf_view: String? = null,
    val shelf_sort: String? = null,
    val hide_title_brackets: Boolean? = null,
    val margin_px: Int? = null,
    val gap_px: Int? = null,
    val brightness: Double? = null,
    val rsvp_rate: Int? = null,
    val autoscroll_speed: Double? = null,
    val show_ruler: Boolean? = null,
    val show_statusbar: Boolean? = null,
    val shortcuts: Map<String, String>? = null,
    val window_size: List<Int>? = null,
    val last_open_book: String? = null,
)

/** 扁平键值设置（JSON，原子写，settings_version<3 一次性迁移）。 */
class Settings(private val file: File) {

    private val lock = ReentrantLock()
    private var data: SettingsData = SettingsData()

    fun load() {
        val text = try {
            file.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            logWarn("AnkeShelf", "settings.json 读取失败：${e.message}")
            null
        }
        if (text.isNullOrBlank()) return
        val rawVersion = try {
            Shelf.json.parseToJsonElement(text)
                .jsonObject["settings_version"]?.jsonPrimitive?.intOrNull ?: 0
        } catch (e: Exception) {
            logWarn("AnkeShelf", "settings.json 版本解析失败：${e.message}")
            0
        }
        val loaded = try {
            Shelf.json.decodeFromString<SettingsData>(text)
        } catch (e: Exception) {
            // 与 readJsonStore 的损坏处理一致：隔离原文件、回退默认、保留诊断信息。
            isolateCorrupt(file)
            logWarn("AnkeShelf", "settings.json 损坏，已隔离并回退默认：$e")
            return
        }
        lock.withLock {
            data = loaded
            if (rawVersion < 3) {
                // 旧版设置文件：一次性切到新默认值（滚动阅读 + 内置默认字体）
                data = loaded.copy(
                    settings_version = 3,
                    pagination = false,
                    custom_font = "sys:weidqczfkyxk.ttf",
                )
                save()
            }
        }
    }

    fun save() {
        lock.withLock {
            atomicWriteJson(file, json.encodeToString(SettingsData.serializer(), data))
        }
    }

    fun getAll(): SettingsData = lock.withLock { data }

    fun update(patch: SettingsPatch) {
        lock.withLock {
            // 用 JSON 合并实现类型安全的 partial update，避免 30 个字段手写 copy。
            val current = Shelf.json.encodeToJsonElement(SettingsData.serializer(), data).jsonObject
            val patchFields = Shelf.json.encodeToJsonElement(SettingsPatch.serializer(), patch).jsonObject
                .filterValues { it !is JsonNull }
            data = Shelf.json.decodeFromJsonElement(
                SettingsData.serializer(),
                JsonObject(current + patchFields),
            )
            save()
        }
    }

    private companion object {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
