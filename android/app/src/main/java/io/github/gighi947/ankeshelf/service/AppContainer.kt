package io.github.gighi947.ankeshelf.service

import android.content.Context
import android.net.Uri
import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.BookRecord
import io.github.gighi947.ankeshelf.data.BookTag
import io.github.gighi947.ankeshelf.data.ChapterReadResult
import io.github.gighi947.ankeshelf.data.EpubBook
import io.github.gighi947.ankeshelf.data.EpubError
import io.github.gighi947.ankeshelf.data.GululuSource
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
import io.github.gighi947.ankeshelf.data.sniffImageExt
import io.github.gighi947.ankeshelf.data.queryDisplayName
import io.github.gighi947.ankeshelf.ui.reader.TocNode
import io.github.gighi947.ankeshelf.ui.reader.TocTree
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import okhttp3.Cache
import okhttp3.OkHttpClient

/** 手动 DI 容器：数据目录 + 书架/进度/设置 + 仓库。
 *  [dataDir] 可注入（UI 测试用临时目录隔离，不触碰真实用户数据）；默认应用私有目录。 */
class AppContainer(
    context: Context,
    dataDir: File = File(context.filesDir, AppPaths.APP_DIR_NAME),
) {
    val appPaths: AppPaths = AppPaths(dataDir).also { it.ensure() }
    val shelf = Shelf(appPaths.shelfFile, appPaths.coversDir)
    val progress = ProgressStore(appPaths.progressFile)
    val settings = io.github.gighi947.ankeshelf.data.Settings(appPaths.settingsFile)
    val ngaConfig = io.github.gighi947.ankeshelf.data.NgaConfig(appPaths.ngaConfigFile)
    val searchHistory = io.github.gighi947.ankeshelf.data.SearchHistoryStore(appPaths.searchHistoryFile)
    val stats = StatsStore(appPaths.statisticsFile)
    val annotations = AnnotationStore(appPaths.annotationsFile)
    /** 骨碌碌阅读解锁状态（骰点分组 + 秘密线索，端私有）。 */
    val gululuUnlocks = io.github.gighi947.ankeshelf.data.GululuUnlockStore(
        appPaths.gululuUnlocksFile,
        appPaths.gululuCluesFile,
    )
    /** 骨碌碌在线评论（5 分钟缓存 + 离线回退）。 */
    val gululuComments = GululuCommentService(appPaths)
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
        gululuUnlocks.load()
    }
}

/** 阅读会话：统一 EPUB 与原生书的只读接口（不含文件句柄常驻）。 */
class BookSession(
    val id: String,
    val title: String,
    val author: String,
    val chapters: List<SpineItem>,
    private val textFn: (Int) -> ChapterReadResult,
    private val titleFn: (Int) -> String,
    private val closeFn: () -> Unit,
    private val baseDirFn: (Int) -> String = { "" },
    private val assetFn: ((Int, String) -> ByteArray?)? = null,
    /** 嵌套目录（EPUB nav/NCX 层级）；缺省为空表示按 spine 章节扁平展示。 */
    private val tocFn: () -> List<TocNode> = { emptyList() },
    /** 骨碌碌公开书籍 ID（>0 表示这本书来自骨碌碌，宿主层交互据此启用）。 */
    val gululuSourceId: Int = 0,
) : Closeable {
    fun chapterText(index: Int): ChapterReadResult = textFn(index)
    fun chapterTitle(index: Int): String = titleFn(index)
    fun chapterBaseDir(index: Int): String = baseDirFn(index)

    /** 目录节点（嵌套已扁平化）；为空时调用方回退 spine 章节列表。 */
    fun tocNodes(): List<TocNode> = tocFn()

    /** 读章节相对资源（EPUB 图片等）；原生书返回 null（走远程/本地图拦截）。 */
    fun readAsset(chapterIndex: Int, rel: String): ByteArray? =
        assetFn?.invoke(chapterIndex, rel)

    override fun close() = closeFn()
}

/** 书架条目 + 阅读进度百分比（章号 + 章内比例，见 [BookRepository.listBooks]）。 */
data class BookUi(
    val record: BookRecord,
    val progressPct: Double,
    val totalChapters: Int,
)

/** 书籍仓库的显式失败模型：调用方不再靠 null 猜测失败原因。 */
sealed class BookRepoError(val message: String) {
    data object NotFound : BookRepoError("书籍文件不存在")
    data object Corrupt : BookRepoError("书籍文件损坏或格式无法解析")
    data class Io(val detail: String) : BookRepoError("读取书籍失败：$detail")
}

sealed interface RepoResult<out T> {
    data class Ok<T>(val value: T) : RepoResult<T>
    data class Err(val error: BookRepoError) : RepoResult<Nothing>
}

/** 书籍仓库：导入/登记/打开/进度（M2 阅读 MVP 核心）。 */
class BookRepository(
    private val appPaths: AppPaths,
    private val shelf: Shelf,
    private val progress: ProgressStore,
) {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "book-repo-io").apply { isDaemon = true }
    }
    private val booksDir: File get() = File(appPaths.root, "books")

    fun listBooks(): List<BookUi> = shelf.listBooks().map { rec ->
        val p = progress.get(rec.id)
        val pct = progressPercent(p, rec.chapter_count)
        BookUi(rec, pct, rec.chapter_count)
    }

    /** SAF 导入：复制到应用私有目录并登记书架；失败返回显式原因。 */
    fun importEpub(context: Context, uri: Uri): RepoResult<BookRecord> {
        val name = queryDisplayName(context.contentResolver, uri)
            ?: "book-${System.currentTimeMillis()}.epub"
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .ifBlank { "book.epub" }
        val target = File(booksDir.apply { mkdirs() }, safeName)
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            return RepoResult.Err(BookRepoError.Io(e.toString()))
        }
        if (input == null) return RepoResult.Err(BookRepoError.NotFound)
        val copied = try {
            input.use { src -> target.outputStream().use { out -> src.copyTo(out) } }
            true
        } catch (e: Exception) {
            return RepoResult.Err(BookRepoError.Io(e.toString()))
        }
        if (!copied || !target.isFile) return RepoResult.Err(BookRepoError.Io("复制失败"))
        return registerEpubFile(target)
    }

    /** 登记本地 EPUB 文件（也用于测试）。 */
    fun registerEpubFile(file: File, tags: List<BookTag> = emptyList()): RepoResult<BookRecord> {
        if (!file.isFile) return RepoResult.Err(BookRepoError.NotFound)
        val book = try {
            EpubBook(file).open()
        } catch (_: EpubError) {
            return RepoResult.Err(BookRepoError.Corrupt)
        } catch (e: Exception) {
            return RepoResult.Err(BookRepoError.Io(e.toString()))
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
                tags = tags,
            )
            shelf.upsert(rec)
            shelf.save()
            RepoResult.Ok(rec)
        } catch (e: Exception) {
            RepoResult.Err(BookRepoError.Io(e.toString()))
        } finally {
            book.close()
        }
    }

    /** 注册原生书目录（meta.json + chapters/），返回书架记录。 */
    fun registerNativeDir(dir: File, tid: Long): RepoResult<BookRecord> {
        if (!dir.isDirectory || !File(dir, "meta.json").isFile) {
            return RepoResult.Err(BookRepoError.NotFound)
        }
        val book = try {
            NativeBook(dir).open()
        } catch (_: EpubError) {
            return RepoResult.Err(BookRepoError.Corrupt)
        } catch (e: Exception) {
            return RepoResult.Err(BookRepoError.Io(e.toString()))
        }
        return try {
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
                tags = listOf(BookTag("NGA", "#2e86ab")),
            )
            shelf.upsert(rec)
            shelf.save()
            RepoResult.Ok(rec)
        } catch (e: Exception) {
            RepoResult.Err(BookRepoError.Io(e.toString()))
        }
    }

    /** 按 id 查书架记录。 */
    fun recordOf(bookId: String): BookRecord? = shelf.get(bookId)

    /** 按 NGA tid 查书架记录。 */
    fun findByNgaTid(tid: Long): BookRecord? =
        shelf.listBooks().firstOrNull { it.nga_tid.toLong() == tid }

    /** 更新书籍标签（内容上限 10 字、颜色为 #RRGGBB）。 */
    fun updateBookTags(bookId: String, tags: List<BookTag>): BookRecord? {
        val rec = shelf.get(bookId) ?: return null
        val cleaned = tags
            .filter { it.name.isNotBlank() }
            .map { BookTag(it.name.trim().take(10), it.color) }
            .distinctBy { it.name }
        val updated = rec.copy(tags = cleaned)
        shelf.upsert(updated)
        shelf.save()
        return updated
    }

    /** 打开书籍（原生书目录或 EPUB 文件）。 */
    fun openSession(rec: BookRecord): RepoResult<BookSession> {
        val f = File(rec.path)
        if (!f.exists()) return RepoResult.Err(BookRepoError.NotFound)
        return try {
            if (f.isDirectory) {
                val nb = NativeBook(f).open()
                RepoResult.Ok(
                    BookSession(
                        id = nb.id,
                        title = nb.title,
                        author = nb.author,
                        chapters = nb.chapters.toList(),
                        textFn = { nb.chapterText(it) },
                        titleFn = { nb.chapterTitle(it) },
                        closeFn = { nb.close() },
                    ),
                )
            } else {
                val eb = EpubBook(f).open()
                RepoResult.Ok(
                    BookSession(
                        id = eb.id,
                        title = eb.title,
                        author = eb.author,
                        chapters = eb.chapters.toList(),
                        textFn = { eb.chapterText(it) },
                        titleFn = { eb.chapterTitle(it) },
                        closeFn = { eb.close() },
                        baseDirFn = { eb.chapterBaseDir(it) },
                        assetFn = { idx, rel -> eb.resolveAsset(idx, rel) },
                        tocFn = { TocTree.flatten(eb.toc.toList()) { href -> eb.tocSpineIndex(href) } },
                        gululuSourceId = GululuSource.parseGululuIdentifier(eb.identifier) ?: 0,
                    ),
                )
            }
        } catch (_: EpubError) {
            RepoResult.Err(BookRepoError.Corrupt)
        } catch (e: Exception) {
            RepoResult.Err(BookRepoError.Io(e.toString()))
        }
    }

    fun saveProgress(
        bookId: String,
        chapterIndex: Int,
        textOffset: Int,
        pageIndex: Int = -1,
        pageTotal: Int = -1,
        scrollRatio: Double = -1.0,
    ) {
        // 对齐桌面 api.save_progress：更新进度并同步“最近阅读”（60s 节流落盘）。
        // progress.set 同步更新内存并自行排队落盘（write 已在后台线程）；
        // shelf.touch 的整文件写入放到本仓库后台线程，主线程不做磁盘 I/O。
        progress.set(bookId, chapterIndex, textOffset, pageIndex, pageTotal, scrollRatio)
        io.execute { shelf.touch(bookId) }
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

    /** 设置自定义封面：复制图片到 covers/<id>.<ext> 并更新书架记录。 */
    fun setCustomCover(rec: BookRecord, uri: Uri, context: Context): RepoResult<BookRecord> {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return RepoResult.Err(BookRepoError.NotFound)
            if (bytes.isEmpty()) return RepoResult.Err(BookRepoError.Io("封面文件为空"))
            val ext = sniffImageExt(bytes)
            val target = File(appPaths.coversDir, "${rec.id}.$ext")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            val updated = rec.copy(cover_rel = "covers/${rec.id}.$ext")
            shelf.upsert(updated)
            shelf.save()
            RepoResult.Ok(updated)
        } catch (e: Exception) {
            RepoResult.Err(BookRepoError.Io(e.toString()))
        }
    }

    /** 恢复默认封面：清除 cover_rel 并删除封面缓存文件。 */
    fun resetCover(rec: BookRecord): RepoResult<BookRecord> {
        val updated = rec.copy(cover_rel = null)
        shelf.upsert(updated)
        shelf.save()
        rec.cover_rel?.let { old ->
            runCatching { File(appPaths.coversDir, old.substringAfterLast('/')).delete() }
        }
        return RepoResult.Ok(updated)
    }

    fun progressOf(bookId: String): ProgressEntry? = progress.get(bookId)

    /** 删除书架条目与本地文件；返回文件是否删除成功（条目已移除，失败可提示残留）。 */
    fun removeBook(rec: BookRecord): Boolean {
        shelf.remove(rec.id)
        shelf.save()
        progress.remove(rec.id)
        val fileOk = try {
            val f = File(rec.path)
            val deleted = if (f.isDirectory) {
                // 原生书：删除整个帖子目录（含 book/ 与 download.json 断点）。
                val ok = f.deleteRecursively()
                f.parentFile?.let { File(it, "download.json").delete() }
                ok
            } else {
                f.delete()
            }
            rec.cover_rel?.let { rel ->
                File(appPaths.coversDir, rel.substringAfterLast('/')).delete()
            }
            deleted
        } catch (e: Exception) {
            LogEvents.event(
                "shelf",
                "remove_file_failed",
                "book_id_hash" to LogEvents.bookIdHash(rec.id),
                "error" to (e.toString()),
            )
            false
        }
        if (!fileOk) {
            LogEvents.event(
                "shelf",
                "remove_file_failed",
                "book_id_hash" to LogEvents.bookIdHash(rec.id),
                "error" to "delete returned false",
            )
        }
        return fileOk
    }

    companion object {
        /** 滚动比例 → text_offset（与桌面旧 scroll_ratio 迁移语义一致）。 */
        fun offsetForRatio(ratio: Double, plainLength: Int): Int {
            if (plainLength <= 0) return 0
            return (ratio.coerceIn(0.0, 1.0) * plainLength).roundToInt().coerceIn(0, plainLength)
        }

        /**
         * 书架进度百分比 = (章索引 + 章内比例) / 总章数 × 100（对齐桌面
         * `app/api/common.py:progress_pct` 的语义）。
         *
         * 桌面用「text_offset / 章纯文本长」求章内比例，需要全文索引就绪；
         * 书架列表不打开书籍，因此这里用已持久化的安卓扩展字段近似：
         * 分页模式用 `page_index / page_total`，滚动模式用 `scroll_ratio`，
         * 两者都没有时退回纯章号占比（与旧行为一致，不会倒退）。
         */
        fun progressPercent(entry: ProgressEntry?, chapterCount: Int): Double {
            if (entry == null || chapterCount <= 0) return 0.0
            val idx = entry.chapter_index.coerceIn(0, chapterCount - 1)
            val inChapter = when {
                entry.page_total > 0 && entry.page_index >= 0 ->
                    (entry.page_index.toDouble() / entry.page_total).coerceIn(0.0, 1.0)
                entry.scroll_ratio in 0.0..1.0 -> entry.scroll_ratio
                else -> 0.0
            }
            return (((idx + inChapter) / chapterCount) * 100.0).coerceIn(0.0, 100.0)
        }
    }
}
