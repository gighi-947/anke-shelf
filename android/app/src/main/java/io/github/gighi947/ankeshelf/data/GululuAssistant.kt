package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 秘密载荷无法校验或解密（对齐桌面 GululuSecretError）。 */
class GululuSecretException(message: String) : Exception(message)

/**
 * 骨碌碌全能助手文本协议（Kotlin 版 `app/gululu_assistant.py`）。
 *
 * 设计红线（与桌面一致）：所有协议在**导入期**转成惰性 AST 节点 / `data-*` 标记，
 * 运行时只切换遮罩与可见状态，绝不重写正文——否则 `text_offset` 会漂移。
 * 秘密密文与线索保持 inert，明文只在宿主弹窗出现。
 */
object GululuAssistant {

    const val MAX_SECRET_TITLE = 120
    const val MAX_SECRET_PASSWORD = 1024
    const val MAX_SECRET_CIPHER = 131072

    private const val INVISIBLE = " \t\r\n\u200b\ufeff"
    private const val ZERO_WIDTH = "\u200b\ufeff"

    private val DICE_CHAIN = Regex(
        "((?:【?)\\d+[dD]\\d+(?:[^=\\r\\n]*?=\\s*【?[\\d.]+】?)+)([^\\r\\n]*)",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val DICE_RESULT = Regex("(=\\s*【?)([\\d.]+)(】?)")
    private val QUOTE_START = Regex("^<引用\\s+id=\"(\\d+)\"\\s+floor=\"(\\d+)\">$")
    private val INLINE_QUOTE = Regex(
        "<引用\\s+id=\"(\\d+)\"\\s+floor=\"(\\d+)\">(.*?)</引用>",
        RegexOption.DOT_MATCHES_ALL,
    )

    // ---------- AST 小工具 ----------

    fun nodeText(node: JsonObject): String {
        if (node.type() == "text") return node.text()
        val content = node["content"] as? JsonArray ?: return ""
        return content.filterIsInstance<JsonObject>().joinToString("") { nodeText(it) }
    }

    private fun JsonObject.type(): String = (this["type"] as? JsonPrimitive)?.contentOrNull().orEmpty()

    private fun JsonObject.text(): String = (this["text"] as? JsonPrimitive)?.contentOrNull().orEmpty()

    private fun JsonPrimitive.contentOrNull(): String? =
        if (isString) content else content.takeUnless { it == "null" }

    private fun node(type: String, attrs: JsonObject, content: List<JsonElement>): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive(type),
                "attrs" to attrs,
                "content" to JsonArray(content),
            ),
        )

    private fun JsonObject.withText(value: String): JsonObject =
        JsonObject(toMutableMap().apply { put("text", JsonPrimitive(value)) })

    private fun JsonObject.withContent(children: List<JsonElement>): JsonObject =
        JsonObject(toMutableMap().apply { put("content", JsonArray(children)) })

    fun directiveError(message: String): JsonObject = node(
        "gululuDirectiveError",
        buildJsonObject { put("message", message) },
        emptyList(),
    )

    // ---------- 内联协议（引用 / 秘密 / 线索） ----------

    /** 在 [start] 处尝试识别一个内联协议；返回 (结束位置, 节点) 或 null。 */
    internal fun inlineProtocolAt(text: String, start: Int): Pair<Int, JsonObject>? {
        INLINE_QUOTE.matchAt(text, start)?.let { quote ->
            return quote.range.last + 1 to node(
                "gululuAssistantQuote",
                buildJsonObject {
                    put("bookId", quote.groupValues[1].toInt())
                    put("floorNumber", quote.groupValues[2].toInt())
                },
                listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(quote.groupValues[3])))),
            )
        }
        val prefixes = listOf(
            Triple("<发现秘密>", "</发现秘密>", "gululuSecretClue"),
            Triple("<秘密>", "</秘密>", "gululuSecret"),
        )
        for ((opening, closing, nodeType) in prefixes) {
            if (!text.startsWith(opening, start)) continue
            val end = text.indexOf(closing, start + opening.length)
            if (end < 0) return null
            val payload = text.substring(start + opening.length, end)
            if (!payload.startsWith("[") || !payload.contains("]")) return null
            val parts = payload.substring(1).split("]", limit = 2)
            val title = parts[0].trim()
            val value = parts.getOrElse(1) { "" }.trim()
            if (title.isEmpty() || title.length > MAX_SECRET_TITLE) return null
            val attrs = if (nodeType == "gululuSecret") {
                if (value.isEmpty() || value.length > MAX_SECRET_CIPHER) return null
                buildJsonObject {
                    put("title", title)
                    put("cipher", value)
                }
            } else {
                if (value.isEmpty() || value.length > MAX_SECRET_PASSWORD) return null
                buildJsonObject {
                    put("title", title)
                    put("password", value)
                }
            }
            return end + closing.length to node(nodeType, attrs, emptyList())
        }
        return null
    }

    private fun splitProtocolText(node: JsonObject): List<JsonObject> {
        val text = node.text()
        val output = mutableListOf<JsonObject>()
        var cursor = 0
        var plainStart = 0
        while (cursor < text.length) {
            if (text[cursor] in ZERO_WIDTH) {
                cursor++
                continue
            }
            val parsed = inlineProtocolAt(text, cursor)
            if (parsed == null) {
                cursor++
                continue
            }
            val (end, protocolNode) = parsed
            val prefix = text.substring(plainStart, cursor).trim { it in ZERO_WIDTH }
            if (prefix.isNotEmpty()) output.add(node.withText(prefix))
            output.add(protocolNode)
            cursor = end
            while (cursor < text.length && text[cursor] in ZERO_WIDTH) cursor++
            plainStart = cursor
        }
        val suffix = text.substring(plainStart).trim { it in ZERO_WIDTH }
        if (suffix.isNotEmpty()) output.add(node.withText(suffix))
        return output.ifEmpty { listOf(node) }
    }

    private fun prepareInline(node: JsonObject): List<JsonObject> {
        if (node.type() == "text") return splitProtocolText(node)
        val content = node["content"] as? JsonArray ?: return listOf(node)
        val prepared = content.filterIsInstance<JsonObject>().flatMap { prepareInline(it) }
        return listOf(node.withContent(prepared))
    }

    // ---------- 段落级协议（折叠 / 引用块） ----------

    /** 内联秘密与段落折叠标记 → 惰性 AST 节点（对齐 `prepare_assistant_nodes`）。 */
    fun prepareAssistantNodes(nodes: List<JsonElement>): List<JsonObject> {
        val prepared = prepareQuoteBlocks(
            nodes.filterIsInstance<JsonObject>().flatMap { prepareInline(it) },
        )
        val output = mutableListOf<JsonObject>()
        // 折叠是可嵌套的：用栈保存"当前正在收集内容的折叠块"及其子节点缓冲。
        val foldTitles = mutableListOf<String>()
        val foldBuffers = mutableListOf<MutableList<JsonElement>>()

        fun append(node: JsonObject) {
            if (foldBuffers.isEmpty()) output.add(node) else foldBuffers.last().add(node)
        }

        fun closeFold(error: String? = null) {
            val title = foldTitles.removeAt(foldTitles.lastIndex)
            val buffer = foldBuffers.removeAt(foldBuffers.lastIndex)
            if (error != null) buffer.add(directiveError(error))
            val fold = node(
                "gululuAssistantFold",
                buildJsonObject { put("title", title) },
                buffer,
            )
            append(fold)
        }

        for (item in prepared) {
            val text = nodeText(item).trim { it in INVISIBLE }
            if (text.startsWith("<折叠>")) {
                val titleText = text.removePrefix("<折叠>").trim()
                if (!titleText.startsWith("[")) {
                    append(directiveError("折叠指令缺少标题"))
                    continue
                }
                val raw = if (titleText.endsWith("]")) {
                    titleText.substring(1, titleText.length - 1)
                } else {
                    titleText.substring(1)
                }
                val title = raw.trim()
                if (title.isEmpty()) {
                    append(directiveError("折叠指令缺少标题"))
                    continue
                }
                foldTitles.add(title.take(MAX_SECRET_TITLE))
                foldBuffers.add(mutableListOf())
                continue
            }
            if (text == "</折叠结束>") {
                if (foldTitles.isNotEmpty()) closeFold() else append(directiveError("折叠结束标记没有对应的开始标记"))
                continue
            }
            append(item)
        }
        // 未闭合的折叠：内容保留 + 显式错误提示（不吞掉正文）
        while (foldTitles.isNotEmpty()) closeFold("折叠指令缺少结束标记")
        return output
    }

    private fun prepareQuoteBlocks(nodes: List<JsonObject>): List<JsonObject> {
        val output = mutableListOf<JsonObject>()
        var index = 0
        while (index < nodes.size) {
            val current = nodes[index]
            val text = nodeText(current).trim { it in INVISIBLE }
            val start = QUOTE_START.matchEntire(text)
            if (start == null) {
                if (text == "</引用>") {
                    output.add(directiveError("引用结束标记没有对应的开始标记"))
                } else {
                    output.add(current)
                }
                index++
                continue
            }
            var closing = index + 1
            while (closing < nodes.size) {
                if (nodeText(nodes[closing]).trim { it in INVISIBLE } == "</引用>") break
                closing++
            }
            if (closing >= nodes.size) {
                output.add(directiveError("引用指令缺少结束标记"))
                index++
                continue
            }
            output.add(
                node(
                    "gululuAssistantQuote",
                    buildJsonObject {
                        put("bookId", start.groupValues[1].toInt())
                        put("floorNumber", start.groupValues[2].toInt())
                    },
                    nodes.subList(index + 1, closing).toList(),
                ),
            )
            index = closing + 1
        }
        return output
    }

    // ---------- 骰点与迷雾 ----------

    private fun diceGroupNode(source: JsonObject, match: MatchResult, groupId: String): JsonObject {
        val chain = match.groupValues[1]
        val suffix = match.groupValues[2]
        val content = mutableListOf<JsonElement>()
        var cursor = 0
        for (result in DICE_RESULT.findAll(chain)) {
            val before = chain.substring(cursor, result.range.first) + result.groupValues[1]
            if (before.isNotEmpty()) content.add(source.withText(before))
            content.add(
                node(
                    "gululuDiceValue",
                    buildJsonObject { put("groupId", groupId) },
                    listOf(source.withText(result.groupValues[2])),
                ),
            )
            if (result.groupValues[3].isNotEmpty()) content.add(source.withText(result.groupValues[3]))
            cursor = result.range.last + 1
        }
        if (cursor < chain.length) content.add(source.withText(chain.substring(cursor)))
        if (suffix.isNotEmpty()) {
            content.add(
                node(
                    "gululuDiceSuffix",
                    buildJsonObject { put("groupId", groupId) },
                    listOf(source.withText(suffix)),
                ),
            )
        }
        return node("gululuDiceGroup", buildJsonObject { put("groupId", groupId) }, content)
    }

    private fun prepareDiceNode(
        node: JsonObject,
        floorId: Int,
        counter: IntArray,
    ): Pair<List<JsonObject>, List<String>> {
        if (node.type() == "text") {
            val text = node.text()
            val output = mutableListOf<JsonObject>()
            val groups = mutableListOf<String>()
            var cursor = 0
            for (match in DICE_CHAIN.findAll(text)) {
                if (match.range.first > cursor) {
                    output.add(node.withText(text.substring(cursor, match.range.first)))
                }
                val groupId = "$floorId-g-${counter[0]}"
                counter[0]++
                output.add(diceGroupNode(node, match, groupId))
                groups.add(groupId)
                cursor = match.range.last + 1
            }
            if (cursor < text.length) output.add(node.withText(text.substring(cursor)))
            return (output.ifEmpty { listOf(node) }) to groups
        }
        val content = node["content"] as? JsonArray
            ?: return listOf(node) to emptyList()
        val preparedContent = mutableListOf<JsonElement>()
        val groups = mutableListOf<String>()
        for (child in content.filterIsInstance<JsonObject>()) {
            val (children, childGroups) = prepareDiceNode(child, floorId, counter)
            preparedContent.addAll(children)
            groups.addAll(childGroups)
        }
        return listOf(node.withContent(preparedContent)) to groups
    }

    /** 稳定骰点分组 + 迷雾锁（对齐 `prepare_reader_experience_nodes`，不改可见文字）。 */
    fun prepareReaderExperienceNodes(nodes: List<JsonElement>, floorId: Int): List<JsonObject> {
        val output = mutableListOf<JsonObject>()
        var activeLock = ""
        val counter = intArrayOf(0)
        for (source in nodes.filterIsInstance<JsonObject>()) {
            val (prepared, groups) = prepareDiceNode(source, floorId, counter)
            for (item in prepared) {
                if (activeLock.isNotEmpty()) {
                    output.add(
                        node(
                            "gululuFogBlock",
                            buildJsonObject { put("groupId", activeLock) },
                            listOf(item),
                        ),
                    )
                } else {
                    output.add(item)
                }
            }
            if (groups.isNotEmpty()) activeLock = groups.last()
        }
        return output
    }

    // ---------- 渲染扩展（对齐 render_assistant_node） ----------

    fun renderer(): GululuNodeRenderer = GululuNodeRenderer { nodeType, attrs, renderChildren, jumpFloorResolver, sourceBookId ->
        val escape = GululuAst::escape
        val title = attrs.str("title").trim()
        when (nodeType) {
            "gululuSecret" ->
                "<button type=\"button\" class=\"gululu-secret-cue\" " +
                    "data-gululu-secret-title=\"${escape(title)}\" " +
                    "data-gululu-secret-cipher=\"${escape(attrs.str("cipher"))}\">" +
                    "秘密：${escape(title)}</button>"
            "gululuSecretClue" ->
                "<button type=\"button\" class=\"gululu-clue-cue\" " +
                    "data-gululu-secret-title=\"${escape(title)}\" " +
                    "data-gululu-secret-password=\"${escape(attrs.str("password"))}\">" +
                    "收集线索：${escape(title)}</button>"
            "gululuAssistantFold" ->
                "<details class=\"gululu-fold gululu-assistant-fold\">" +
                    "<summary>${escape(title.ifEmpty { "折叠内容" })}</summary>" +
                    "${renderChildren()}</details>"
            "gululuDiceGroup" ->
                "<span class=\"gululu-dice-group\" data-gululu-dice-group=\"${escape(attrs.str("groupId"))}\">" +
                    "${renderChildren()}</span>"
            "gululuDiceValue" ->
                "<span class=\"gululu-dice-value\" role=\"button\" tabindex=\"0\" " +
                    "data-gululu-dice-group=\"${escape(attrs.str("groupId"))}\" aria-label=\"揭示骰点结果\">" +
                    "${renderChildren()}</span>"
            "gululuDiceSuffix" ->
                "<span class=\"gululu-dice-suffix\" data-gululu-dice-group=\"${escape(attrs.str("groupId"))}\">" +
                    "${renderChildren()}</span>"
            "gululuFogBlock" ->
                "<div class=\"gululu-fog-block\" data-gululu-fog-lock=\"${escape(attrs.str("groupId"))}\">" +
                    "${renderChildren()}</div>"
            "gululuAssistantQuote" -> {
                val bookId = attrs.intOrZero("bookId")
                val floorNumber = attrs.intOrZero("floorNumber")
                var href = ""
                if (bookId > 0 && floorNumber > 0) {
                    href = if (bookId == sourceBookId && jumpFloorResolver != null) {
                        jumpFloorResolver(floorNumber)
                    } else if (bookId != sourceBookId) {
                        "https://www.gululu.world/book/$bookId?floorSort=$floorNumber"
                    } else {
                        ""
                    }
                }
                val content = renderChildren()
                if (href.isNotEmpty()) {
                    "<a class=\"gululu-assistant-quote\" href=\"${escape(href)}\">$content</a>"
                } else {
                    "<blockquote class=\"gululu-assistant-quote\">$content</blockquote>"
                }
            }
            "jumpFloorComponent" -> {
                val floorNumber = attrs.intOrZero("floorNumber")
                val description = attrs.str("description").trim()
                val label = escape(description.ifEmpty { "跳至第 $floorNumber 楼" })
                val href = if (floorNumber > 0 && jumpFloorResolver != null) {
                    jumpFloorResolver(floorNumber)
                } else {
                    ""
                }
                if (href.isNotEmpty()) {
                    "<a class=\"gululu-jump-floor\" href=\"${escape(href)}\">$label</a>"
                } else {
                    "<span class=\"gululu-jump-floor\" data-gululu-jump-floor=\"$floorNumber\">$label</span>"
                }
            }
            "sensitive" -> "<p class=\"gululu-sensitive-unavailable\">[敏感内容不可用]</p>"
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return if (primitive.isString) primitive.content else primitive.content.takeUnless { it == "null" }.orEmpty()
    }

    private fun JsonObject.intOrZero(key: String): Int =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() } ?: 0

    // ---------- 秘密解密（CryptoJS / OpenSSL salted AES） ----------

    /**
     * 解开 `CryptoJS.AES.encrypt(text, passphrase).toString()` 的输出。
     * 与桌面 `decrypt_cryptojs_secret` 同算法：Base64 → `Salted__` 头 + 8 字节盐 →
     * MD5 EVP KDF 派生 32 字节密钥 + 16 字节 IV → AES-CBC → PKCS7 去填充 → UTF-8。
     * 明文只交给宿主弹窗，绝不写回正文 DOM。
     */
    fun decryptCryptoJsSecret(ciphertext: String?, password: String?): String {
        val encoded = ciphertext?.trim().orEmpty()
        val passwordText = password.orEmpty()
        if (encoded.isEmpty() || encoded.length > MAX_SECRET_CIPHER || passwordText.isEmpty()) {
            throw GululuSecretException("秘密数据格式错误")
        }
        val payload = try {
            java.util.Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw GululuSecretException("秘密数据格式错误")
        }
        val header = if (payload.size >= 8) String(payload, 0, 8, Charsets.ISO_8859_1) else ""
        if (payload.size <= 16 || header != "Salted__" || (payload.size - 16) % 16 != 0) {
            throw GululuSecretException("秘密数据格式错误")
        }
        val salt = payload.copyOfRange(8, 16)
        val passwordBytes = passwordText.toByteArray(Charsets.UTF_8)
        val material = ByteArray(48)
        var filled = 0
        var previous = ByteArray(0)
        val md5 = MessageDigest.getInstance("MD5")
        while (filled < 48) {
            md5.reset()
            md5.update(previous)
            md5.update(passwordBytes)
            md5.update(salt)
            previous = md5.digest()
            val take = minOf(previous.size, 48 - filled)
            System.arraycopy(previous, 0, material, filled, take)
            filled += take
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(material.copyOfRange(0, 32), "AES"),
                IvParameterSpec(material.copyOfRange(32, 48)),
            )
            String(cipher.doFinal(payload.copyOfRange(16, payload.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw GululuSecretException("密码错误或秘密数据损坏")
        }
    }
}
