package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 评论字段缺失/格式错误（对齐桌面 ValueError → GululuFormatError 的显式失败）。 */
class GululuCommentFormatException(message: String) : Exception(message)

/**
 * 骨碌碌公开评论：公开字段裁剪与 EPUB 评论块渲染
 * （Kotlin 版 `app/gululu_comments.py` 的纯函数部分；分页抓取在 [io.github.gighi947.ankeshelf.service.GululuClient]）。
 *
 * 红线：**只保留阅读器需要的字段**（不把原始用户对象写进本地缓存或界面），
 * 渲染产物与桌面逐字符一致（含评论块的 details/summary 结构与计数）。
 */
object GululuComments {

    /** 原始评论 → 公开字段（递归子回复）；缺 id/content 显式失败。 */
    fun commentToPublic(comment: JsonObject): JsonObject {
        val id = comment.intOrNull("id")
        val content = comment.stringOrNull("content")
        if (id == null || content == null) throw GululuCommentFormatException("评论缺少 id 或 content")
        val childrenRaw = comment["childrenComment"]
        if (childrenRaw != null && childrenRaw !is JsonArray && !childrenRaw.isJsonNull()) {
            throw GululuCommentFormatException("评论 $id 的 childrenComment 格式错误")
        }
        val children = (childrenRaw as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
        return buildJsonObject {
            put("id", id)
            put("content", content)
            put("author", userName(comment))
            put("reply_user", userName(comment, "replyUser", ""))
            put("created_at", comment.stringOrNull("createTime").orEmpty())
            put("likes", comment.intOrNull("likeNum") ?: 0)
            put("paragraph_id", paragraphId(comment))
            putJsonArray("children") {
                children.forEach { add(commentToPublic(it)) }
            }
        }
    }

    /** 评论块（EPUB 内可折叠）；空评论返回空串，与桌面一致。 */
    fun renderCommentBlock(comments: List<JsonObject>, label: String, opus: Boolean = false): String {
        if (comments.isEmpty()) return ""
        val total = comments.sumOf { 1 + ((it["childrenComment"] as? JsonArray)?.size ?: 0) }
        val articles = comments.joinToString("") { renderComment(it, child = false) }
        val classes = if (opus) "gululu-comments gululu-opus-comments" else "gululu-comments"
        return "<details class=\"$classes\" data-comment-count=\"$total\">" +
            "<summary>${GululuAst.escape(label)} $total</summary>" +
            "<div class=\"gululu-comment-list\">$articles</div></details>"
    }

    private fun renderComment(comment: JsonObject, child: Boolean): String {
        val id = comment.intOrNull("id")
        val content = comment.stringOrNull("content")
        if (id == null || content == null) throw GululuCommentFormatException("评论缺少 id 或 content")
        val author = GululuAst.escape(userName(comment))
        val replyUser = userName(comment, "replyUser", "")
        val reply = if (replyUser.isNotEmpty()) {
            " 回复 <span class=\"comment-reply-user\">@${GululuAst.escape(replyUser)}</span>"
        } else {
            ""
        }
        val created = GululuAst.escape(comment.stringOrNull("createTime").orEmpty())
        val likes = comment.intOrNull("likeNum") ?: 0
        val paragraph = paragraphId(comment)
        val paragraphAttr = if (paragraph.isNotEmpty()) {
            " data-paragraph-id=\"${GululuAst.escape(paragraph)}\""
        } else {
            ""
        }
        val childrenRaw = comment["childrenComment"]
        if (childrenRaw != null && childrenRaw !is JsonArray && !childrenRaw.isJsonNull()) {
            throw GululuCommentFormatException("评论 $id 的 childrenComment 格式错误")
        }
        val children = (childrenRaw as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
        val childHtml = children.joinToString("") { renderComment(it, child = true) }
        val classes = if (child) "gululu-comment gululu-comment-reply" else "gululu-comment"
        val replies = if (childHtml.isNotEmpty()) {
            "<div class=\"gululu-comment-replies\">$childHtml</div>"
        } else {
            ""
        }
        return "<article class=\"$classes\" data-comment-id=\"$id\"$paragraphAttr>" +
            "<header class=\"gululu-comment-head\"><strong>$author</strong>$reply" +
            "<span>$created · 赞 $likes</span></header>" +
            "<div class=\"gululu-comment-text\">${text(content)}</div>" +
            replies +
            "</article>"
    }

    /** 正文转义 + 换行 → `<br/>`（与桌面 `_text` 一致，先转义再换行）。 */
    private fun text(value: String): String = GululuAst.escape(value)
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "<br/>")

    private fun userName(comment: JsonObject, field: String = "fromUser", fallback: String = "匿名用户"): String {
        val user = comment[field] as? JsonObject ?: return fallback
        val nick = user.stringOrNull("nickName")?.trim().orEmpty()
        return nick.ifEmpty { fallback }
    }

    /** 段落评论锚点：0/""/"0"/null 一律视为"非段落评论"。 */
    private fun paragraphId(comment: JsonObject): String {
        val raw = comment["paragraphId"] ?: return ""
        if (raw.isJsonNull()) return ""
        val value = (raw as? JsonPrimitive)?.content.orEmpty()
        return if (value.isEmpty() || value == "0" || value == "null") "" else value
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) null else it.intOrNull }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString && primitive.content == "null") return null
        return primitive.content
    }

    private fun JsonElement.isJsonNull(): Boolean =
        this is JsonPrimitive && !isString && content == "null"
}
