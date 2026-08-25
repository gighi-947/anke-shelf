package io.github.gighi947.ankeshelf.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 骨碌碌 EPUB 结构不符合支持范围（对齐桌面 GululuFormatError）。 */
class GululuEpubFormatException(message: String) : Exception(message)

/** 一章 = 标题 + 有序的 (楼层目录项, 楼层正文) 列表。 */
data class GululuChapterGroup(
    val title: String,
    val floors: List<Pair<JsonObject, JsonObject>>,
)

/** 内嵌图片资源（与 service/GululuImages 的结果对接，避免 data 层依赖 service 层）。 */
data class GululuEpubImage(
    val fileName: String,
    val mediaType: String,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is GululuEpubImage && fileName == other.fileName
    override fun hashCode(): Int = fileName.hashCode()
}

/**
 * 骨碌碌公开接口 → 标准 EPUB3（Kotlin 版 `app/gululu_epub.py`）。
 *
 * 结构与桌面（ebooklib 产物）保持同构，这样两端导入的书可以互相打开、
 * 也让热更新的"读旧 EPUB 楼层锚点"迁移路径在两端都成立：
 * - `EPUB/content.opf`（`dc:identifier = gululu-<bookId>`、`dc:source` 指向公开页）
 * - `EPUB/chapters/chapter_%04d.xhtml`，每层带 `id="floor-<floorId>"` 锚点
 * - `EPUB/style/main.css`、`EPUB/nav.xhtml`、`EPUB/toc.ncx`
 * - 内嵌图片 `EPUB/images/<sha 前16位>.<ext>`（章节内引用 `../images/...`）
 */
object GululuEpub {

    const val FALLBACK_FLOORS_PER_CHAPTER = 20
    const val SITE_BASE = "https://www.gululu.world"

    /**
     * 章节分组（纯函数）：作者标记过章节就按标记切，否则每 [FALLBACK_FLOORS_PER_CHAPTER]
     * 楼一章。缺正文的楼层显式失败——宁可报错，也不产出缺楼的书。
     */
    fun chapterGroups(
        floorIndex: List<JsonObject>,
        chapterIndex: List<JsonObject>,
        floors: List<JsonObject>,
    ): List<GululuChapterGroup> {
        val floorById = floors.mapNotNull { floor ->
            floor.intOrNull("id")?.let { it to floor }
        }.toMap()
        val markers = chapterIndex.mapNotNull { marker ->
            val floorNum = marker.intOrNull("floor") ?: return@mapNotNull null
            floorNum to marker.str("title").trim()
        }.toMap()

        val ordered = floorIndex.map { item ->
            val floor = floorById[item.intOrNull("floorId")]
                ?: throw GululuEpubFormatException(
                    "缺少第 ${item["floorNum"]?.let { (it as? JsonPrimitive)?.content } ?: "?"} 楼正文",
                )
            item to floor
        }
        if (ordered.isEmpty()) throw GululuEpubFormatException("骨碌碌书籍没有可导出的楼层")

        val hasAuthorChapters = ordered.any { (item, _) ->
            markers[item.intOrNull("floorNum")]?.isNotEmpty() == true
        }
        if (!hasAuthorChapters) {
            return ordered.chunked(FALLBACK_FLOORS_PER_CHAPTER).map { group ->
                val first = group.first().first.intOrNull("floorNum")
                val last = group.last().first.intOrNull("floorNum")
                val title = if (first == last) "第 $first 楼" else "第 $first~$last 楼"
                GululuChapterGroup(title, group)
            }
        }

        val groups = mutableListOf<GululuChapterGroup>()
        var current = mutableListOf<Pair<JsonObject, JsonObject>>()
        var currentTitle = ""
        for ((item, floor) in ordered) {
            val floorNum = item.intOrNull("floorNum")
            val markerTitle = markers[floorNum]
            if (!markerTitle.isNullOrEmpty() && current.isNotEmpty()) {
                groups.add(GululuChapterGroup(currentTitle, current))
                current = mutableListOf()
            }
            if (current.isEmpty()) {
                currentTitle = markerTitle?.takeIf { it.isNotEmpty() }
                    ?: item.str("name").ifEmpty { "第 $floorNum 楼" }
            }
            current.add(item to floor)
        }
        if (current.isNotEmpty()) groups.add(GululuChapterGroup(currentTitle, current))
        return groups
    }

    /** 楼层 → XHTML（纯函数，与桌面 `_floor_html` 逐字符一致）。 */
    fun floorHtml(
        indexItem: JsonObject,
        floor: JsonObject,
        comments: List<JsonObject>,
        immersive: ImmersiveFloor,
        imageResolver: (String) -> String,
        jumpFloorResolver: ((Int) -> String)?,
        sourceBookId: Int,
    ): String {
        val floorNum = indexItem.intOrNull("floorNum") ?: floor.intOrNull("floorNum") ?: 0
        val floorId = indexItem.intOrNull("floorId") ?: floor.intOrNull("id") ?: 0
        val title = GululuAst.escape(
            indexItem.str("name").ifEmpty { floor.str("name") },
        )
        val body = GululuAst.render(
            nodes = GululuAssistant.prepareReaderExperienceNodes(immersive.nodes, floorId),
            imageResolver = imageResolver,
            jumpFloorResolver = jumpFloorResolver,
            sourceBookId = sourceBookId,
            extensions = listOf(GululuAssistant.renderer(), GululuImmersive.renderer()),
            imageBackgroundAttr = { attrs -> GululuImmersive.backgroundAttribute(attrs) },
        )
        val effectAttr = if (immersive.vfx.isNotEmpty()) {
            " data-gululu-vfx=\"${GululuAst.escape(immersive.vfx)}\""
        } else {
            ""
        }
        val commentHtml = GululuComments.renderCommentBlock(comments, label = "评论")
        return "<section class=\"gululu-floor\" id=\"floor-$floorId\"$effectAttr>" +
            "<header class=\"floor-head\">" +
            "<span class=\"floor-number\">第 $floorNum 楼</span>" +
            "<span class=\"floor-title\">$title</span>" +
            "</header>" +
            "<div class=\"floor-content\">$body</div>" +
            commentHtml +
            "</section>"
    }

    /** 章节 XHTML 全文（含首章的来源行与作品评论）。 */
    fun chapterXhtml(title: String, bodyParts: List<String>): String {
        val escaped = GululuAst.escape(title)
        return "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"zh-CN\">" +
            "<head><title>$escaped</title>" +
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"../style/main.css\"/></head>" +
            "<body>${bodyParts.joinToString("")}</body></html>"
    }

    /**
     * 生成完整 EPUB 字节。
     *
     * @param floorTargets 楼号 → `chapter_NNNN.xhtml#floor-<id>`（同书引用锚点）
     * @param commentsByFloor key 0 为作品评论（写入首章）
     */
    fun build(
        detail: JsonObject,
        floorIndex: List<JsonObject>,
        chapterIndex: List<JsonObject>,
        floors: List<JsonObject>,
        imageResolver: (String) -> String,
        images: List<GululuEpubImage> = emptyList(),
        cover: GululuEpubImage? = null,
        commentsByFloor: Map<Int, List<JsonObject>> = emptyMap(),
        onChapter: ((current: Int, total: Int) -> Unit)? = null,
        cancelled: (() -> Boolean)? = null,
    ): ByteArray {
        val bookId = detail.intOrNull("bookId")
            ?: throw GululuEpubFormatException("骨碌碌书籍详情缺少 bookId 或 name")
        val title = detail.str("name").trim()
        if (title.isEmpty()) throw GululuEpubFormatException("骨碌碌书名为空")
        val author = (detail["author"] as? JsonObject)?.str("nickName")?.trim().orEmpty()
        val description = detail.str("oneLineText").trim()

        val groups = chapterGroups(floorIndex, chapterIndex, floors)
        val floorTargets = mutableMapOf<Int, String>()
        groups.forEachIndexed { index, group ->
            val chapterName = "chapter_%04d.xhtml".format(index + 1)
            for ((item, floor) in group.floors) {
                val floorNumber = item.intOrNull("floorNum") ?: floor.intOrNull("floorNum") ?: 0
                val floorId = item.intOrNull("floorId") ?: floor.intOrNull("id") ?: 0
                if (floorNumber > 0 && floorId > 0) {
                    floorTargets[floorNumber] = "$chapterName#floor-$floorId"
                }
            }
        }
        val immersiveByFloor = floors.mapNotNull { floor ->
            floor.intOrNull("id")?.let { it to GululuImmersive.prepareImmersiveFloor(floor["paragraphContents"]) }
        }.toMap()

        val chapterFiles = mutableListOf<Pair<String, String>>() // fileName to title
        val chapterBodies = mutableListOf<String>()
        var activeBackground = ""
        groups.forEachIndexed { index, group ->
            if (cancelled?.invoke() == true) throw GululuCancelledBuild()
            val parts = mutableListOf<String>()
            parts.add("<h1 class=\"chapter-title\">${GululuAst.escape(group.title)}</h1>")
            if (index == 0) {
                val source = GululuAst.escape("$SITE_BASE/book/$bookId")
                parts.add(
                    "<p class=\"book-meta\">来源：<a href=\"$source\">骨碌碌</a>" +
                        " · 作者：${GululuAst.escape(author.ifEmpty { "未知" })}</p>",
                )
                parts.add(
                    GululuComments.renderCommentBlock(
                        commentsByFloor[0] ?: emptyList(),
                        label = "作品评论",
                        opus = true,
                    ),
                )
            }
            if (activeBackground.isNotEmpty()) {
                parts.add(
                    "<span class=\"gululu-immersive-marker\" " +
                        "data-gululu-background-initial=\"${GululuAst.escape(activeBackground)}\" " +
                        "aria-hidden=\"true\"><wbr/></span>",
                )
            }
            for ((item, floor) in group.floors) {
                val floorId = item.intOrNull("floorId") ?: 0
                val immersive = immersiveByFloor[floorId] ?: ImmersiveFloor(emptyList())
                parts.add(
                    floorHtml(
                        indexItem = item,
                        floor = floor,
                        comments = commentsByFloor[floorId] ?: emptyList(),
                        immersive = immersive,
                        imageResolver = imageResolver,
                        jumpFloorResolver = { floorNumber -> floorTargets[floorNumber].orEmpty() },
                        sourceBookId = bookId,
                    ),
                )
                immersive.backgroundUpdate?.let { activeBackground = it }
            }
            val fileName = "chapter_%04d.xhtml".format(index + 1)
            chapterFiles.add(fileName to group.title)
            chapterBodies.add(chapterXhtml(group.title, parts))
            onChapter?.invoke(index + 1, groups.size)
        }

        return writeZip(
            bookId = bookId,
            title = title,
            author = author,
            description = description,
            chapterFiles = chapterFiles,
            chapterBodies = chapterBodies,
            images = images,
            cover = cover,
        )
    }

    private fun writeZip(
        bookId: Int,
        title: String,
        author: String,
        description: String,
        chapterFiles: List<Pair<String, String>>,
        chapterBodies: List<String>,
        images: List<GululuEpubImage>,
        cover: GululuEpubImage?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // mimetype 必须是第一个条目且 STORED（EPUB 规范）
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()

            zip.write("META-INF/container.xml", CONTAINER_XML)
            zip.write(
                "EPUB/content.opf",
                opfXml(bookId, title, author, description, chapterFiles, images, cover),
            )
            zip.write("EPUB/style/main.css", GULULU_EPUB_CSS)
            zip.write("EPUB/nav.xhtml", navXhtml(title, chapterFiles))
            zip.write("EPUB/toc.ncx", ncxXml(bookId, title, chapterFiles))
            chapterFiles.forEachIndexed { index, (fileName, _) ->
                zip.write("EPUB/chapters/$fileName", chapterBodies[index])
            }
            for (image in images) {
                zip.putNextEntry(ZipEntry("EPUB/${image.fileName}"))
                zip.write(image.content)
                zip.closeEntry()
            }
            cover?.let {
                zip.putNextEntry(ZipEntry("EPUB/${coverName(it)}"))
                zip.write(it.content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.write(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun coverName(cover: GululuEpubImage): String =
        "cover." + cover.fileName.substringAfterLast('.', "jpg")

    private fun opfXml(
        bookId: Int,
        title: String,
        author: String,
        description: String,
        chapterFiles: List<Pair<String, String>>,
        images: List<GululuEpubImage>,
        cover: GululuEpubImage?,
    ): String {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
        manifest.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
        manifest.append("<item id=\"style-main\" href=\"style/main.css\" media-type=\"text/css\"/>")
        chapterFiles.forEachIndexed { index, (fileName, _) ->
            val id = "chapter_%04d".format(index + 1)
            manifest.append(
                "<item id=\"$id\" href=\"chapters/$fileName\" media-type=\"application/xhtml+xml\"/>",
            )
            spine.append("<itemref idref=\"$id\"/>")
        }
        for (image in images) {
            val id = "gululu-image-" + image.fileName.substringAfterLast('/').substringBeforeLast('.')
            manifest.append(
                "<item id=\"${GululuAst.escape(id)}\" href=\"${GululuAst.escape(image.fileName)}\" " +
                    "media-type=\"${image.mediaType}\"/>",
            )
        }
        cover?.let {
            manifest.append(
                "<item id=\"cover-image\" href=\"${coverName(it)}\" media-type=\"${it.mediaType}\" " +
                    "properties=\"cover-image\"/>",
            )
        }
        val creator = if (author.isNotEmpty()) {
            "<dc:creator>${GululuAst.escape(author)}</dc:creator>"
        } else {
            ""
        }
        val desc = if (description.isNotEmpty()) {
            "<dc:description>${GululuAst.escape(description)}</dc:description>"
        } else {
            ""
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:identifier id="bookid">gululu-$bookId</dc:identifier>
    <dc:title>${GululuAst.escape(title)}</dc:title>
    <dc:language>zh-CN</dc:language>
    $creator
    <dc:source>$SITE_BASE/book/$bookId</dc:source>
    $desc
  </metadata>
  <manifest>$manifest</manifest>
  <spine toc="ncx">$spine</spine>
</package>
"""
    }

    private fun navXhtml(title: String, chapterFiles: List<Pair<String, String>>): String {
        val items = chapterFiles.joinToString("") { (fileName, chapterTitle) ->
            "<li><a href=\"chapters/$fileName\">${GululuAst.escape(chapterTitle)}</a></li>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh-CN">
<head><title>${GululuAst.escape(title)}</title></head>
<body><nav epub:type="toc" id="toc"><h1>目录</h1><ol>$items</ol></nav></body>
</html>
"""
    }

    private fun ncxXml(bookId: Int, title: String, chapterFiles: List<Pair<String, String>>): String {
        val points = chapterFiles.mapIndexed { index, (fileName, chapterTitle) ->
            "<navPoint id=\"navPoint-${index + 1}\" playOrder=\"${index + 1}\">" +
                "<navLabel><text>${GululuAst.escape(chapterTitle)}</text></navLabel>" +
                "<content src=\"chapters/$fileName\"/></navPoint>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="gululu-$bookId"/></head>
<docTitle><text>${GululuAst.escape(title)}</text></docTitle>
<navMap>$points</navMap>
</ncx>
"""
    }

    private fun JsonObject.str(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return if (primitive.isString) primitive.content else primitive.content.takeUnless { it == "null" }.orEmpty()
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content.toIntOrNull() else it.intOrNull }

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="EPUB/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

    /** 与桌面 `app/gululu_epub_styles.py` 完全同一份样式（两端观感一致）。 */
    val GULULU_EPUB_CSS = """body { line-height:1.8; margin:0 1em; }
.book-meta { color:#777; font-size:.9em; margin:0 0 1.2em; }
.chapter-title { font-size:1.5em; margin:.5em 0 1em; }
.gululu-floor { border:1px solid #e0e0e0; border-left:4px solid #6f8d87;
  box-sizing:border-box; padding:12px 14px; margin:14px 0; border-radius:2px; }
.floor-head { align-items:baseline; border-bottom:1px dotted #e0e0e0; color:#888;
  display:flex; font-size:.82em; gap:.55em; padding-bottom:6px; margin-bottom:8px;
  break-after:avoid; }
.floor-number { color:#6f8d87; font-weight:700; }
.floor-title { flex:1; min-width:0; overflow-wrap:anywhere; }
.floor-content p { margin:.65em 0; overflow-wrap:anywhere; }
.empty-paragraph { min-height:.6em; margin:.15em 0 !important; }
.gululu-image { margin:.8em 0; text-align:center; break-inside:avoid; }
.gululu-image img { max-width:100%; height:auto; object-fit:contain; }
.avatar-image img { width:4.8em; height:4.8em; object-fit:cover; border-radius:10px; }
.gululu-music-row { break-inside:avoid; }
.gululu-music-cue { display:inline-flex; align-items:center; gap:.55em; max-width:100%;
  border:1px solid #aaa; border-radius:6px; background:transparent; color:inherit;
  padding:.45em .65em; cursor:pointer; font:inherit; text-align:left; }
.gululu-music-kind { color:#777; font-size:.78em; white-space:nowrap; }
.gululu-music-title { overflow-wrap:anywhere; }
.gululu-music-cue.playing { border-color:currentColor; }
.gululu-music-stop { display:inline-block; cursor:pointer; font-size:.75em; padding:.25em; }
.gululu-immersive-marker { display:block; height:0; overflow:hidden; }
.gululu-directive-error { border:1px dashed #aaa; color:#777; padding:.5em; }
.gululu-fold { border-left:3px solid #aaa; margin:.8em 0; padding:.2em 0 .2em .8em; }
.gululu-fold summary { cursor:pointer; font-weight:700; break-after:avoid; }
.gululu-dice-value, .gululu-dice-suffix { border-radius:3px; cursor:pointer; }
.gululu-dice-value { padding:0 .12em; }
.gululu-dice-value:focus-visible { outline:2px solid currentColor; outline-offset:2px; }
.gululu-dice-value.masked, .gululu-dice-suffix.masked {
  background:rgba(127,127,127,.35); color:transparent;
  -webkit-text-fill-color:transparent; text-shadow:none; user-select:none;
}
.gululu-dice-value.revealed, .gululu-dice-suffix.revealed { animation:g-dice-reveal .28s ease-out; }
.gululu-fog-block.gululu-fog-hidden { display:none; }
@keyframes g-dice-reveal { from { opacity:.2; } to { opacity:1; } }
.gululu-secret-cue, .gululu-clue-cue { border:1px solid #aaa; border-radius:6px;
  background:transparent; color:inherit; cursor:pointer; font:inherit; margin:.25em 0;
  max-width:100%; overflow-wrap:anywhere; padding:.45em .65em; }
.gululu-clue-cue { border-style:dashed; }
.gululu-jump-floor { display:inline-block; margin:.35em 0; }
.gululu-assistant-quote { display:block; border-left:3px solid #8aa09a; color:inherit;
  margin:.8em 0; padding:.45em .8em; text-decoration:none; }
.gululu-assistant-quote:hover { background:rgba(127,127,127,.08); }
.gululu-sensitive-unavailable { border:1px dashed #aaa; color:#777; padding:.5em; }
.gululu-comments { border-top:1px solid #bbb; margin:1em 0 0; padding-top:.5em; }
.gululu-comments > summary { cursor:pointer; font-weight:700; }
.gululu-comment-list { margin:.6em 0 0; }
.gululu-comment { border-left:2px solid #bbb; margin:.65em 0; padding:.15em 0 .15em .75em; }
.gululu-comment-head { align-items:baseline; display:flex; flex-wrap:wrap; gap:.5em; }
.gululu-comment-head > span { color:#777; font-size:.78em; }
.gululu-comment-text { margin:.25em 0; overflow-wrap:anywhere; }
.gululu-comment-replies { margin:.4em 0 0 .65em; }
.gululu-comment-reply { font-size:.92em; }
.comment-reply-user { color:#777; }
.unsupported-node, .image-omitted, .image-unavailable {
  border:1px dashed #aaa; color:#777; padding:.5em;
}
del { opacity:.72; }
"""
}

/** EPUB 构建被任务取消。 */
class GululuCancelledBuild : Exception("骨碌碌导入已取消")
