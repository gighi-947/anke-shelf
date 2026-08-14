package io.github.gighi947.ankeshelf.service

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 结构化诊断事件：`component event key=value`，同时写 Logcat 并保留最近
 * [MAX_EVENTS] 条供诊断包导出（对齐桌面 logutil 的字段约定）。
 */
object LogEvents {
    const val MAX_EVENTS = 200

    private val events = ConcurrentLinkedDeque<String>()

    fun event(component: String, event: String, vararg fields: Pair<String, Any?>) {
        val sb = StringBuilder()
        sb.append(Instant.now()).append(' ')
            .append(component).append(' ')
            .append(event)
        for ((key, value) in fields) {
            if (value == null) continue
            sb.append(' ').append(key).append('=').append(value)
        }
        val line = sb.toString()
        runCatching { android.util.Log.d("AnkeShelf", line) }
        while (events.size >= MAX_EVENTS) events.pollFirst()
        events.addLast(line)
    }

    fun snapshot(): List<String> = events.toList()

    /** 书 ID → 12 位短哈希：诊断与日志不出现原始 book_id。 */
    fun bookIdHash(bookId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bookId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
    }
}
