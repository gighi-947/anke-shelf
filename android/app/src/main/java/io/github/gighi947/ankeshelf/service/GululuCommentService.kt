package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.GululuComments
import io.github.gighi947.ankeshelf.data.atomicWriteJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/** 单个评论作用域的读取结果（cached/stale/error 显式区分，便于界面提示）。 */
data class GululuCommentScope(
    val floorId: Int,
    val comments: List<JsonObject>,
    val cached: Boolean,
    val stale: Boolean,
    val fetchedAt: String,
    val error: String,
)

data class GululuCommentResult(
    val ok: Boolean,
    val sourceId: Int,
    val floors: List<GululuCommentScope>,
    val error: String,
)

/**
 * 骨碌碌在线评论缓存（Kotlin 版 `app/gululu_comment_service.py`）。
 *
 * 语义与桌面一致：
 * - 每个作用域一份端私有缓存 `gululu_library/<id>/comments/<floorId>.json`（不入双端契约）；
 * - 5 分钟内视为新鲜，过期或强制刷新才走网络；
 * - **网络失败不抛给界面**：有缓存则回退为 `stale=true` 并带上错误原因，
 *   完全没有缓存才算硬失败（`ok=false`）——离线可读是这条链路的核心不变量。
 */
class GululuCommentService(
    private val appPaths: AppPaths,
    /** 注入点：默认走真实客户端；测试与离线场景传入假实现（不需要打开 client 类）。 */
    private val fetchComments: (bookId: Int, floorIds: List<Int>) -> Map<Int, List<JsonObject>> =
        { bookId, floorIds -> GululuClient().fetchComments(bookId, floorIds) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun getComments(
        sourceId: Int,
        floorIds: List<Int>,
        refresh: Boolean = false,
    ): GululuCommentResult {
        val scopes = floorIds.distinct()
        if (scopes.isEmpty() || scopes.size > MAX_COMMENT_SCOPES) {
            return GululuCommentResult(
                ok = false,
                sourceId = sourceId,
                floors = emptyList(),
                error = "单次评论请求必须包含 1-$MAX_COMMENT_SCOPES 个楼层",
            )
        }
        if (scopes.any { it < 0 }) {
            return GululuCommentResult(false, sourceId, emptyList(), "评论楼层 ID 格式错误")
        }

        val cached = scopes.associateWith { readCache(sourceId, it) }.toMutableMap()
        val pending = scopes.filter { refresh || cached[it]?.fresh != true }
        var networkError = ""
        if (pending.isNotEmpty()) {
            try {
                val fetched = fetchComments(sourceId, pending)
                for (floorId in pending) {
                    val comments = (fetched[floorId] ?: emptyList()).map {
                        GululuComments.commentToPublic(it)
                    }
                    val payload = buildJsonObject {
                        put("version", 1)
                        put("source_id", sourceId)
                        put("floor_id", floorId)
                        put("fetched_at", io.github.gighi947.ankeshelf.data.nowIso())
                        put("comments", JsonArray(comments))
                    }
                    writeCache(sourceId, floorId, payload)
                    cached[floorId] = CachedScope(payload, fresh = true)
                }
            } catch (e: Exception) {
                // 显式降级：记录原因并回退缓存，绝不把网络抖动变成"评论功能不可用"。
                networkError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                LogEvents.event(
                    "gululu",
                    "comments_fetch_failed",
                    "source_id" to sourceId,
                    "floors" to pending.size,
                    "error" to networkError,
                )
            }
        }

        val floors = mutableListOf<GululuCommentScope>()
        val hardErrors = mutableListOf<String>()
        for (floorId in scopes) {
            val item = cached[floorId]
            if (item == null) {
                val error = networkError.ifEmpty { "评论缓存不可用" }
                hardErrors.add("$floorId: $error")
                floors.add(GululuCommentScope(floorId, emptyList(), false, false, "", error))
                continue
            }
            val wasPending = floorId in pending
            val stale = networkError.isNotEmpty() && wasPending
            floors.add(
                GululuCommentScope(
                    floorId = floorId,
                    comments = (item.payload["comments"] as? JsonArray)
                        ?.filterIsInstance<JsonObject>() ?: emptyList(),
                    cached = !wasPending || stale,
                    stale = stale,
                    fetchedAt = (item.payload["fetched_at"] as? JsonPrimitive)?.content.orEmpty(),
                    error = if (stale) networkError else "",
                ),
            )
        }
        return GululuCommentResult(
            ok = hardErrors.isEmpty(),
            sourceId = sourceId,
            floors = floors,
            error = hardErrors.joinToString("; "),
        )
    }

    private data class CachedScope(val payload: JsonObject, val fresh: Boolean)

    private fun cacheFile(sourceId: Int, floorId: Int): File =
        File(appPaths.gululuLibraryDir, "$sourceId/comments/$floorId.json")

    private fun readCache(sourceId: Int, floorId: Int): CachedScope? {
        val file = cacheFile(sourceId, floorId)
        if (!file.isFile) return null
        val payload = runCatching { json.parseToJsonElement(file.readText()) as? JsonObject }
            .getOrNull() ?: return null
        val versionOk = (payload["version"] as? JsonPrimitive)?.intOrNull == 1
        val sourceOk = (payload["source_id"] as? JsonPrimitive)?.intOrNull == sourceId
        val floorOk = (payload["floor_id"] as? JsonPrimitive)?.intOrNull == floorId
        val fetchedAt = payload["fetched_at"] as? JsonPrimitive
        val comments = payload["comments"] as? JsonArray
        if (!versionOk || !sourceOk || !floorOk || fetchedAt == null || comments == null) return null
        val fresh = (nowMillis() - file.lastModified()) <= CACHE_TTL_MILLIS
        return CachedScope(payload, fresh)
    }

    private fun writeCache(sourceId: Int, floorId: Int, payload: JsonObject) {
        val file = cacheFile(sourceId, floorId)
        file.parentFile?.mkdirs()
        atomicWriteJson(file, json.encodeToString(JsonObject.serializer(), payload))
    }

    companion object {
        const val MAX_COMMENT_SCOPES = 64
        const val CACHE_TTL_MILLIS = 300_000L
    }
}
