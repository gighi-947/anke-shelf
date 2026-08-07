package io.github.gighi947.ankeshelf.data

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** EPUB 解析异常（message 为面向用户的中文说明）。 */
class EpubError(message: String, cause: Throwable? = null) : Exception(message, cause)

object EpubNamespaces {
    const val CNT = "urn:oasis:names:tc:opendocument:xmlns:container"
    const val OPF = "http://www.idpf.org/2007/opf"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val EPUB = "http://www.idpf.org/2007/ops"
    const val NCX = "http://www.daisy.org/z3986/2005/ncx/"
    const val XHTML = "http://www.w3.org/1999/xhtml"
    const val MIMETYPE_OPF = "application/oebps-package+xml"
    const val MIMETYPE_NCX = "application/x-dtbncx+xml"
    const val MIMETYPE_XHTML = "application/xhtml+xml"
}

private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03)
private val EOCD_SIG = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x05, 0x06)
private const val EOCD_SEARCH_SIZE = 64 * 1024 + 22

fun isZipFile(path: File): Boolean {
    return try {
        FileInputStream(path).use { f ->
            val head = ByteArray(3)
            val read = f.read(head)
            if (read == 3 && head[0] == ZIP_MAGIC[0] && head[1] == ZIP_MAGIC[1] && head[2] == 0x03.toByte()) {
                return true
            }
            val size = f.channel.size()
            if (size < 22) return false
            val n = minOf(EOCD_SEARCH_SIZE.toLong(), size).toInt()
            f.channel.position(size - n)
            val tail = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = f.read(tail, off, n - off)
                if (r < 0) break
                off += r
            }
            indexOf(tail, EOCD_SIG) != -1
        }
    } catch (_: Exception) {
        false
    }
}

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

data class SpineItem(
    val index: Int,
    val idref: String,
    val href: String,
    val linear: Boolean = true,
    val mediaType: String = "",
)

data class TocEntry(
    val label: String,
    val href: String,
    val spineIndex: Int? = null,
    val children: List<TocEntry> = emptyList(),
)

/** 按 BOM → XML 声明 encoding → UTF-8 → GBK 兜底顺序解码文本。 */
fun decodeText(data: ByteArray): String {
    if (data.size >= 3 &&
        data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()
    ) {
        return String(data, 3, data.size - 3, Charsets.UTF_8)
    }
    val head = String(data, 0, minOf(200, data.size), Charsets.ISO_8859_1)
    val m = Regex("""^\s*<\?xml[^>]*encoding=["']([A-Za-z0-9._-]+)["']""").find(head)
    if (m != null) {
        try {
            return String(data, Charset.forName(m.groupValues[1]))
        } catch (_: Exception) {
            // 未知编码名，继续走 UTF-8/GBK
        }
    }
    try {
        return decodeStrict(data, Charsets.UTF_8)
    } catch (_: CharacterCodingException) {
        return String(data, Charset.forName("GBK"))
    }
}

private fun decodeStrict(data: ByteArray, charset: Charset): String {
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(data)).toString()
}

/** POSIX 路径归一化（与 Python posixpath.normpath 语义对齐）。 */
private fun posixNorm(path: String): String {
    val absolute = path.startsWith("/")
    val stack = ArrayDeque<String>()
    for (p in path.split("/")) {
        when (p) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty() && stack.last() != "..") stack.removeLast() else if (!absolute) stack.addLast("..")
            else -> stack.addLast(p)
        }
    }
    val joined = stack.joinToString("/")
    return if (absolute) "/$joined" else joined
}

private fun normHref(href: String, baseDir: String): String {
    var h = href.substringBefore('#')
    if (h.isEmpty()) return ""
    h = h.trimStart('/')
    h = h.replace('\\', '/')
    val joined = if (baseDir.isEmpty()) h else "$baseDir/$h"
    return posixNorm(joined)
}

/** 单本已解析 EPUB；线程安全只读（持有 ZipFile 句柄，用完 close）。 */
class EpubBook(val path: File) : Closeable {

    val id: String = md5Hex(path.absolutePath.toByteArray(Charsets.UTF_8))

    var title: String = ""
    var author: String = ""
    var language: String = ""
    var publisher: String = ""
    var description: String = ""
    var isbn: String = ""
    val chapters: MutableList<SpineItem> = mutableListOf()
    val toc: MutableList<TocEntry> = mutableListOf()
    val tocMap: MutableMap<String, String> = mutableMapOf()
    var coverHref: String? = null

    private var zip: ZipFile? = null
    private val entries: MutableMap<String, ZipEntry> = mutableMapOf()
    private val entriesLower: MutableMap<String, ZipEntry?> = mutableMapOf()
    private var navHref: String? = null
    private var manifestItems: Map<String, Pair<String, String>> = emptyMap()

    fun open(): EpubBook {
        if (!isZipFile(path)) throw EpubError("不是有效的 EPUB 文件（无法识别为 zip 容器）")
        zip = try {
            ZipFile(path)
        } catch (e: Exception) {
            throw EpubError("损坏的 EPUB 文件：${e.message}", e)
        }
        try {
            buildEntryMap()
            if (hasEntry("META-INF/encryption.xml")) {
                throw EpubError("此文件受 DRM 加密保护，无法阅读")
            }
            val opfPath = findOpf() ?: throw EpubError("损坏的 EPUB：缺少 container.xml 或 OPF 包文件")
            val opfBytes = readFile(opfPath) ?: throw EpubError("损坏的 EPUB：无法读取 OPF 文件")
            val root = parseXml(decodeText(opfBytes), "OPF 文件")
            parseMetadata(root, opfPath)
            parseManifestSpine(root, opfPath)
            parseToc(root, opfPath)
            findCover(root, opfPath)
            return this
        } catch (e: EpubError) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw EpubError("解析 EPUB 失败：${e.message}", e)
        }
    }

    override fun close() {
        try {
            zip?.close()
        } catch (_: Exception) {
        }
        zip = null
    }

    private fun buildEntryMap() {
        entries.clear()
        entriesLower.clear()
        val zf = zip ?: return
        zf.entries().asSequence().forEach { info ->
            val name = info.name
            entries[name] = info
            val low = name.lowercase()
            val existing = entriesLower[low]
            if (existing != null && existing.name != info.name) {
                // 小写名冲突：标记不可用（None），宁可 404
                entriesLower[low] = null
            } else {
                entriesLower[low] = info
            }
        }
    }

    private fun hasEntry(zipPath: String): Boolean =
        entries.containsKey(zipPath) || entriesLower.containsKey(zipPath.lowercase())

    fun readFile(zipPath: String): ByteArray? {
        val info = entries[zipPath] ?: entriesLower[zipPath.lowercase()]
        val zf = zip ?: return null
        if (info == null) return null
        return try {
            zf.getInputStream(info).use { it.readBytes() }
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

    fun getCoverBytes(): ByteArray? = coverHref?.let { readFile(it) }

    // ---------- 解析流水线 ----------

    private fun findOpf(): String? {
        val cnt = readFile("META-INF/container.xml") ?: return null
        val root = parseXml(decodeText(cnt), "container.xml")
        for (rf in root.descendants("rootfile", EpubNamespaces.CNT)) {
            if (rf.getAttribute("media-type") == EpubNamespaces.MIMETYPE_OPF) {
                return normHref(rf.getAttribute("full-path"), "")
            }
        }
        return null
    }

    private fun parseMetadata(root: Element, opfPath: String) {
        val md = root.child("metadata", EpubNamespaces.OPF) ?: return
        title = md.child("title", EpubNamespaces.DC)?.textContent?.trim() ?: ""
        author = md.child("creator", EpubNamespaces.DC)?.textContent?.trim() ?: ""
        language = md.child("language", EpubNamespaces.DC)?.textContent?.trim() ?: ""
        publisher = md.child("publisher", EpubNamespaces.DC)?.textContent?.trim() ?: ""
        description = md.child("description", EpubNamespaces.DC)?.textContent?.trim() ?: ""
        md.child("identifier", EpubNamespaces.DC)?.textContent?.let {
            val scheme = md.child("identifier", EpubNamespaces.DC)?.getAttributeNS(EpubNamespaces.OPF, "scheme")
                ?.uppercase() ?: ""
            if (scheme in setOf("ISBN", "URI", "")) isbn = it.trim()
        }
        if (title.isEmpty()) title = path.nameWithoutExtension
    }

    private fun parseManifestSpine(root: Element, opfPath: String) {
        val opfDir = opfPath.substringBeforeLast('/', "")
        val manifest = root.child("manifest", EpubNamespaces.OPF)
        val spine = root.child("spine", EpubNamespaces.OPF)
        if (manifest == null || spine == null) throw EpubError("损坏的 EPUB：缺少 manifest 或 spine")
        val items = LinkedHashMap<String, Pair<String, String>>()
        var nav: String? = null
        for (it in manifest.descendants("item", EpubNamespaces.OPF)) {
            val iid = it.getAttribute("id")
            val href = it.getAttribute("href")
            val mime = it.getAttribute("media-type")
            if (iid.isEmpty() || href.isEmpty()) continue
            val nh = normHref(href, opfDir)
            items[iid] = nh to mime
            val props = it.getAttribute("properties").split(Regex("\\s+"))
            if ("nav" in props && nav == null) nav = nh
        }
        chapters.clear()
        val seen = HashSet<String>()
        var idx = 0
        for (ir in spine.descendants("itemref", EpubNamespaces.OPF)) {
            val idref = ir.getAttribute("idref")
            val item = items[idref] ?: continue
            val href = item.first
            if (href.isEmpty() || !seen.add(href)) continue
            chapters.add(
                SpineItem(
                    index = idx,
                    idref = idref,
                    href = href,
                    linear = !ir.getAttribute("linear").lowercase().equals("no"),
                    mediaType = item.second,
                ),
            )
            idx++
        }
        navHref = nav
        manifestItems = items
    }

    private fun parseToc(root: Element, opfPath: String) {
        val opfDir = opfPath.substringBeforeLast('/', "")
        var entries: List<TocEntry>? = null

        // EPUB3 nav 优先
        var nav = navHref
        if (nav == null) {
            for ((href, mime) in manifestItems.values) {
                val base = href.substringAfterLast('/').lowercase()
                if (mime == EpubNamespaces.MIMETYPE_XHTML && "nav" in base) {
                    nav = href
                    break
                }
            }
        }
        if (nav != null) {
            val data = readFile(nav)
            if (data != null) {
                val navRoot = parseXml(decodeText(data), "nav 文档")
                val navEl = navRoot.descendants("nav", EpubNamespaces.XHTML)
                    .firstOrNull { it.getAttributeNS(EpubNamespaces.EPUB, "type") == "toc" }
                if (navEl != null) {
                    val ol = navEl.child("ol", EpubNamespaces.XHTML)
                    if (ol != null) {
                        entries = parseNavOl(ol, nav.substringBeforeLast('/', ""))
                    }
                }
            }
        }

        // NCX 兜底
        if (entries == null) {
            var ncx: String? = null
            for ((href, mime) in manifestItems.values) {
                if (mime == EpubNamespaces.MIMETYPE_NCX) {
                    ncx = href
                    break
                }
            }
            if (ncx != null) {
                val data = readFile(ncx)
                if (data != null) {
                    val ncxRoot = parseXml(decodeText(data), "NCX 文件")
                    val navMap = ncxRoot.child("navMap", EpubNamespaces.NCX)
                    if (navMap != null) {
                        entries = parseNcxPoints(navMap, ncx.substringBeforeLast('/', ""))
                    }
                }
            }
        }

        // 都没有 → 扁平目录
        if (entries == null) {
            entries = chapters.mapIndexed { i, c ->
                TocEntry(label = tocMap[c.href] ?: "第 ${i + 1} 章", href = c.href, spineIndex = i)
            }
        }

        toc.clear()
        toc.addAll(entries)
        tocMap.clear()
        fun walk(list: List<TocEntry>) {
            for (e in list) {
                tocMap.putIfAbsent(e.href.substringBefore('#'), e.label)
                walk(e.children)
            }
        }
        walk(entries)
    }

    private fun parseNavOl(ol: Element, baseDir: String): List<TocEntry> {
        val out = mutableListOf<TocEntry>()
        for (li in ol.children("li", EpubNamespaces.XHTML)) {
            val a = li.child("a", EpubNamespaces.XHTML)
            if (a == null) {
                val subOl = li.child("ol", EpubNamespaces.XHTML)
                if (subOl != null) out.addAll(parseNavOl(subOl, baseDir))
                continue
            }
            val href = normHref(a.getAttribute("href"), baseDir)
            val label = a.textContent.trim().ifEmpty { "(无标题)" }
            val childOl = li.child("ol", EpubNamespaces.XHTML)
            val entry = TocEntry(
                label = label,
                href = href.ifEmpty { "#" },
                spineIndex = null,
                children = childOl?.let { parseNavOl(it, baseDir) } ?: emptyList(),
            )
            out.add(entry)
        }
        return out
    }

    private fun parseNcxPoints(parent: Element, baseDir: String): List<TocEntry> {
        val out = mutableListOf<TocEntry>()
        for (np in parent.children("navPoint", EpubNamespaces.NCX)) {
            val nl = np.child("navLabel", EpubNamespaces.NCX)?.child("text", EpubNamespaces.NCX)
            val content = np.child("content", EpubNamespaces.NCX)
            val href = normHref(content?.getAttribute("src") ?: "", baseDir)
            val label = nl?.textContent?.trim().orEmpty()
            val children = np.children("navPoint", EpubNamespaces.NCX)
            val entry = TocEntry(
                label = label.ifEmpty { "(无标题)" },
                href = href.ifEmpty { "#" },
                spineIndex = null,
                children = if (children.isNotEmpty()) parseNcxPoints(np, baseDir) else emptyList(),
            )
            out.add(entry)
        }
        return out
    }

    private fun findCover(root: Element, opfPath: String) {
        val opfDir = opfPath.substringBeforeLast('/', "")
        val md = root.child("metadata", EpubNamespaces.OPF)
        if (md != null) {
            for (meta in md.descendants("meta", EpubNamespaces.OPF)) {
                if (meta.getAttribute("name") == "cover" && meta.getAttribute("content").isNotEmpty()) {
                    val href = manifestItems[meta.getAttribute("content")]?.first
                    if (!href.isNullOrEmpty()) {
                        coverHref = href
                        return
                    }
                }
            }
            for (meta in md.descendants("meta", EpubNamespaces.OPF)) {
                val prop = meta.getAttribute("property").lowercase()
                if (prop in setOf("cover-image", "cover")) {
                    val value = meta.getAttribute("content")
                    manifestItems[value]?.let {
                        coverHref = it.first
                        return
                    }
                    val nh = normHref(value, opfDir)
                    if (nh.isNotEmpty() && hasEntry(nh)) {
                        coverHref = nh
                        return
                    }
                }
            }
        }
        val guide = root.child("guide", EpubNamespaces.OPF)
        if (guide != null) {
            for (ref in guide.descendants("reference", EpubNamespaces.OPF)) {
                if (ref.getAttribute("type") == "cover") {
                    val nh = normHref(ref.getAttribute("href"), opfDir)
                    if (nh.isNotEmpty() && hasEntry(nh)) {
                        coverHref = nh
                        return
                    }
                }
            }
        }
        for ((href, mime) in manifestItems.values) {
            if (mime.startsWith("image/") && href.substringAfterLast('/').lowercase().startsWith("cover")) {
                coverHref = href
                return
            }
        }
    }

    private fun parseXml(text: String, what: String): Element {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                try {
                    // Android 内置解析器不支持该 feature，忽略（默认即不加载外部 DTD）
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                } catch (_: Exception) {
                }
            }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(text)))
            doc.documentElement
        } catch (e: Exception) {
            throw EpubError("解析失败（$what）：${e.message}", e)
        }
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

// ---------- DOM 辅助 ----------

private fun Element.child(local: String, ns: String): Element? {
    var node = firstChild
    while (node != null) {
        if (node is Element && node.localName == local && node.namespaceURI == ns) return node
        node = node.nextSibling
    }
    return null
}

private fun Element.children(local: String, ns: String): List<Element> {
    val out = mutableListOf<Element>()
    var node = firstChild
    while (node != null) {
        if (node is Element && node.localName == local && node.namespaceURI == ns) out.add(node)
        node = node.nextSibling
    }
    return out
}

private fun Element.descendants(local: String, ns: String): List<Element> {
    val out = mutableListOf<Element>()
    fun walk(el: Element) {
        var node = el.firstChild
        while (node != null) {
            if (node is Element) {
                if (node.localName == local && node.namespaceURI == ns) out.add(node)
                walk(node)
            }
            node = node.nextSibling
        }
    }
    walk(this)
    return out
}
