package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class GululuUnlocksFile(
    val version: Int = 1,
    /** bookId → 已揭示的骰点分组 ID（保序，便于裁剪最早的记录）。 */
    val unlocked: Map<String, List<String>> = emptyMap(),
    /** bookId → 骰点掩盖显式开关（缺省按规则推断：有揭示记录=开，无记录=关）。 */
    val mask_enabled: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class GululuCluesFile(
    val version: Int = 1,
    /** bookId → (线索标题 → 口令)；只存本机，永不上传。 */
    val clues: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * 骨碌碌阅读解锁状态：骰点分组揭示记录与秘密线索口令。
 *
 * 端私有存储（`gululu_unlocks.json` / `gululu_clues.json`，不入双端数据契约；
 * 桌面对应实现放在前端 localStorage）。与桌面同上限：单书最多保留
 * [MAX_GROUPS] 组，超出时裁剪到 [TRIM_GROUPS]（保留最近解锁的）。
 */
class GululuUnlockStore(private val unlocksFile: File, private val cluesFile: File) {

    private val lock = ReentrantLock()
    private val unlocksGuard = StoreWriteGuard()
    private val cluesGuard = StoreWriteGuard()
    private var unlocked: MutableMap<String, MutableList<String>> = mutableMapOf()
    private var maskEnabled: MutableMap<String, Boolean> = mutableMapOf()
    private var clues: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    fun load(): List<StoreLoadIssue> {
        val (loadedUnlocks, unlocksIssue) =
            loadGuarded(unlocksFile, unlocksGuard) { GululuUnlocksFile() }
        val (loadedClues, cluesIssue) =
            loadGuarded(cluesFile, cluesGuard) { GululuCluesFile() }
        lock.withLock {
            unlocked = loadedUnlocks.unlocked.mapValues { it.value.toMutableList() }.toMutableMap()
            maskEnabled = loadedUnlocks.mask_enabled.toMutableMap()
            clues = loadedClues.clues.mapValues { it.value.toMutableMap() }.toMutableMap()
        }
        return listOfNotNull(unlocksIssue, cluesIssue)
    }

    // ---------- 骰点解锁 ----------

    fun unlockedGroups(bookId: String): List<String> = lock.withLock {
        unlocked[bookId]?.toList() ?: emptyList()
    }

    /** 记录一组已揭示；返回是否有变化（重复解锁不写盘）。 */
    fun unlock(bookId: String, groupId: String): Boolean {
        if (groupId.isBlank()) return false
        val changed = lock.withLock {
            val list = unlocked.getOrPut(bookId) { mutableListOf() }
            if (list.contains(groupId)) {
                false
            } else {
                list.add(groupId)
                if (list.size > MAX_GROUPS) {
                    // 保留最近解锁的，丢掉最早的（与桌面 3000→2000 裁剪一致）
                    val trimmed = list.takeLast(TRIM_GROUPS)
                    list.clear()
                    list.addAll(trimmed)
                }
                true
            }
        }
        if (changed) saveUnlocks()
        return changed
    }

    /** 批量揭示（整楼 / 接下来 N 组）；返回真正新增的组数。 */
    fun unlockAll(bookId: String, groupIds: List<String>): Int {
        var added = 0
        val changed = lock.withLock {
            val list = unlocked.getOrPut(bookId) { mutableListOf() }
            for (groupId in groupIds) {
                if (groupId.isNotBlank() && !list.contains(groupId)) {
                    list.add(groupId)
                    added++
                }
            }
            if (list.size > MAX_GROUPS) {
                val trimmed = list.takeLast(TRIM_GROUPS)
                list.clear()
                list.addAll(trimmed)
            }
            added > 0
        }
        if (changed) saveUnlocks()
        return added
    }

    /** 单书重置：清骰点解锁与线索，不影响阅读进度与书签。 */
    fun reset(bookId: String) {
        val changed = lock.withLock {
            val a = unlocked.remove(bookId) != null
            val b = clues.remove(bookId) != null
            a || b
        }
        if (changed) {
            saveUnlocks()
            saveClues()
        }
    }

    // ---------- 骰点掩盖开关 ----------

    /** 骰点掩盖是否启用：显式开关优先；缺省规则=有揭示记录的书视为已在用（开），
     *  无记录的书默认关（2026-10 用户反馈：默认掩盖会吞剧情，改为菜单选择性开启）。 */
    fun isMaskEnabled(bookId: String): Boolean = lock.withLock {
        maskEnabled[bookId] ?: unlocked.containsKey(bookId)
    }

    fun setMaskEnabled(bookId: String, enabled: Boolean) {
        val changed = lock.withLock {
            if (maskEnabled[bookId] == enabled) false else { maskEnabled[bookId] = enabled; true }
        }
        if (changed) saveUnlocks()
    }

    // ---------- 秘密线索 ----------

    fun clues(bookId: String): Map<String, String> = lock.withLock {
        clues[bookId]?.toMap() ?: emptyMap()
    }

    /** 收集线索（标题 → 口令）；返回是否新增/更新。 */
    fun collectClue(bookId: String, title: String, password: String): Boolean {
        if (title.isBlank() || password.isBlank()) return false
        val changed = lock.withLock {
            val map = clues.getOrPut(bookId) { mutableMapOf() }
            if (map[title] == password) false else { map[title] = password; true }
        }
        if (changed) saveClues()
        return changed
    }

    /**
     * 用已收集的线索尝试解开秘密：逐个口令试解，成功即返回明文。
     * 与桌面一致：口令不匹配不是错误，只是"还没找到对应线索"。
     */
    fun revealSecret(bookId: String, cipher: String): GululuSecretReveal {
        val known = clues(bookId)
        if (known.isEmpty()) return GululuSecretReveal.NoClue
        for ((title, password) in known) {
            val plain = runCatching { GululuAssistant.decryptCryptoJsSecret(cipher, password) }.getOrNull()
            if (plain != null) return GululuSecretReveal.Ok(plain, title)
        }
        return GululuSecretReveal.NoClue
    }

    private fun saveUnlocks() {
        if (unlocksGuard.writeBlocked()) {
            logWarn("AnkeShelf", "gululu_unlocks.json 读取失败过，跳过写入以保护原文件")
            return
        }
        val snapshot = lock.withLock {
            unlocked.mapValues { it.value.toList() } to maskEnabled.toMap()
        }
        atomicWriteJson(
            unlocksFile,
            json.encodeToString(
                GululuUnlocksFile.serializer(),
                GululuUnlocksFile(unlocked = snapshot.first, mask_enabled = snapshot.second),
            ),
        )
    }

    private fun saveClues() {
        if (cluesGuard.writeBlocked()) {
            logWarn("AnkeShelf", "gululu_clues.json 读取失败过，跳过写入以保护原文件")
            return
        }
        val snapshot = lock.withLock { clues.mapValues { it.value.toMap() } }
        atomicWriteJson(
            cluesFile,
            json.encodeToString(GululuCluesFile.serializer(), GululuCluesFile(clues = snapshot)),
        )
    }

    companion object {
        const val MAX_GROUPS = 3000
        const val TRIM_GROUPS = 2000

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/** 秘密揭示结果：明文只交给宿主弹窗，绝不写回正文。 */
sealed interface GululuSecretReveal {
    data class Ok(val plaintext: String, val clueTitle: String) : GululuSecretReveal
    data object NoClue : GululuSecretReveal
}
