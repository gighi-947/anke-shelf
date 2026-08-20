package io.github.gighi947.ankeshelf.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 骨碌碌公开接口失败（message 为面向用户的中文说明，与桌面 GululuApiError 同文案）。 */
class GululuApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 目录/详情索引（字段保持接口原样 JsonObject，与桌面 dict 语义一致）。 */
data class GululuIndex(
    val detail: JsonObject,
    val floorIndex: List<JsonObject>,
    val chapterIndex: List<JsonObject>,
)

/** 完整公开快照（顺序稳定：floors 与 floorIndex 一一对应）。 */
data class GululuSnapshot(
    val detail: JsonObject,
    val floorIndex: List<JsonObject>,
    val chapterIndex: List<JsonObject>,
    val floors: List<JsonObject>,
    val commentsByFloor: Map<Int, List<JsonObject>> = emptyMap(),
)

/**
 * 骨碌碌公开阅读接口客户端（Kotlin 版 `app/gululu_client.py`）。
 *
 * 与桌面同协议：`platform: 1` 头、`code != 200` 视为业务失败、楼层正文按
 * [floorBatchSize] 分批 POST、**缺失楼层显式报错**（绝不静默少写一楼）。
 */
class GululuClient(
    private val baseUrl: String = API_BASE,
    private val floorBatchSize: Int = 20,
    timeoutSeconds: Long = 30,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    init {
        require(floorBatchSize >= 1) { "floorBatchSize 必须大于 0" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun requestData(method: String, path: String, body: String? = null): JsonElement {
        val url = baseUrl.trimEnd('/') + path
        val builder = Request.Builder().url(url).header("platform", "1")
        if (method == "POST") {
            builder.post((body ?: "[]").toRequestBody(JSON_MEDIA))
        }
        val text = try {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw GululuApiException("骨碌碌接口请求失败（$path）：HTTP ${response.code}")
                }
                response.body.string().orEmpty()
            }
        } catch (e: GululuApiException) {
            throw e
        } catch (e: Exception) {
            throw GululuApiException("骨碌碌接口请求失败（$path）：${e.message}", e)
        }
        return parseDataPayload(path, text)
    }

    /**
     * 解析 `{code,msg,data}` 信封（纯函数，单测入口；对齐桌面 `_request_data` 的校验顺序）：
     * 非 JSON 对象 / code != 200 / 缺 data 三种都是显式失败。
     */
    fun parseDataPayload(path: String, text: String): JsonElement {
        val payload = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw GululuApiException("骨碌碌接口响应格式错误（$path）")
        val code = (payload["code"] as? JsonPrimitive)?.intOrNull
        if (code != 200) {
            val message = (payload["msg"] as? JsonPrimitive)?.content ?: "未知业务错误"
            throw GululuApiException("骨碌碌接口返回失败（$path）：$message")
        }
        return payload["data"] ?: throw GululuApiException("骨碌碌接口响应缺少 data（$path）")
    }

    private fun report(
        progress: ProgressReporter?,
        cancel: (() -> Boolean)?,
        stage: String,
        current: Int,
        total: Int,
        detail: String,
    ) {
        if (cancel?.invoke() == true) throw GululuCancelledException("骨碌碌任务已取消")
        progress?.invoke(stage, current, total, detail)
    }

    /** 书籍详情 + 楼层目录 + 作者章节目录（三个接口一次取齐，与桌面 fetch_index 同序）。 */
    fun fetchIndex(
        bookId: Int,
        progress: ProgressReporter? = null,
        cancel: (() -> Boolean)? = null,
    ): GululuIndex {
        report(progress, cancel, "metadata", 0, 0, "正在读取书籍信息")
        val detail = requestData("GET", "/reader/opus/detail/$bookId")
        report(progress, cancel, "index", 0, 0, "正在读取目录")
        val floorIndex = requestData("GET", "/reader/floor/index-list/$bookId")
        val chapterData = requestData("GET", "/reader/opus/chapter-index?opusId=$bookId")
        return parseIndexPayloads(detail, floorIndex, chapterData)
    }

    /**
     * 校验并组装索引（纯函数，单测入口）：详情必须是对象、楼层目录必须是数组且每条
     * 带整数 `floorId`、章节目录允许为 null/缺省（作者未分章）。
     */
    fun parseIndexPayloads(
        detail: JsonElement?,
        floorIndex: JsonElement?,
        chapterData: JsonElement?,
    ): GululuIndex {
        val detailObject = detail as? JsonObject
            ?: throw GululuApiException("骨碌碌书籍详情或楼层目录格式错误")
        val floorArray = floorIndex as? JsonArray
            ?: throw GululuApiException("骨碌碌书籍详情或楼层目录格式错误")
        val chapterIndex = when {
            chapterData == null -> emptyList()
            chapterData is JsonObject -> {
                val raw = chapterData["chapterIndex"]
                when {
                    raw == null || (raw is JsonPrimitive && raw.content == "null") -> emptyList()
                    raw is JsonArray -> raw.filterIsInstance<JsonObject>()
                    else -> throw GululuApiException("骨碌碌 chapterIndex 格式错误")
                }
            }
            chapterData is JsonPrimitive && chapterData.content == "null" -> emptyList()
            else -> throw GululuApiException("骨碌碌章节目录格式错误")
        }
        val floors = floorArray.map { element ->
            val item = element as? JsonObject
                ?: throw GululuApiException("骨碌碌楼层目录条目格式错误")
            if ((item["floorId"] as? JsonPrimitive)?.intOrNull == null) {
                throw GululuApiException("骨碌碌楼层目录条目格式错误")
            }
            item
        }
        return GululuIndex(detail = detailObject, floorIndex = floors, chapterIndex = chapterIndex)
    }

    /** 按 ID 批量取楼层正文；返回顺序与入参一致，缺失即报错。 */
    fun fetchFloors(
        bookId: Int,
        floorIds: List<Int>,
        progress: ProgressReporter? = null,
        cancel: (() -> Boolean)? = null,
    ): List<JsonObject> {
        if (floorIds.isEmpty()) return emptyList()
        val batches = mutableListOf<JsonElement>()
        val total = floorIds.size
        var start = 0
        while (start < total) {
            report(progress, cancel, "floors", start, total, "正在获取楼层")
            val batch = floorIds.subList(start, minOf(start + floorBatchSize, total))
            val body = batch.joinToString(prefix = "[", postfix = "]") { it.toString() }
            batches += requestData("POST", "/reader/floor/content-by-ids", body)
            val current = minOf(start + batch.size, total)
            report(progress, cancel, "floors", current, total, "正在获取楼层 $current/$total")
            start += floorBatchSize
        }
        return mergeFloorBatches(floorIds, batches)
    }

    /**
     * 合并分批结果（纯函数，单测入口）：按入参顺序重排，缺失楼层显式报错——
     * 绝不静默少写一楼（少一楼会让后续 append-only 基线校验永久失败）。
     */
    fun mergeFloorBatches(floorIds: List<Int>, batches: List<JsonElement>): List<JsonObject> {
        val byId = mutableMapOf<Int, JsonObject>()
        for (batch in batches) {
            val array = batch as? JsonArray
                ?: throw GululuApiException("骨碌碌楼层正文格式错误")
            for (element in array) {
                val floor = element as? JsonObject ?: continue
                val id = (floor["id"] as? JsonPrimitive)?.intOrNull ?: continue
                byId[id] = floor
            }
        }
        val missing = floorIds.filter { it !in byId }
        if (missing.isNotEmpty()) {
            val preview = missing.take(5).joinToString(", ")
            throw GululuApiException("骨碌碌楼层正文缺失：$preview")
        }
        return floorIds.map { byId.getValue(it) }
    }

    /** 完整快照（顺序稳定）；评论按需在批 5 接入，这里保持不含评论。 */
    fun fetchSnapshot(
        bookId: Int,
        progress: ProgressReporter? = null,
        cancel: (() -> Boolean)? = null,
    ): GululuSnapshot {
        val index = fetchIndex(bookId, progress, cancel)
        val floorIds = index.floorIndex.mapNotNull { (it["floorId"] as? JsonPrimitive)?.intOrNull }
        val floors = fetchFloors(bookId, floorIds, progress, cancel)
        return GululuSnapshot(
            detail = index.detail,
            floorIndex = index.floorIndex,
            chapterIndex = index.chapterIndex,
            floors = floors,
        )
    }

    // ---------- 评论（对齐 app/gululu_comments.py 的抓取部分） ----------

    /**
     * 读取指定作用域的评论（`floorId=0` 表示作品评论），含子回复。
     * 与桌面同分页语义：一级 100/页、子回复 1000/页，按 `total` 收敛；
     * 分页提前结束视为**显式失败**（宁可报错，不静默少评论）。
     */
    fun fetchComments(
        bookId: Int,
        floorIds: List<Int>,
        cancel: (() -> Boolean)? = null,
        onScope: ((current: Int, total: Int) -> Unit)? = null,
    ): Map<Int, List<JsonObject>> {
        val scopes = floorIds.distinct()
        if (scopes.isEmpty()) return emptyMap()
        if (scopes.any { it < 0 }) throw GululuApiException("骨碌碌评论楼层 ID 格式错误")
        val out = linkedMapOf<Int, List<JsonObject>>()
        scopes.forEachIndexed { index, floorId ->
            if (cancel?.invoke() == true) throw GululuCancelledException("骨碌碌任务已取消")
            out[floorId] = fetchCommentScope(bookId, floorId, cancel)
            onScope?.invoke(index + 1, scopes.size)
        }
        return out
    }

    private fun fetchCommentScope(
        bookId: Int,
        floorId: Int,
        cancel: (() -> Boolean)?,
    ): List<JsonObject> {
        val records = fetchCommentPage(bookId, floorId.takeIf { it > 0 }, null, cancel)
        return records.map { item ->
            if (cancel?.invoke() == true) throw GululuCancelledException("骨碌碌任务已取消")
            val id = (item["id"] as? JsonPrimitive)?.intOrNull
                ?: throw GululuApiException("骨碌碌评论条目格式错误")
            val childrenNum = (item["childrenNum"] as? JsonPrimitive)?.intOrNull ?: 0
            if (childrenNum < 0) throw GululuApiException("骨碌碌评论 $id 的 childrenNum 格式错误")
            val children = if (childrenNum > 0) {
                fetchCommentPage(bookId, null, id, cancel)
            } else {
                emptyList()
            }
            JsonObject(item.toMutableMap().apply { put("childrenComment", JsonArray(children)) })
        }
    }

    private fun fetchCommentPage(
        bookId: Int,
        floorId: Int?,
        parentId: Int?,
        cancel: (() -> Boolean)?,
    ): List<JsonObject> {
        val path = if (parentId != null) {
            "/reader/opus/comment/page-children"
        } else {
            "/reader/opus/comment/page"
        }
        val size = if (parentId != null) CHILD_PAGE_SIZE else COMMENT_PAGE_SIZE
        val records = mutableListOf<JsonObject>()
        var current = 1
        while (true) {
            if (cancel?.invoke() == true) throw GululuCancelledException("骨碌碌任务已取消")
            val query = buildString {
                append("?opusId=").append(bookId)
                append("&current=").append(current)
                append("&size=").append(size)
                if (floorId != null) append("&floorId=").append(floorId)
                if (parentId != null) append("&parentId=").append(parentId)
            }
            val page = requestData("GET", path + query) as? JsonObject
                ?: throw GululuApiException("骨碌碌评论分页格式错误")
            val pageRecords = page["records"] as? JsonArray
            val total = (page["total"] as? JsonPrimitive)?.intOrNull
            if (pageRecords == null || total == null || total < 0) {
                throw GululuApiException("骨碌碌评论分页缺少 records 或 total")
            }
            for (element in pageRecords) {
                val item = element as? JsonObject
                    ?: throw GululuApiException("骨碌碌评论条目格式错误")
                val id = (item["id"] as? JsonPrimitive)?.intOrNull
                    ?: throw GululuApiException("骨碌碌评论条目格式错误")
                val content = item["content"] as? JsonPrimitive
                if (content == null || !content.isString) {
                    throw GululuApiException("骨碌碌评论 $id 正文格式错误")
                }
                records.add(item)
            }
            if (records.size >= total) return records
            if (pageRecords.isEmpty()) throw GululuApiException("骨碌碌评论分页提前结束")
            current++
        }
    }

    companion object {
        const val API_BASE = "https://backend.gululu.world"
        const val SITE_BASE = "https://www.gululu.world"
        private const val COMMENT_PAGE_SIZE = 100
        private const val CHILD_PAGE_SIZE = 1000
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** 进度回调签名与桌面 ProgressCallback 一致：(stage, current, total, detail)。 */
typealias ProgressReporter = (String, Int, Int, String) -> Unit

/** 任务取消（与 NGA 链路的取消语义一致：调用方转为"已取消"状态，不留半成品）。 */
class GululuCancelledException(message: String) : Exception(message)
