package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        } catch (_: Exception) {
            null
        }
        if (text.isNullOrBlank()) return
        val rawVersion = try {
            Shelf.json.parseToJsonElement(text)
                .jsonObject["settings_version"]?.jsonPrimitive?.intOrNull ?: 0
        } catch (_: Exception) {
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
            data = data.copy(
                theme = patch.theme ?: data.theme,
                theme_mode = patch.theme_mode ?: data.theme_mode,
                font_size = patch.font_size ?: data.font_size,
                line_height = patch.line_height ?: data.line_height,
                ui_font_scale = patch.ui_font_scale ?: data.ui_font_scale,
                font_family = patch.font_family ?: data.font_family,
                custom_font = patch.custom_font ?: data.custom_font,
                book_fonts = patch.book_fonts ?: data.book_fonts,
                custom_bg = patch.custom_bg ?: data.custom_bg,
                custom_primary = patch.custom_primary ?: data.custom_primary,
                custom_accent = patch.custom_accent ?: data.custom_accent,
                custom_text = patch.custom_text ?: data.custom_text,
                page_width = patch.page_width ?: data.page_width,
                bars_pinned = patch.bars_pinned ?: data.bars_pinned,
                pagination = patch.pagination ?: data.pagination,
                dual_page = patch.dual_page ?: data.dual_page,
                auto_dual = patch.auto_dual ?: data.auto_dual,
                shelf_view = patch.shelf_view ?: data.shelf_view,
                shelf_sort = patch.shelf_sort ?: data.shelf_sort,
                hide_title_brackets = patch.hide_title_brackets ?: data.hide_title_brackets,
                margin_px = patch.margin_px ?: data.margin_px,
                gap_px = patch.gap_px ?: data.gap_px,
                brightness = patch.brightness ?: data.brightness,
                rsvp_rate = patch.rsvp_rate ?: data.rsvp_rate,
                autoscroll_speed = patch.autoscroll_speed ?: data.autoscroll_speed,
                show_ruler = patch.show_ruler ?: data.show_ruler,
                show_statusbar = patch.show_statusbar ?: data.show_statusbar,
                shortcuts = patch.shortcuts ?: data.shortcuts,
                window_size = patch.window_size ?: data.window_size,
                last_open_book = patch.last_open_book ?: data.last_open_book,
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
