package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 骨碌碌富文本 AST → 安全 XHTML（Kotlin 版 `app/gululu_ast.py`）。
 *
 * 与 Windows 的产物必须**逐字符一致**：两端生成同一种 EPUB，章节 XHTML 决定
 * `text_offset` 坐标，任何标签/空白差异都会让同一本书在两端的进度与标注错位。
 * 跨端 golden 对照见 `contracts/fixtures/gululu/ast-cases.json`。
 *
 * 扩展点 [GululuNodeRenderer] 对应桌面的 `render_assistant_node` /
 * `render_immersive_node`：核心渲染保持不变，助手与沉浸协议按注册顺序先行接管。
 */
fun interface GululuNodeRenderer {
    /**
     * 返回 null 表示本扩展不处理该节点类型（交给下一个扩展，最后落到核心渲染）。
     * 参数顺序与桌面 `render_assistant_node(node_type, attrs, render_children,
     * jump_floor_resolver, source_book_id)` 一致，便于双端逐行对照。
     */
    fun render(
        nodeType: String,
        attrs: JsonObject,
        renderChildren: () -> String,
        jumpFloorResolver: ((Int) -> String)?,
        sourceBookId: Int,
    ): String?
}

/** 不受支持的节点在 strict 模式下的显式失败（对齐桌面 GululuFormatError）。 */
class GululuFormatException(message: String) : Exception(message)

object GululuAst {

    private val HEX_COLOR = Regex("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?")
    private val RGB_COLOR = Regex(
        "rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)",
        RegexOption.IGNORE_CASE,
    )

    /** 与 Python `html.escape(s)`（quote=True）逐字符一致。 */
    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")

    /** 安全颜色：仅接受 #rgb/#rrggbb 与分量在 0..255 的 rgb()，其余丢弃。 */
    fun safeColor(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (HEX_COLOR.matchEntire(raw) != null) return raw
        val match = RGB_COLOR.matchEntire(raw) ?: return ""
        val parts = (1..3).map { match.groupValues[it].toIntOrNull() ?: return "" }
        if (parts.any { it < 0 || it > 255 }) return ""
        return "rgb(${parts[0]}, ${parts[1]}, ${parts[2]})"
    }

    /** 文本节点 marks：加粗/斜体/删除线/下划线/安全文字色，未知 mark 保留可见标记。 */
    fun renderMarks(text: String, marks: JsonElement?): String {
        var rendered = escape(text)
        val list = marks as? JsonArray ?: return rendered
        for (element in list) {
            val mark = element as? JsonObject ?: continue
            when (val type = mark.str("type")) {
                "" -> continue
                "bold" -> rendered = "<strong>$rendered</strong>"
                "italic" -> rendered = "<em>$rendered</em>"
                "strike" -> rendered = "<del>$rendered</del>"
                "underline" -> rendered = "<u>$rendered</u>"
                "textStyle" -> {
                    val color = safeColor(mark.obj("attrs")?.str("color"))
                    if (color.isNotEmpty()) {
                        rendered = "<span style=\"color:$color\">$rendered</span>"
                    }
                }
                else -> rendered =
                    "<span class=\"unsupported-mark\" data-mark=\"${escape(type)}\">$rendered</span>"
            }
        }
        return rendered
    }

    /**
     * 递归渲染已知的骨碌碌富文本 AST。
     *
     * @param imageResolver 图片三态：在线返回原 URL、内嵌返回 EPUB 内相对路径、不含图返回空串。
     * @param jumpFloorResolver 同书楼层锚点解析（跨书引用由扩展直接指向公开网页）。
     * @param extensions 助手/沉浸等协议扩展（批 5 接入）。
     * @param strict 遇到未知节点时抛 [GululuFormatException]（导入用宽容模式，测试用严格模式）。
     */
    fun render(
        nodes: List<JsonElement>,
        imageResolver: (String) -> String = { it },
        jumpFloorResolver: ((Int) -> String)? = null,
        sourceBookId: Int = 0,
        extensions: List<GululuNodeRenderer> = emptyList(),
        strict: Boolean = false,
        imageBackgroundAttr: (JsonObject) -> String = { "" },
    ): String {
        fun renderNode(node: JsonObject): String {
            val type = node.str("type")
            val attrs = node.obj("attrs") ?: JsonObject(emptyMap())
            val renderChildren = { renderChildren(node, imageResolver, jumpFloorResolver, sourceBookId, extensions, strict, imageBackgroundAttr) }

            for (extension in extensions) {
                val html = extension.render(type, attrs, renderChildren, jumpFloorResolver, sourceBookId)
                if (html != null) return html
            }

            return when (type) {
                "text" -> renderMarks(node.str("text"), node["marks"])
                "hardBreak" -> "<br/>"
                "paragraph" -> {
                    val content = renderChildren()
                    val paragraphId = attrs["id"]
                    val attr = if (paragraphId == null || paragraphId.contentOrEmpty().isEmpty()) {
                        ""
                    } else {
                        " data-paragraph-id=\"${escape(paragraphId.contentOrEmpty())}\""
                    }
                    if (content.isNotEmpty()) {
                        "<p$attr>$content</p>"
                    } else {
                        "<p class=\"empty-paragraph\"$attr>&#160;</p>"
                    }
                }
                "heading" -> {
                    val sourceLevel = attrs["level"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 2
                    val level = minOf(6, maxOf(3, sourceLevel + 1))
                    "<h$level>${renderChildren()}</h$level>"
                }
                "image" -> renderImage(attrs, imageResolver, imageBackgroundAttr)
                "collapsibleBlock" ->
                    // 默认折叠（浏览器 <details> 未带 open 即收起），对齐站点行为
                    "<details class=\"gululu-fold\"><summary>折叠内容</summary>${renderChildren()}</details>"
                else -> {
                    if (strict) {
                        throw GululuFormatException(
                            "暂不支持的骨碌碌正文节点：${type.ifEmpty { "unknown" }}",
                        )
                    }
                    "<div class=\"unsupported-node\">[暂不支持的内容：${escape(type.ifEmpty { "unknown" })}]</div>"
                }
            }
        }

        return nodes.filterIsInstance<JsonObject>().joinToString("") { renderNode(it) }
    }

    private fun renderChildren(
        node: JsonObject,
        imageResolver: (String) -> String,
        jumpFloorResolver: ((Int) -> String)?,
        sourceBookId: Int,
        extensions: List<GululuNodeRenderer>,
        strict: Boolean,
        imageBackgroundAttr: (JsonObject) -> String,
    ): String {
        val content = node["content"] as? JsonArray ?: return ""
        return render(
            nodes = content.toList(),
            imageResolver = imageResolver,
            jumpFloorResolver = jumpFloorResolver,
            sourceBookId = sourceBookId,
            extensions = extensions,
            strict = strict,
            imageBackgroundAttr = imageBackgroundAttr,
        )
    }

    private fun renderImage(
        attrs: JsonObject,
        imageResolver: (String) -> String,
        imageBackgroundAttr: (JsonObject) -> String,
    ): String {
        val source = attrs.str("src").trim()
        if (!source.startsWith("https://")) {
            return "<p class=\"image-unavailable\">[图片地址不可用]</p>"
        }
        val resolved = imageResolver(source)
        if (resolved.isEmpty()) return "<p class=\"image-omitted\">[图片已省略]</p>"
        val alt = escape(attrs.str("alt").ifEmpty { "图片" })
        var image =
            "<img src=\"${escape(resolved)}\" alt=\"$alt\" loading=\"lazy\" decoding=\"async\"/>"
        if (attrs.str("avatar").lowercase() == "true") {
            image = "<span class=\"avatar-image\">$image</span>"
        }
        return "<figure class=\"gululu-image\"${imageBackgroundAttr(attrs)}>$image</figure>"
    }

    // ---- JSON 小工具（attrs 直接来自公开接口，字段缺失一律当空处理） ----

    internal fun JsonObject.str(key: String): String = this[key].contentOrEmpty()

    internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    internal fun JsonObject.int(key: String, fallback: Int = 0): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: fallback

    internal fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonElement?.contentOrEmpty(): String {
        val primitive = this as? JsonPrimitive ?: return ""
        return if (primitive is JsonPrimitive && primitive.isString) {
            primitive.content
        } else {
            primitive.content.takeUnless { it == "null" }.orEmpty()
        }
    }
}
