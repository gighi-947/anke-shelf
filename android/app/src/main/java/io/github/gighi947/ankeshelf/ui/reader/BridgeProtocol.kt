package io.github.gighi947.ankeshelf.ui.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * WebView 阅读桥协议（P1）：
 * - ready 握手使用单个结构化 JSON payload（`{bridgeVersion, capabilities}`），
 *   不再扩大多参数桥方法；
 * - Kotlin 侧解析并校验版本，不兼容时显式失败并记诊断，避免局部升级静默错配。
 */
object BridgeProtocol {
    const val VERSION = 1

    data class BridgeReady(val version: Int, val capabilities: Set<String>)

    private val json = Json { ignoreUnknownKeys = true }

    fun parseReady(payload: String?): BridgeReady? {
        if (payload.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(payload) }
            .getOrNull()
            ?.let { it as? JsonObject }
            ?: return null
        val version = (obj["bridgeVersion"] as? JsonPrimitive)?.intOrNull ?: -1
        val caps = obj["capabilities"] as? JsonArray ?: return null
        return BridgeReady(
            version,
            caps.mapNotNull { el ->
                val prim = el as? JsonPrimitive
                if (prim == null) {
                    null
                } else {
                    runCatching { prim.content }.getOrNull()?.takeIf { it.isNotBlank() }
                }
            }.toSet(),
        )
    }

    fun isCompatible(ready: BridgeReady?): Boolean = ready != null && ready.version == VERSION
}
