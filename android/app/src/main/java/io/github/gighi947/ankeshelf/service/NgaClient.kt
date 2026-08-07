package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.NativeFloor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** NGA 接口异常（message 为面向用户的中文说明）。 */
class NgaHttpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 楼层头部信息（供下载列表/进度展示；完整数据见 NgaPageData.floors）。 */
data class NgaFloorHead(
    val pid: Long,
    val lou: Int,
    val username: String,
    val userId: Long,
    val likeNum: Int,
    val rawContent: String,
    val commentCount: Int,
)

/** app_api.php?__lib=post&__act=list 的一页摘要（旧接口，保留兼容）。 */
data class NgaPageSummary(
    val code: Int,
    val msg: String,
    val tid: Long,
    val title: String,
    val author: String,
    val authorId: Long,
    val totalPage: Int,
    val vrows: Int,
    val floors: List<NgaFloorHead>,
)

/** 完整一页数据（对齐桌面 nga.py analyze_floors / page 语义）。 */
data class NgaPageData(
    val code: Int,
    val msg: String,
    val tid: Long,
    val title: String,
    val author: String,
    val authorId: Long,
    val forumName: String,
    val totalPage: Int,
    val vrows: Int,
    val floors: List<NativeFloor>,
)

/**
 * NGA 客户端（对齐桌面 ngapost2md-python/client.py + nga.py）：
 * base_url + UA + Cookie（ngaPassportUid/Cid），POST app_api.php 拉取帖子 JSON。
 */
class NgaClient(
    private val cookieUid: String = "",
    private val cookieCid: String = "",
    private val userAgent: String = DEFAULT_NGA_UA,
    baseUrl: String = "https://bbs.nga.cn",
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(EMPTY_COOKIE_JAR)
        .build()

    private fun request(tid: Long, page: Int, authorId: Long): String {
        val form = FormBody.Builder()
            .add("page", page.toString())
            .add("tid", tid.toString())
            .apply { if (authorId > 0) add("authorid", authorId.toString()) }
            .build()
        val request = Request.Builder()
            .url("$base/app_api.php?__lib=post&__act=list")
            .header("User-Agent", userAgent)
            .apply {
                if (cookieUid.isNotEmpty() && cookieCid.isNotEmpty()) {
                    header("Cookie", "ngaPassportUid=$cookieUid;ngaPassportCid=$cookieCid")
                }
            }
            .post(form)
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw NgaHttpException("NGA 请求失败：HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: NgaHttpException) {
            throw e
        } catch (e: Exception) {
            throw NgaHttpException("无法连接 NGA：${e.message}", e)
        }
    }

    /** 拉取一页楼层摘要（兼容旧调用）。 */
    fun fetchPage(tid: Long, page: Int, authorId: Long = 0): NgaPageSummary {
        return parseResponse(tid, request(tid, page, authorId))
    }

    /** 拉取一页完整楼层（下载流程使用，对齐桌面 page() + analyze_floors）。 */
    fun fetchPageFull(tid: Long, page: Int, authorId: Long = 0): NgaPageData {
        return parsePageFull(tid, request(tid, page, authorId))
    }

    /** 解析 app_api 返回 JSON（旧摘要接口）。 */
    fun parseResponse(tid: Long, body: String): NgaPageSummary {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw NgaHttpException("NGA 返回无法解析：${e.message}", e)
        }
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        val result = root["result"]
        val floors = if (result is JsonArray) {
            result.map { el ->
                val obj = el.jsonObject
                val author = obj["author"]?.jsonObject
                NgaFloorHead(
                    pid = obj["pid"]?.jsonPrimitive?.longOrNull ?: 0L,
                    lou = obj["lou"]?.jsonPrimitive?.intOrNull ?: -1,
                    username = author?.get("username")?.jsonPrimitive?.content ?: "",
                    userId = author?.get("uid")?.jsonPrimitive?.longOrNull ?: 0L,
                    likeNum = obj["vote_good"]?.jsonPrimitive?.intOrNull ?: 0,
                    rawContent = obj["content"]?.jsonPrimitive?.content ?: "",
                    commentCount = (obj["comments"] as? JsonArray)?.size ?: 0,
                )
            }
        } else {
            emptyList()
        }
        return NgaPageSummary(
            code = code,
            msg = root["msg"]?.jsonPrimitive?.content ?: "",
            tid = tid,
            title = root["tsubject"]?.jsonPrimitive?.content ?: "",
            author = root["tauthor"]?.jsonPrimitive?.content ?: "",
            authorId = root["tauthorid"]?.jsonPrimitive?.longOrNull ?: 0L,
            totalPage = root["totalPage"]?.jsonPrimitive?.intOrNull ?: 1,
            vrows = root["vrows"]?.jsonPrimitive?.intOrNull ?: 1,
            floors = floors,
        )
    }

    /** 解析完整楼层（对齐桌面 analyze_floors：lou/pid/timestamp/author/comments 递归）。 */
    fun parsePageFull(tid: Long, body: String): NgaPageData {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw NgaHttpException("NGA 返回无法解析：${e.message}", e)
        }
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        val result = root["result"]
        val floors = if (result is JsonArray) {
            result.map { parseFloor(it.jsonObject, false) }
        } else {
            emptyList()
        }
        return NgaPageData(
            code = code,
            msg = root["msg"]?.jsonPrimitive?.content ?: "",
            tid = tid,
            title = root["tsubject"]?.jsonPrimitive?.content ?: "",
            author = root["tauthor"]?.jsonPrimitive?.content ?: "",
            authorId = root["tauthorid"]?.jsonPrimitive?.longOrNull ?: 0L,
            forumName = root["forum_name"]?.jsonPrimitive?.content ?: "",
            totalPage = root["totalPage"]?.jsonPrimitive?.intOrNull ?: 1,
            vrows = root["vrows"]?.jsonPrimitive?.intOrNull ?: 1,
            floors = floors,
        )
    }

    private fun parseFloor(obj: kotlinx.serialization.json.JsonObject, isComment: Boolean): NativeFloor {
        val author = obj["author"]?.jsonObject
        val comments = obj["comments"]
        val commentFloors = if (comments is JsonArray) {
            var lou = 1
            comments.mapNotNull { c ->
                val f = parseFloor(c.jsonObject, true)
                if (f.pid != 0L) f.copy(lou = lou++) else null
            }
        } else {
            emptyList()
        }
        return NativeFloor(
            pid = obj["pid"]?.jsonPrimitive?.longOrNull ?: 0L,
            lou = if (isComment) -1 else (obj["lou"]?.jsonPrimitive?.intOrNull ?: -1),
            timestamp = obj["postdatetimestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
            username = author?.get("username")?.jsonPrimitive?.content ?: "",
            user_id = author?.get("uid")?.jsonPrimitive?.longOrNull ?: 0L,
            like_num = obj["vote_good"]?.jsonPrimitive?.intOrNull ?: 0,
            raw_content = obj["content"]?.jsonPrimitive?.content ?: "",
            comments = commentFloors,
        )
    }

    companion object {
        /** 与桌面 config.ini 默认模板一致的兜底浏览器 UA。 */
        const val DEFAULT_NGA_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val EMPTY_COOKIE_JAR = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        }
    }
}
