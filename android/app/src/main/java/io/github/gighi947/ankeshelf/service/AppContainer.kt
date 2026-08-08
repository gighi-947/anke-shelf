package io.github.gighi947.ankeshelf.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.EpubBook
import io.github.gighi947.ankeshelf.data.EpubError
import io.github.gighi947.ankeshelf.data.NativeBook
import io.github.gighi947.ankeshelf.data.NativeBookWriter
import io.github.gighi947.ankeshelf.data.ProgressEntry
import io.github.gighi947.ankeshelf.data.ProgressStore
import io.github.gighi947.ankeshelf.data.StatsStore
import io.github.gighi947.ankeshelf.data.AnnotationStore
import io.github.gighi947.ankeshelf.data.Shelf
import io.github.gighi947.ankeshelf.data.SpineItem
import io.github.gighi947.ankeshelf.data.TextExtractor
import io.github.gighi947.ankeshelf.data.nowIso
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import okhttp3.Cache
import okhttp3.OkHttpClient

/** 手动 DI 容器：数据目录 + 书架/进度/设置 + 仓库。 */
class AppContainer(context: Context) {
    val appPaths: AppPaths = AppPaths(File(context.filesDir, AppPaths.APP_DIR_NAME)).also { it.ensure() }
    val shelf = Shelf(appPaths.shelfFile, appPaths.coversDir)
    val progress = ProgressStore(appPaths.progressFile)
    val settings = io.github.gighi947.ankeshelf.data.Settings(appPaths.settingsFile)
    val ngaConfig = io.github.gighi947.ankeshelf.data.NgaConfig(appPaths.ngaConfigFile)
    val searchHistory = io.github.gighi947.ankeshelf.data.SearchHistoryStore(appPaths.searchHistoryFile)
    val stats = StatsStore(appPaths.statisticsFile)
    val annotations = AnnotationStore(appPaths.annotationsFile)
    val repository = BookRepository(appPaths, shelf, progress)
    /** 图片代理用：给 NGA 图床补 Referer/Cookie 规避防盗链；带磁盘缓存，
     *  滚动回看/翻页往返时不重复下载同一图片。 */
    val okHttp = OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "okhttp-images"), 64L * 1024 * 1024))
        .build()

    init {
        shelf.load()
        progress.load()
        settings.load()
        ngaConfig.ensure()
        searchHistory.load()
        stats.load()
        annotations.load()
    }
}

/** 阅读会话：统一 EPUB 与原生书的只读接口（不含文件句柄常驻）。 */
class BookSession(
    val id: String,
    val title: String,
    val author: String,
    val chapters: List<SpineItem>,
    private val textFn: (Int) -> String?,
    private val titleFn: (Int) -> String,
    private val closeFn: () -> Unit,
) : Closeable {
    fun chapterText(index: Int): String? = textFn(index)
    fun chapterTitle(index: Int): String = titleFn(index)
    override fun close() = closeFn()
}

/** 书架条目 + 阅读进度百分比（M2 近似：按章号占比）。 */
data class BookUi(
    val record: BookRecord,
    val progressPct: Double,
    val totalChapters: Int,
)

/** 书籍仓库：导入/登记/打开/进度（M2 阅读 MVP 核心）。 */
class BookRepository(
    private val appPaths: AppPaths,
    private val shelf: Shelf,
    private val progress: ProgressStore,
) {

    private val booksDir: File get() = File(appPaths.root, "books")

    fun listBooks(): List<BookUi> = shelf.listBooks().map { rec ->
        val p = progress.get(rec.id)
        val pct = if (rec.chapter_count > 0 && p != null) {
            (p.chapter_index.coerceIn(0, rec.chapter_count - 1).toDouble() / rec.chapter_count) * 100.0
        } else {
            0.0
        }
        BookUi(rec, pct, rec.chapter_count)
    }

    /** SAF 导入：复制到应用私有目录并登记书架；失败返回 null。 */
    fun importEpub(context: Context, uri: Uri): BookRecord? {
        val name = queryDisplayName(context.contentResolver, uri)
            ?: "book-${System.currentTimeMillis()}.epub"
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .ifBlank { "book.epub" }
        val target = File(booksDir.apply { mkdirs() }, safeName)
        val copied = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } != null
        } catch (_: Exception) {
            false
        }
        if (!copied || !target.isFile) return null
        return registerEpubFile(target)
    }

    /** 登记本地 EPUB 文件（也用于测试）。 */
    fun registerEpubFile(file: File): BookRecord? {
        val book = try {
            EpubBook(file).open()
        } catch (_: EpubError) {
            return null
        }
        return try {
            val coverRel = shelf.extractCover(book)
            val rec = BookRecord(
                id = book.id,
                path = file.absolutePath,
                title = book.title.ifBlank { file.nameWithoutExtension },
                author = book.author,
                language = book.language,
                chapter_count = book.chapters.size,
                cover_rel = coverRel,
                file_size = file.length(),
                file_mtime = file.lastModified().toString(),
                added_at = nowIso(),
            )
            shelf.upsert(rec)
            shelf.save()
            rec
        } finally {
            book.close()
        }
    }

    /** 注册原生书目录（meta.json + chapters/），返回书架记录。 */
    fun registerNativeDir(dir: File, tid: Long): BookRecord? {
        val book = try {
            NativeBook(dir).open()
        } catch (_: Exception) {
            return null
        }
        val rec = BookRecord(
            id = book.id,
            path = dir.absolutePath,
            title = book.title.ifBlank { dir.name },
            author = book.author,
            language = "zh",
            chapter_count = book.chapters.size,
            file_size = 0,
            file_mtime = "",
            added_at = nowIso(),
            nga_tid = tid.toInt(),
        )
        shelf.upsert(rec)
        shelf.save()
        return rec
    }

    /** 按 id 查书架记录。 */
    fun recordOf(bookId: String): BookRecord? = shelf.get(bookId)

    /** 按 NGA tid 查书架记录。 */
    fun findByNgaTid(tid: Long): BookRecord? =
        shelf.listBooks().firstOrNull { it.nga_tid.toLong() == tid }

    /** 打开书籍（原生书目录或 EPUB 文件）。 */
    fun openSession(rec: BookRecord): BookSession? {
        val f = File(rec.path)
        return try {
            if (f.isDirectory) {
                val nb = NativeBook(f).open()
                BookSession(
                    id = nb.id,
                    title = nb.title,
                    author = nb.author,
                    chapters = nb.chapters.toList(),
                    textFn = { nb.chapterText(it) },
                    titleFn = { nb.chapterTitle(it) },
                    closeFn = { nb.close() },
                )
            } else {
                val eb = EpubBook(f).open()
                BookSession(
                    id = eb.id,
                    title = eb.title,
                    author = eb.author,
                    chapters = eb.chapters.toList(),
                    textFn = { eb.chapterText(it) },
                    titleFn = { eb.chapterTitle(it) },
                    closeFn = { eb.close() },
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 章纯文本长度（text_offset 坐标基准）。 */
    fun chapterPlainLength(session: BookSession, index: Int): Int =
        TextExtractor.extractDomText(session.chapterText(index) ?: "").length

    fun saveProgress(bookId: String, chapterIndex: Int, textOffset: Int) {
        progress.set(bookId, chapterIndex, textOffset)
    }

    /** 重命名书籍显示标题：书架记录 + 原生书 meta.json（EPUB 仅书架记录）。 */
    fun renameBook(rec: BookRecord, newTitle: String): BookRecord? {
        val title = newTitle.trim()
        if (title.isEmpty() || title == rec.title) return rec
        val updated = rec.copy(title = title)
        shelf.upsert(updated)
        shelf.save()
        val f = File(rec.path)
        if (f.isDirectory) {
            runCatching { NativeBookWriter.renameTitle(f, title) }
        }
        return updated
    }

    fun progressOf(bookId: String): ProgressEntry? = progress.get(bookId)

    fun removeBook(rec: BookRecord) {
        shelf.remove(rec.id)
        shelf.save()
        progress.remove(rec.id)
        try {
            val f = File(rec.path)
            if (f.isDirectory) {
                // 原生书：删除整个帖子目录（含 book/ 与 download.json 断点）。
                f.deleteRecursively()
                f.parentFile?.let { File(it, "download.json").delete() }
            } else {
                f.delete()
            }
            rec.cover_rel?.let { rel ->
                File(appPaths.coversDir, rel.substringAfterLast('/')).delete()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** 滚动比例 → text_offset（与桌面旧 scroll_ratio 迁移语义一致）。 */
        fun offsetForRatio(ratio: Double, plainLength: Int): Int {
            if (plainLength <= 0) return 0
            return (ratio.coerceIn(0.0, 1.0) * plainLength).roundToInt().coerceIn(0, plainLength)
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
}
