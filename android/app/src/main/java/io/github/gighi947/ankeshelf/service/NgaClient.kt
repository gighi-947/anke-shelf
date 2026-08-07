package io.github.gighi947.ankeshelf.service

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

/** 楼层头部信息（供下载列表/进度展示；正文在后续 M3 下载流程中全量解析）。 */
data class NgaFloorHead(
    val pid: Long,
    val lou: Int,
    val username: String,
    val userId: Long,
    val likeNum: Int,
    val rawContent: String,
    val commentCount: Int,
)

/** app_api.php?__lib=post&__act=list 的一页摘要。 */
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

/**
 * NGA 客户端（对齐桌面 ngapost2md/client.py）：
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

    /** 拉取一页楼层（真实连通性 spike 与 M3 下载共用）。 */
    fun fetchPage(tid: Long, page: Int, authorId: Long = 0): NgaPageSummary {
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

        val body = try {
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
        return parseResponse(tid, body)
    }

    /** 解析 app_api 返回 JSON（与桌面 analyze_floors 字段对齐）。 */
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

    companion object {
        /** 与桌面 config.ini 默认模板一致的占位 UA 之前的兜底浏览器 UA。 */
        const val DEFAULT_NGA_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val EMPTY_COOKIE_JAR = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        }
    }
}
