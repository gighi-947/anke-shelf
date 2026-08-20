package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一层的沉浸解析结果（nodes 已含助手协议转换；vfx/背景交给宿主层呈现）。 */
data class ImmersiveFloor(
    val nodes: List<JsonObject>,
    val vfx: String = "",
    val backgroundUpdate: String? = null,
)

/**
 * 骨碌碌作者沉浸指令 → 惰性 EPUB 语义标记（Kotlin 版 `app/gululu_immersive.py`）。
 *
 * 红线（与桌面一致）：只接受**无凭据 HTTPS** 外链；指令段落整体识别后替换为
 * `data-*` 标记节点，不在运行时改写正文；视效每层只取第一个有效值。
 */
object GululuImmersive {

    private val MANUAL_MUSIC = Regex(
        "^\\s*<音乐>\\s*(.*?)\\s*[♪♫]\\s*(.*?)\\s*</音乐结束>\\s*$",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val AUTO_MUSIC = Regex(
        "^\\s*<自动音乐>\\s*(.*?)\\s*[♪♫]\\s*(.*?)\\s*</自动音乐结束>\\s*$",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val VFX = Regex("^\\s*<特效[:：]\\s*(.*?)\\s*>\\s*$", RegexOption.DOT_MATCHES_ALL)

    private val VFX_NAMES = mapOf(
        "下雨" to "rain", "雨" to "rain", "rain" to "rain",
        "下雪" to "snow", "雪" to "snow", "snow" to "snow",
        "打雷" to "thunder", "雷" to "thunder", "thunder" to "thunder", "lightning" to "thunder",
        "地震" to "quake", "震动" to "quake", "quake" to "quake", "earthquake" to "quake",
        "狂风" to "wind", "风" to "wind", "wind" to "wind", "gale" to "wind",
        "停止" to "stop", "关闭" to "stop", "stop" to "stop", "clear" to "stop",
    )

    private const val DIRECTIVE_PADDING = " \t\r\n\u00a0\u200b\ufeff\u3000"
    private val BACKGROUND_CLEAR = setOf("<移除背景>", "<清除背景>", "<恢复背景>")

    /** 只接受无凭据 HTTPS（无用户名/密码、无控制字符、长度 ≤2048）；否则返回空串。 */
    fun safeHttpsUrl(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 2048 || raw.any { it.code < 32 }) return ""
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return ""
        if (uri.scheme?.lowercase() != "https") return ""
        if (uri.host.isNullOrEmpty()) return ""
        if (uri.userInfo != null) return ""
        return raw
    }

    /** 图片节点的背景标记属性（由 [prepareImmersiveFloor] 写入 attrs 后渲染时读出）。 */
    fun backgroundAttribute(attrs: JsonObject): String {
        val background = (attrs["gululuBackground"] as? JsonPrimitive)?.content.orEmpty()
        if (background.isEmpty() || background == "null") return ""
        return " data-gululu-background-url=\"${GululuAst.escape(background)}\""
    }

    /**
     * 识别完整的指令段落（不修改接口快照）：音乐/自动音乐/停止音乐、视效、
     * 背景区间与清除背景；其余节点原样保留，最后统一过一遍助手协议。
     */
    fun prepareImmersiveFloor(nodes: JsonElement?): ImmersiveFloor {
        val list = nodes as? JsonArray ?: return ImmersiveFloor(emptyList())
        val output = mutableListOf<JsonElement>()
        var vfx = ""
        var inBackground = false
        var backgroundUpdate: String? = null

        for (source in list.filterIsInstance<JsonObject>()) {
            val nodeType = (source["type"] as? JsonPrimitive)?.content.orEmpty()
            val text = if (nodeType == "paragraph") {
                GululuAssistant.nodeText(source).trim { it in DIRECTIVE_PADDING }
            } else {
                ""
            }

            val auto = AUTO_MUSIC.matchEntire(text)
            val music = auto ?: MANUAL_MUSIC.matchEntire(text)
            if (music != null) {
                val title = music.groupValues[1].trim().ifEmpty { "BGM" }
                val url = safeHttpsUrl(music.groupValues[2])
                if (url.isEmpty()) {
                    output.add(GululuAssistant.directiveError("音乐链接不可用：$title"))
                } else {
                    output.add(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("gululuMusic"),
                                "attrs" to buildJsonObject {
                                    put("title", title)
                                    put("url", url)
                                    put("auto", auto != null)
                                },
                                "content" to JsonArray(emptyList()),
                            ),
                        ),
                    )
                }
                continue
            }

            if (text == "<停止音乐>") {
                output.add(marker("gululuMusicStop"))
                continue
            }

            val effect = VFX.matchEntire(text)
            if (effect != null) {
                val requested = effect.groupValues[1].trim()
                val mapped = VFX_NAMES[requested.lowercase()].orEmpty()
                if (mapped.isNotEmpty()) {
                    if (vfx.isEmpty()) vfx = mapped
                } else {
                    output.add(
                        GululuAssistant.directiveError(
                            "暂不支持的特效：${requested.ifEmpty { "空" }}",
                        ),
                    )
                }
                continue
            }

            if (text == "<背景>") {
                inBackground = true
                continue
            }
            if (text == "</背景>" && inBackground) {
                inBackground = false
                continue
            }
            if (text in BACKGROUND_CLEAR) {
                output.add(marker("gululuBackgroundClear"))
                backgroundUpdate = ""
                continue
            }

            var node = source
            if (inBackground && nodeType == "image") {
                val attrs = source["attrs"] as? JsonObject ?: JsonObject(emptyMap())
                val backgroundUrl = safeHttpsUrl((attrs["src"] as? JsonPrimitive)?.content)
                if (backgroundUrl.isNotEmpty()) {
                    val patched = JsonObject(
                        attrs.toMutableMap().apply {
                            put("gululuBackground", JsonPrimitive(backgroundUrl))
                        },
                    )
                    node = JsonObject(source.toMutableMap().apply { put("attrs", patched) })
                    backgroundUpdate = backgroundUrl
                } else {
                    output.add(GululuAssistant.directiveError("背景图片链接不可用"))
                }
            }
            output.add(node)
        }

        if (inBackground) output.add(GululuAssistant.directiveError("背景指令缺少结束标记"))
        return ImmersiveFloor(
            nodes = GululuAssistant.prepareAssistantNodes(output),
            vfx = vfx,
            backgroundUpdate = backgroundUpdate,
        )
    }

    /** 渲染扩展（对齐 `render_immersive_node`）。 */
    fun renderer(): GululuNodeRenderer = GululuNodeRenderer { nodeType, attrs, _, _, _ ->
        val escape = GululuAst::escape
        when (nodeType) {
            "gululuMusic" -> {
                val title = (attrs["title"] as? JsonPrimitive)?.content?.ifEmpty { "BGM" } ?: "BGM"
                val url = escape((attrs["url"] as? JsonPrimitive)?.content.orEmpty())
                val isAuto = (attrs["auto"] as? JsonPrimitive)?.let {
                    it.content == "true" || it.content == "1"
                } ?: false
                val auto = if (isAuto) " data-gululu-music-auto=\"true\"" else ""
                val label = if (isAuto) "自动音乐" else "音乐"
                "<p class=\"gululu-music-row\">" +
                    "<button type=\"button\" class=\"gululu-music-cue\" " +
                    "data-gululu-music-url=\"$url\"$auto>" +
                    "<span class=\"gululu-music-kind\">$label</span>" +
                    "<span class=\"gululu-music-title\">${escape(title)}</span>" +
                    "</button></p>"
            }
            "gululuMusicStop" ->
                "<span class=\"gululu-music-stop\" data-gululu-music-stop=\"true\" " +
                    "role=\"button\" tabindex=\"0\" aria-label=\"停止音乐\">&#9632;</span>"
            "gululuBackgroundClear" ->
                "<span class=\"gululu-immersive-marker\" " +
                    "data-gululu-background-clear=\"true\" aria-hidden=\"true\"><wbr/></span>"
            "gululuDirectiveError" -> {
                val message = escape(
                    (attrs["message"] as? JsonPrimitive)?.content?.ifEmpty { "沉浸指令不可用" }
                        ?: "沉浸指令不可用",
                )
                "<p class=\"gululu-directive-error\">[$message]</p>"
            }
            else -> null
        }
    }

    private fun marker(type: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive(type),
            "attrs" to JsonObject(emptyMap()),
            "content" to JsonArray(emptyList()),
        ),
    )
}
