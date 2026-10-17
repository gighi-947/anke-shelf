package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UpdateCheckFile(
    val version: Int = 1,
    /** 每本书「自动检查更新」开关；缺省 true（未登记=开）。 */
    val auto_check: Map<String, Boolean> = emptyMap(),
    /** 已检出的待更新楼数（>0 才有条目；书架徽标与阅读器图标共用）。 */
    val available: Map<String, Int> = emptyMap(),
)

/** 旧版 update_prompt_muted.json 载荷：仅迁移期一次性读取后删除。 */
@Serializable
internal data class LegacyUpdatePromptFile(
    val muted: List<String> = emptyList(),
)

/**
 * 更新检查状态（端私有 sidecar，不入双端数据契约）：每本书的「自动检查
 * 更新」开关 + 已检出的待更新楼数。
 *
 * - 容错读：文件缺失/损坏一律回空（更新检查是可选增强，损坏不值得进
 *   权威存储横幅打断用户）；写走 [atomicWriteJson] 原子替换。
 * - [revision] 每次写盘递增，UI 轮询据此刷新书架徽标；无变化的写入会
 *   被跳过（不写盘也不递增）。
 * - [markChecked] / [checkedThisSession] 是会话内节流（内存 set，不持久化），
 *   防止书架往返/轮询对同一本书反复发网络检查。
 * - 旧版 update_prompt_muted.json 的 muted 书迁移为 auto_check=false。
 */
class UpdateCheckStore(
    private val file: File,
    legacyMutedFile: File? = null,
) {

    private val autoCheck = mutableMapOf<String, Boolean>()
    private val available = mutableMapOf<String, Int>()
    private val checked = mutableSetOf<String>()

    @Volatile
    var revision: Int = 0
        private set

    init {
        val loaded = try {
            json.decodeFromString<UpdateCheckFile>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
        if (loaded != null) {
            autoCheck.putAll(loaded.auto_check)
            available.putAll(loaded.available)
        }
        migrateLegacy(legacyMutedFile)
    }

    /** 未登记的书默认开。 */
    @Synchronized
    fun isAutoCheckEnabled(bookId: String): Boolean = autoCheck[bookId] ?: true

    /** 设置自动检查开关；关闭时同时清除该书 available 条目（不再显示徽标）。 */
    @Synchronized
    fun setAutoCheck(bookId: String, enabled: Boolean) {
        if (bookId.isBlank()) return
        val current = autoCheck[bookId] ?: true
        val changed = if (enabled) {
            current != true
        } else {
            current != false || available.containsKey(bookId)
        }
        if (!changed) return
        autoCheck[bookId] = enabled
        if (!enabled) available.remove(bookId)
        persist()
    }

    /** 待更新楼数（无条目=0）。 */
    @Synchronized
    fun availableFloors(bookId: String): Int = available[bookId] ?: 0

    /** 记录待更新楼数；<=0 删除条目（值未变化不写盘）。 */
    @Synchronized
    fun setAvailable(bookId: String, floors: Int) {
        if (bookId.isBlank()) return
        if (floors <= 0) {
            if (available.remove(bookId) == null) return
        } else {
            if (available[bookId] == floors) return
            available[bookId] = floors
        }
        persist()
    }

    @Synchronized
    fun availableSnapshot(): Map<String, Int> = available.toMap()

    @Synchronized
    fun markChecked(bookId: String) {
        checked.add(bookId)
    }

    @Synchronized
    fun checkedThisSession(bookId: String): Boolean = checked.contains(bookId)

    private fun migrateLegacy(legacy: File?) {
        if (legacy == null || !legacy.exists()) return
        val muted = try {
            json.decodeFromString<LegacyUpdatePromptFile>(
                legacy.readText(Charsets.UTF_8),
            ).muted
        } catch (_: Exception) {
            // 损坏的旧文件无法恢复屏蔽清单：按空迁移，避免每次构造都重试失败。
            emptyList()
        }
        var changed = false
        for (id in muted) {
            if (id.isBlank() || autoCheck[id] == false) continue
            autoCheck[id] = false
            changed = true
        }
        legacy.delete()
        if (changed) persist()
    }

    private fun persist() {
        atomicWriteJson(
            file,
            json.encodeToString(
                UpdateCheckFile.serializer(),
                UpdateCheckFile(auto_check = autoCheck.toMap(), available = available.toMap()),
            ),
        )
        revision += 1
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
