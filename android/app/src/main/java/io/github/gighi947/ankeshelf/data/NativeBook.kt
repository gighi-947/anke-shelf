package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val NATIVE_FORMAT = "ank-native/1"
const val NATIVE_META_NAME = "meta.json"
const val NATIVE_FLOORS_NAME = "floors.json"
const val NATIVE_CHAPTERS_DIR = "chapters"

/** 楼层原始数据（与桌面 native_book.serialize_floor 字段完全一致）。 */
@Serializable
data class NativeFloor(
    val pid: Long,
    val lou: Int = -1,
    val timestamp: Long = 0,
    val username: String = "",
    val user_id: Long = 0,
    val like_num: Int = 0,
    val raw_content: String = "",
    val comments: List<NativeFloor> = emptyList(),
)

/** 目录条目：桌面序列化为 JSON 数组 [标题, pid]，自定义序列化保持同构。 */
@Serializable(with = NativeTocEntry.Serializer::class)
data class NativeTocEntry(val title: String, val pid: Long) {
    @OptIn(ExperimentalSerializationApi::class)
    object Serializer : KSerializer<NativeTocEntry> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NativeTocEntry") {
                element<String>("title")
                element<Long>("pid")
            }

        override fun serialize(encoder: Encoder, value: NativeTocEntry) {
            val json = encoder as? JsonEncoder
                ?: error("NativeTocEntry 只能用于 JSON 序列化")
            json.encodeJsonElement(
                JsonArray(listOf(JsonPrimitive(value.title), JsonPrimitive(value.pid))),
            )
        }

        override fun deserialize(decoder: Decoder): NativeTocEntry {
            val json = decoder as? JsonDecoder
                ?: error("NativeTocEntry 只能用于 JSON 反序列化")
            return when (val el = json.decodeJsonElement()) {
                is JsonArray -> {
                    val title = (el.getOrNull(0) as? JsonPrimitive)?.content ?: ""
                    val pid = (el.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: 0L
                    NativeTocEntry(title, pid)
                }
                is JsonObject -> NativeTocEntry(
                    title = (el["title"] as? JsonPrimitive)?.content ?: "",
                    pid = (el["pid"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
                else -> NativeTocEntry("", 0L)
            }
        }
    }
}

@Serializable
data class NativeTocChapter(
    val title: String = "",
    val entries: List<NativeTocEntry> = emptyList(),
)

@Serializable
data class NativeChapterMeta(
    val file: String,
    val title: String,
    val floor_count: Int,
    val first_lou: Int,
    val last_lou: Int,
    val main: Boolean = false,
)

/** 原生书 meta.json（与桌面 write_container 输出字段一致）。 */
@Serializable
data class NativeMeta(
    val format: String = NATIVE_FORMAT,
    val book_id: String = "",
    val tid: Long = 0,
    val author_id: Long = 0,
    val title: String = "",
    val author: String = "",
    val folder_name: String = "",
    val per_chapter: Int = 20,
    val image_mode: String = "online",
    val theme: String = "light",
    val toc_mode: String = "index",
    val toc: List<NativeTocChapter> = emptyList(),
    val chapters: List<NativeChapterMeta> = emptyList(),
    val last_lou: Int = 0,
    val created_time: String = "",
    val updated_time: String = "",
)

private val NATIVE_META_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val NATIVE_FLOORS_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = " "
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** 一本原生目录书（meta.json + floors.json + chapters 目录下的 XHTML 章节文件）。 */
class NativeBook(val path: File) : Closeable {

    var id: String = md5Hex(path.absolutePath.toByteArray(Charsets.UTF_8))
    var title: String = ""
    var author: String = ""
    var language: String = "zh"
    val chapters: MutableList<SpineItem> = mutableListOf()
    val toc: MutableList<TocEntry> = mutableListOf()
    val tocMap: MutableMap<String, String> = mutableMapOf()

    private var root: File? = null
    private var meta: NativeMeta? = null

    fun open(): NativeBook {
        val root = path.absoluteFile
        val metaFile = File(root, NATIVE_META_NAME)
        if (!metaFile.isFile) throw EpubError("不是有效的原生书（缺少 meta.json）")
        val m = try {
            NATIVE_META_JSON.decodeFromString<NativeMeta>(metaFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw EpubError("原生书元数据损坏：${e.message}", e)
        }
        this.root = root
        this.meta = m
        id = m.book_id.ifEmpty { id }
        title = m.title
        author = m.author
        chapters.clear()
        m.chapters.forEachIndexed { i, c ->
            chapters.add(
                SpineItem(
                    index = i,
                    idref = "ch$i",
                    href = c.file,
                    linear = true,
                    mediaType = "application/xhtml+xml",
                ),
            )
        }
        toc.clear()
        m.chapters.forEachIndexed { i, c ->
            toc.add(
                TocEntry(
                    label = c.title.ifEmpty { "第 ${i + 1} 章" },
                    href = c.file,
                    spineIndex = i,
                ),
            )
        }
        tocMap.clear()
        m.chapters.forEach { c -> tocMap[c.file] = c.title }
        return this
    }

    override fun close() = Unit

    fun readFile(name: String): ByteArray? {
        val root = root ?: return null
        val safe = safeRel(name) ?: return null
        val p = File(root, safe).absoluteFile
        return try {
            if (!p.path.startsWith(root.absolutePath + File.separator)) return null
            if (!p.isFile) null else p.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    fun chapterText(index: Int): String? {
        if (index !in chapters.indices) return null
        val data = readFile(chapters[index].href) ?: return null
        return decodeText(data)
    }

    fun chapterTitle(index: Int): String {
        if (index !in chapters.indices) return ""
        return tocMap[chapters[index].href] ?: "第 ${index + 1} 章"
    }

    fun tocSpineIndex(href: String): Int? {
        val h = href.substringBefore('#')
        chapters.forEachIndexed { i, c ->
            if (c.href == h) return i
        }
        return null
    }

    fun getCoverBytes(): ByteArray? = null

    fun meta(): NativeMeta? = meta
}

/** 原生书容器写侧：首建 + 纯增量热更新（只追加，不重写旧章节）。 */
object NativeBookWriter {

    fun nativeDirFor(ngaLibraryRoot: File, folderName: String): File =
        File(ngaLibraryRoot, "$folderName/book")

    fun isNativeDir(path: File): Boolean =
        path.isDirectory && File(path, NATIVE_META_NAME).isFile

    fun writeContainer(
        ngaLibraryRoot: File,
        folderName: String,
        tieziTitle: String,
        author: String,
        tid: Long,
        authorId: Long,
        createdTime: String,
        updatedTime: String,
        validFloors: List<NativeFloor>,
        perChapter: Int,
        imageMode: String,
        theme: String,
        bookId: String,
        tocChapters: List<NativeTocChapter>? = null,
        tocMode: String = "index",
    ): File {
        val nativeDir = nativeDirFor(ngaLibraryRoot, folderName)
        val chaptersDir = File(nativeDir, NATIVE_CHAPTERS_DIR)
        chaptersDir.mkdirs()
        val colors = themeColors(theme)

        val grouped: List<Pair<String, List<NativeFloor>>> = if (tocMode == "split" && !tocChapters.isNullOrEmpty()) {
            groupFloorsByToc(validFloors, tocChapters)
        } else {
            groupFloors(validFloors, perChapter).map { groupTitle(it) to it }
        }

        val chapters = mutableListOf<NativeChapterMeta>()
        grouped.forEachIndexed { gi, (title, group) ->
            val body = buildString {
                if (group[0].pid == 0L) append("<h1>${escapeHtml(tieziTitle)}</h1>")
                group.forEach { append(renderFloorHtml(it, colors, theme == "dark")) }
            }
            val fileName = "%04d.xhtml".format(gi)
            File(chaptersDir, fileName).writeText(chapterHtml(title, body, theme), Charsets.UTF_8)
            chapters.add(
                NativeChapterMeta(
                    file = "$NATIVE_CHAPTERS_DIR/$fileName",
                    title = title,
                    floor_count = group.size,
                    first_lou = group.first().lou,
                    last_lou = group.last().lou,
                    main = group.first().pid == 0L,
                ),
            )
        }

        val meta = NativeMeta(
            format = NATIVE_FORMAT,
            book_id = bookId,
            tid = tid,
            author_id = authorId,
            title = tieziTitle,
            author = author,
            folder_name = folderName,
            per_chapter = maxOf(1, perChapter),
            image_mode = imageMode,
            theme = theme,
            toc_mode = tocMode,
            toc = tocChapters ?: emptyList(),
            chapters = chapters,
            last_lou = validFloors.lastOrNull()?.lou ?: 0,
            created_time = createdTime,
            updated_time = updatedTime,
        )
        saveMeta(nativeDir, meta)
        saveFloors(nativeDir, validFloors)
        return nativeDir
    }

    /** 把新楼层追加进已有原生书，返回实际追加数（按 pid 去重）。 */
    fun appendContainer(
        ngaLibraryRoot: File,
        folderName: String,
        newFloors: List<NativeFloor>,
        perChapter: Int,
        imageMode: String,
        theme: String,
    ): Int {
        val nativeDir = nativeDirFor(ngaLibraryRoot, folderName)
        val meta = loadMeta(nativeDir)
        val floors = loadFloors(nativeDir)
        val existingPids = floors.mapTo(HashSet()) { it.pid }
        val fresh = newFloors.filter { it.pid !in existingPids }
        if (fresh.isEmpty()) return 0

        val chaptersDir = File(nativeDir, NATIVE_CHAPTERS_DIR)
        val colors = themeColors(theme)
        val chapters = meta.chapters.toMutableList()
        val pending = fresh.toMutableList()
        val chunkSize = maxOf(1, perChapter)

        // 优先填满最后一个普通章节（主楼章节不追加）
        if (chapters.isNotEmpty()) {
            val last = chapters.last()
            if (!last.main && last.floor_count < chunkSize) {
                val room = chunkSize - last.floor_count
                val take = pending.take(room)
                pending.removeAll(take)
                if (take.isNotEmpty()) {
                    val html = take.joinToString("") { renderFloorHtml(it, colors, theme == "dark") }
                    val chapterFile = File(nativeDir, last.file)
                    val text = chapterFile.readText(Charsets.UTF_8)
                    chapterFile.writeText(text.replace("</body>", html + "</body>"), Charsets.UTF_8)
                    val updated = last.copy(
                        floor_count = last.floor_count + take.size,
                        last_lou = take.last().lou,
                        title = if (take.last().lou != last.first_lou) {
                            "第 ${last.first_lou}~${take.last().lou} 楼"
                        } else {
                            last.title
                        },
                    )
                    chapters[chapters.size - 1] = updated
                }
            }
        }

        // 其余按每章 per_chapter 开新章节
        var nextIndex = chapters.size
        var i = 0
        while (i < pending.size) {
            val group = pending.subList(i, minOf(i + chunkSize, pending.size))
            val title = groupTitle(group)
            val body = group.joinToString("") { renderFloorHtml(it, colors, theme == "dark") }
            val fileName = "%04d.xhtml".format(nextIndex)
            File(chaptersDir, fileName).writeText(chapterHtml(title, body, theme), Charsets.UTF_8)
            chapters.add(
                NativeChapterMeta(
                    file = "$NATIVE_CHAPTERS_DIR/$fileName",
                    title = title,
                    floor_count = group.size,
                    first_lou = group.first().lou,
                    last_lou = group.last().lou,
                    main = false,
                ),
            )
            nextIndex++
            i += chunkSize
        }

        val allFloors = floors + fresh
        val updatedMeta = meta.copy(
            chapters = chapters,
            last_lou = maxOf(meta.last_lou, fresh.maxOf { it.lou }),
            updated_time = nowIso(),
            theme = theme,
            image_mode = imageMode,
            per_chapter = chunkSize,
        )
        saveFloors(nativeDir, allFloors)
        saveMeta(nativeDir, updatedMeta)
        return fresh.size
    }

    fun loadMeta(nativeDir: File): NativeMeta {
        val file = File(nativeDir, NATIVE_META_NAME)
        return NATIVE_META_JSON.decodeFromString(file.readText(Charsets.UTF_8))
    }

    fun loadFloors(nativeDir: File): List<NativeFloor> {
        val file = File(nativeDir, NATIVE_FLOORS_NAME)
        if (!file.isFile) return emptyList()
        return NATIVE_FLOORS_JSON.decodeFromString(file.readText(Charsets.UTF_8))
    }

    fun saveMeta(nativeDir: File, meta: NativeMeta) {
        atomicWriteJson(
            File(nativeDir, NATIVE_META_NAME),
            NATIVE_META_JSON.encodeToString(NativeMeta.serializer(), meta),
        )
    }

    fun saveFloors(nativeDir: File, floors: List<NativeFloor>) {
        atomicWriteJson(
            File(nativeDir, NATIVE_FLOORS_NAME),
            NATIVE_FLOORS_JSON.encodeToString(ListSerializer(NativeFloor.serializer()), floors),
        )
    }

    /** 重命名书籍显示标题（书架与导出共用；不改 tid/章节/进度）。 */
    fun renameTitle(nativeDir: File, newTitle: String) {
        val meta = loadMeta(nativeDir)
        saveMeta(nativeDir, meta.copy(title = newTitle))
    }

    // ---------- 分组（与桌面 _group_floors/_group_floors_by_toc 对齐） ----------

    private fun groupFloors(valid: List<NativeFloor>, perChapter: Int): List<List<NativeFloor>> {
        val main = valid.firstOrNull { it.pid == 0L }
        val rest = if (main != null) valid.drop(1) else valid
        val groups = mutableListOf<List<NativeFloor>>()
        if (main != null) groups.add(listOf(main))
        val chunk = maxOf(1, perChapter)
        var i = 0
        while (i < rest.size) {
            groups.add(rest.subList(i, minOf(i + chunk, rest.size)))
            i += chunk
        }
        return groups.filter { it.isNotEmpty() }
    }

    private fun groupFloorsByToc(
        valid: List<NativeFloor>,
        tocChapters: List<NativeTocChapter>,
    ): List<Pair<String, List<NativeFloor>>> {
        val pidToFloor = valid.associateBy { it.pid }
        val marks = mutableListOf<Pair<Int, String>>()
        for (ch in tocChapters) {
            for (e in ch.entries) {
                val f = pidToFloor[e.pid]
                if (f != null) {
                    marks.add(f.lou to ch.title)
                    break
                }
            }
        }
        marks.sortBy { it.first }

        val main = valid.firstOrNull { it.pid == 0L }
        val rest = if (main != null) valid.drop(1) else valid
        val groups = mutableListOf<Pair<String, List<NativeFloor>>>()
        if (main != null) groups.add("序章 · 主楼" to listOf(main))
        if (rest.isEmpty()) return groups

        var idx = 0
        var current = mutableListOf<NativeFloor>()
        var curTitle = ""
        for (f in rest) {
            while (idx < marks.size && f.lou >= marks[idx].first) {
                if (current.isNotEmpty()) {
                    groups.add(curTitle to current)
                    current = mutableListOf()
                }
                curTitle = marks[idx].second
                idx++
            }
            current.add(f)
        }
        if (current.isNotEmpty()) groups.add(curTitle to current)
        return groups
    }

    private fun groupTitle(group: List<NativeFloor>): String {
        if (group[0].pid == 0L) return "序章 · 主楼"
        val first = group[0].lou
        val last = group.last().lou
        return if (first == last) "第 $first 楼" else "第 $first~$last 楼"
    }

    // ---------- HTML 渲染（安卓版精简渲染，结构语义与桌面一致） ----------

    private data class ThemeColors(val border: String, val accent: String, val muted: String, val commentBg: String)

    private fun themeColors(theme: String): ThemeColors =
        if (theme == "dark") {
            ThemeColors("#3a3a3a", "#5ba3d9", "#8a8a8a", "#262626")
        } else {
            ThemeColors("#e0e0e0", "#2e86ab", "#888888", "#fafafa")
        }

    private fun ts2t(ts: Long): String =
        TS_FORMAT.format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))

    private fun renderFloorHtml(f: NativeFloor, colors: ThemeColors, dark: Boolean): String {
        val floorStyle =
            "border:1px solid ${colors.border}; border-left:4px solid ${colors.accent}; " +
                "padding:12px 14px; margin:14px 0; border-radius:2px;"
        val headStyle =
            "color:${colors.muted}; font-size:.82em; border-bottom:1px dotted ${colors.border}; " +
                "padding-bottom:6px; margin-bottom:8px;"
        val commentStyle =
            "background:${colors.commentBg}; border:1px solid ${colors.border}; " +
                "padding:8px 10px; margin:6px 0 6px 14px; font-size:.92em;"
        val head =
            "<span class=\"lou\" style=\"color:${colors.accent}; font-weight:bold;\">${f.lou}楼</span> " +
                "· ${f.like_num}赞 · ${escapeHtml(f.username)}(${f.user_id}) · ${ts2t(f.timestamp)}" +
                "<span class=\"pid\"> · pid:${f.pid}</span>"
        val out = StringBuilder(
            "<div class=\"nga-floor\" id=\"pid${f.pid}\" style=\"$floorStyle\">" +
                "<div class=\"floor-head\" style=\"$headStyle\">$head</div>" +
                "<div class=\"floor-body\">${NgaFormatHtml.renderContentHtml(f.raw_content, dark = dark)}</div></div>",
        )
        for (c in f.comments) {
            if (c.lou <= 0) continue
            val cHead =
                "${c.lou}楼 · ${escapeHtml(c.username)}(${c.user_id}) · ${ts2t(c.timestamp)}"
            out.append(
                "<div class=\"nga-comment\" style=\"$commentStyle\">" +
                    "<span class=\"comment-head\" style=\"color:${colors.muted}; font-size:.8em; " +
                    "display:block; margin-bottom:4px;\">$cHead</span>${c.raw_content}</div>",
            )
        }
        return out.toString()
    }

    private fun chapterHtml(title: String, bodyHtml: String, theme: String): String {
        val css = if (theme == "dark") {
            "body{color:#d0d0d0;background:#202020;font-family:sans-serif;line-height:1.7}" +
                "h1{font-size:1.15em}.nga-floor{max-width:100%;overflow-wrap:break-word}" +
                "img{max-width:100%}"
        } else {
            "body{color:#222;background:#ffffff;font-family:sans-serif;line-height:1.7}" +
                "h1{font-size:1.15em}.nga-floor{max-width:100%;overflow-wrap:break-word}" +
                "img{max-width:100%}"
        }
        return "<html xmlns=\"http://www.w3.org/1999/xhtml\">" +
            "<head><meta charset=\"utf-8\"/><title>${escapeHtml(title)}</title>" +
            "<style>$css</style></head><body>$bodyHtml</body></html>"
    }
}

private val TS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** POSIX 相对路径归一化 + 目录穿越检查（与桌面 native_book._safe_rel 对齐）。 */
private fun safeRel(name: String): String? {
    if (name.isEmpty() || '\\' in name || name.startsWith("/")) return null
    val stack = ArrayDeque<String>()
    for (p in name.split("/")) {
        when (p) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeLast() else return null
            else -> stack.addLast(p)
        }
    }
    if (stack.isEmpty()) return null
    return stack.joinToString("/")
}

private fun escapeHtml(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#x27;")
            else -> append(c)
        }
    }
}

private fun md5Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
