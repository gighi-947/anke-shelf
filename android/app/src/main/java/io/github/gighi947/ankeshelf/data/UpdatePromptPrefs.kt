package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UpdatePromptFile(
    val version: Int = 1,
    /** 已选择"不再提示进书更新"的书（bookId，保序）。 */
    val muted: List<String> = emptyList(),
)

/**
 * 进书更新提示屏蔽（端私有 sidecar，不入双端数据契约）。
 *
 * 容错读：文件缺失/损坏一律回空集（提示屏蔽是可选增强，损坏不值得进
 * 权威存储横幅打断用户）；写走 [atomicWriteJson] 原子替换。
 */
class UpdatePromptPrefs(private val file: File) {

    private val muted = mutableSetOf<String>()

    init {
        val loaded = try {
            json.decodeFromString<UpdatePromptFile>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
        if (loaded != null) muted.addAll(loaded.muted)
    }

    @Synchronized
    fun isMuted(bookId: String): Boolean = muted.contains(bookId)

    /** 屏蔽指定书的进书更新提示（幂等：已屏蔽或空 id 不写盘）。 */
    @Synchronized
    fun mute(bookId: String) {
        if (bookId.isBlank() || !muted.add(bookId)) return
        atomicWriteJson(
            file,
            json.encodeToString(UpdatePromptFile.serializer(), UpdatePromptFile(muted = muted.toList())),
        )
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
