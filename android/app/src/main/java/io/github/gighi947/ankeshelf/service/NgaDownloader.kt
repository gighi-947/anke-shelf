package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.NativeBook
import io.github.gighi947.ankeshelf.data.NativeBookWriter
import io.github.gighi947.ankeshelf.data.NativeFloor
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.NgaFormatHtml
import io.github.gighi947.ankeshelf.data.NativeTocChapter
import io.github.gighi947.ankeshelf.data.NgaTocParser
import io.github.gighi947.ankeshelf.data.atomicWriteJson
import io.github.gighi947.ankeshelf.data.nowIso
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

/** 下载/更新参数（对齐桌面 NgaService.start / update_book 的子集）。 */
data class NgaDownloadParams(
    val tid: Long,
    val authorId: Long = 0,
    val maxFloors: Int = 0,
    /** 单次最多新增页数（0=不限制；对齐桌面 cfg.page_download_limit）。 */
    val pageLimit: Int = 0,
    val imageMode: String = "online",
    val theme: String = "light",
    val perChapter: Int = 20,
    /** 目录楼 pid（>0 时解析该楼为章节目录；对齐桌面 cfg.epub_toc_pid）。 */
    val tocPid: Long = 0,
    /** 分章方式：index=每章固定楼数；split=按目录楼分章（需 tocPid 解析成功）。 */
    val tocMode: String = "index",
    val fullRedownload: Boolean = false,
)

/** 下载进度回调（对齐桌面 NgaService status）。 */
data class NgaProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
    val detail: String = "",
)

/** 本地下载断点（book 目录旁 download.json，桌面目录结构不冲突）。 */
@Serializable
data class NgaDownloadState(
    val tid: Long = 0,
    val author_id: Long = 0,
    val max_page: Int = 1,
    val max_floor: Int = -1,
    val theme: String = "light",
    val image_mode: String = "online",
    val per_chapter: Int = 20,
    /** 最近一次使用的参数（热更新表单默认值；对齐桌面 download_settings.json）。 */
    val page_limit: Int = 0,
    val toc_pid: Long = 0,
    val toc_mode: String = "index",
)

/**
 * NGA 下载编排（对齐桌面 app/nga_service.py _download / _update_core）：
 * 拉页 → 收集楼层 → NativeBookWriter 首建/增量追加 → 注册书架。
 */
class NgaDownloader(
    private val appPaths: AppPaths,
    private val repository: BookRepository,
    private val config: NgaConfig,
    @Volatile var taskId: String = "",
) {
    // 超时对齐 GululuImages：connect 15s / read 30s。此前用无参 OkHttpClient()，
    // 慢速滴流连接可无限拖住内嵌图片下载（readTimeout 只约束单次 socket read）。
    private val imageHttp = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    var cancelled = false
        private set

    private var listener: ((NgaProgress) -> Unit)? = null

    fun setListener(l: ((NgaProgress) -> Unit)?) {
        listener = l
    }

    fun cancel() {
        cancelled = true
    }

    private fun progress(stage: String, current: Int = 0, total: Int = 0, detail: String = "") {
        listener?.invoke(NgaProgress(stage, current, total, detail))
    }

    private fun checkCancel() {
        if (cancelled) throw NgaCancelled()
    }

    /** 下载楼层正文中的 [img] 图片到 images/<bookId>/（embedded 本地化模式）。 */
    private fun downloadImages(floors: List<NativeFloor>, bookId: String) {
        if (bookId.isBlank()) return
        val cfg = config.load()
        val urls = LinkedHashSet<String>()
        for (f in floors) {
            collectImageUrls(f.raw_content, urls)
            f.comments.forEach { collectImageUrls(it.raw_content, urls) }
        }
        if (urls.isEmpty()) return
        val dir = File(appPaths.root, "images/$bookId").apply { mkdirs() }
        // 并发下载 + 逐张进度上报（NgaImageDownloads；修复前串行且零上报，
        // 前台通知停在楼层阶段表现为"无进度、像卡住"）。单图失败不中断。
        val completed = NgaImageDownloads.drain(
            urls = urls.toList(),
            isCached = { url ->
                val t = File(dir, NativeBookWriter.imageFileName(url))
                t.isFile && t.length() > 0
            },
            load = { url ->
                try {
                    val req = Request.Builder()
                        .url(url)
                        .ngaHeaders(cfg)
                        .build()
                    imageHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            LogEvents.event(
                                "nga", "image_download_failed",
                                "task_id" to taskId, "url" to url,
                                "error" to "HTTP ${resp.code}",
                            )
                            null
                        } else {
                            resp.body.byteStream().use { it.readBytes() }
                        }
                    }
                } catch (e: Exception) {
                    LogEvents.event(
                        "nga", "image_download_failed",
                        "task_id" to taskId, "url" to url,
                        "error" to e.toString(),
                    )
                    null
                }
            },
            persist = { url, bytes ->
                val target = File(dir, NativeBookWriter.imageFileName(url))
                target.outputStream().use { it.write(bytes) }
            },
            onProgress = { done, total, ok, failed ->
                progress("images", done, total, "正在下载图片 $done/$total（成功 $ok，失败 $failed）")
            },
            isCancelled = { cancelled },
        )
        if (!completed) {
            if (cancelled) throw NgaCancelled()
            // 整体卡死（超时窗口内无任何图片完成）：显式失败，不静默半成品
            throw NgaHttpException("图片下载超时终止（网络过慢或图床无响应），请重试或改用在线图片模式")
        }
    }

    private fun collectImageUrls(content: String, out: MutableSet<String>) {
        Regex("\\[img\\](.+?)\\[/img\\]", RegexOption.IGNORE_CASE)
            .findAll(content)
            .forEach { m ->
                val raw = m.groupValues[1].trim()
                if (raw.isNotEmpty()) out.add(normalizeImageUrl(raw))
            }
    }

    private fun normalizeImageUrl(url: String): String = NgaFormatHtml.normalizeImageUrl(url)

    /** 首次下载：拉全部页 → 写原生书 → 注册书架，返回 bookId。 */
    fun download(params: NgaDownloadParams): String {
        cancelled = false
        val cfg = config.load()
        if (!cfg.configured) {
            throw NgaHttpException("请先在下载页填写 NGA Cookie（ngaPassportUid / ngaPassportCid）")
        }
        val client = NgaClient(cookieUid = cfg.uid, cookieCid = cfg.cid, userAgent = cfg.ua, baseUrl = cfg.baseUrl)
        val folderName = folderNameFor(params.tid, params.authorId)
        val nativeDir = NativeBookWriter.nativeDirFor(appPaths.ngaLibraryDir, folderName)
        if (params.fullRedownload) {
            File(appPaths.ngaLibraryDir, folderName).deleteRecursively()
        } else if (NativeBookWriter.isNativeDir(nativeDir)) {
            // 已存在同 tid 原生书且非强制重下：对齐桌面走增量更新
            val added = updateFolder(folderName, params.tid, params.authorId, params)
            val rec = repository.findByNgaTid(params.tid)
                ?: throw NgaHttpException("书架中找不到该书")
            LogEvents.event(
                "nga",
                "update_done",
                "task_id" to taskId,
                "tid" to params.tid,
                "floors" to added,
                "book_id_hash" to LogEvents.bookIdHash(rec.id),
            )
            progress("done", added, added, if (added > 0) "已更新 $added 楼" else "已是最新")
            return rec.id
        }
        try {
            progress("pages", 0, 1, "正在初始化…")
            val first = client.fetchPageFull(params.tid, 1, params.authorId)
            if (first.code != 0) {
                throw NgaHttpException("NGA 返回代码不为 0：${first.code} ${first.msg}")
            }
            val totalPage = first.totalPage.coerceAtLeast(1)
            // 页数上限：对齐桌面 cfg.page_download_limit（0=不限制），从第 1 页起算。
            val lastWanted = if (params.pageLimit > 0) {
                minOf(totalPage, params.pageLimit)
            } else {
                totalPage
            }
            var lastPage = lastWanted
            val floors = mutableListOf<NativeFloor>()
            floors.addAll(first.floors)
            progress("pages", 1, lastWanted, "正在下载第 1/$lastWanted 页")
            for (p in 2..lastWanted) {
                checkCancel()
                val pageData = client.fetchPageFull(params.tid, p, params.authorId)
                if (pageData.code != 0) {
                    throw NgaHttpException("NGA 返回代码不为 0：${pageData.code} ${pageData.msg}")
                }
                floors.addAll(pageData.floors)
                progress("pages", p, lastWanted, "正在下载第 $p/$lastWanted 页")
                if (params.maxFloors > 0 &&
                    floors.count { it.lou != -1 } >= params.maxFloors
                ) {
                    lastPage = p
                    break
                }
            }
            checkCancel()
            val valid = validFloors(floors, params.maxFloors)
            val bookId = nativeBookId(nativeDir)
            val tocChapters = fetchTocChapters(client, params)
            if (valid.isNotEmpty()) {
                val imagesDir = File(appPaths.root, "images/$bookId")
                if (params.imageMode == "embedded") downloadImages(valid, bookId)
                progress("format", 0, 0, "正在写入原生书…")
                NativeBookWriter.writeContainer(
                    ngaLibraryRoot = appPaths.ngaLibraryDir,
                    folderName = folderName,
                    tieziTitle = first.title,
                    author = first.author,
                    tid = params.tid,
                    authorId = params.authorId,
                    createdTime = nowIso(),
                    updatedTime = nowIso(),
                    validFloors = valid,
                    perChapter = params.perChapter,
                    imageMode = params.imageMode,
                    theme = params.theme,
                    bookId = bookId,
                    tocChapters = tocChapters,
                    tocMode = params.tocMode,
                    imagesDir = imagesDir,
                )
                saveState(folderName, lastPage, valid, params)
                val registered = repository.registerNativeDir(nativeDir, params.tid)
                if (registered is RepoResult.Err) {
                    throw NgaHttpException("书籍登记失败：${registered.error.message}")
                }
            } else {
                throw NgaHttpException("没有可用的楼层内容")
            }
            progress("done", lastWanted, lastWanted, "下载完成")
            LogEvents.event(
                "nga",
                "download_done",
                "task_id" to taskId,
                "tid" to params.tid,
                "floors" to totalPage,
                "book_id_hash" to LogEvents.bookIdHash(bookId),
            )
            return bookId
        } finally {
            // 取消且目录为本次新建时清理半成品
            if (cancelled) {
                val folder = File(appPaths.ngaLibraryDir, folderName)
                if (folder.isDirectory && !File(folder, "book/meta.json").isFile) {
                    folder.deleteRecursively()
                }
            }
        }
    }

    /** 增量热更新：断点续拉新页 → appendContainer，返回新增楼层数。 */
    fun update(bookId: String, params: NgaDownloadParams): Int {
        cancelled = false
        val record = repository.recordOf(bookId) ?: throw NgaHttpException("书架中找不到该书")
        val nativeDir = File(record.path)
        if (!NativeBookWriter.isNativeDir(nativeDir)) {
            throw NgaHttpException("仅支持更新 NGA 下载的原生书")
        }
        val folderName = nativeDir.parentFile?.name ?: ""
        return updateFolder(folderName, params.tid, params.authorId, params)
    }

    private fun updateFolder(
        folderName: String,
        tid: Long,
        authorId: Long,
        params: NgaDownloadParams,
    ): Int {
        cancelled = false
        val cfg = config.load()
        if (!cfg.configured) {
            throw NgaHttpException("请先配置 NGA Cookie")
        }
        val nativeDir = NativeBookWriter.nativeDirFor(appPaths.ngaLibraryDir, folderName)
        val state = loadState(folderName, tid, authorId)
        val client = NgaClient(
            cookieUid = cfg.uid,
            cookieCid = cfg.cid,
            userAgent = cfg.ua,
            baseUrl = cfg.baseUrl,
        )
        val startPage = state.max_page.coerceAtLeast(1)
        progress("pages", startPage, startPage, "正在检查更新…")
        val first = client.fetchPageFull(tid, startPage, authorId)
        if (first.code != 0) {
            throw NgaHttpException("NGA 返回代码不为 0：${first.code} ${first.msg}")
        }
        val totalPage = first.totalPage.coerceAtLeast(1)
        // 页数上限：只约束本次新增页（桌面 page_download_limit 同语义，从断点页起算）。
        val lastWanted = if (params.pageLimit > 0) {
            minOf(totalPage, startPage + params.pageLimit - 1)
        } else {
            totalPage
        }
        val newFloors = mutableListOf<NativeFloor>()
        newFloors.addAll(first.floors)
        var lastPage = lastWanted
        for (p in (startPage + 1)..lastWanted) {
            checkCancel()
            val pageData = client.fetchPageFull(tid, p, authorId)
            if (pageData.code != 0) {
                throw NgaHttpException("NGA 返回代码不为 0：${pageData.code} ${pageData.msg}")
            }
            newFloors.addAll(pageData.floors)
            progress("pages", p, lastWanted, "正在下载第 $p/$lastWanted 页")
            if (params.maxFloors > 0 &&
                validFloors(newFloors, params.maxFloors).size >= params.maxFloors
            ) {
                lastPage = p
                break
            }
        }
        checkCancel()
        val valid = validFloors(newFloors, params.maxFloors)
        val meta = NativeBookWriter.loadMeta(nativeDir)
        val imagesDir = File(appPaths.root, "images/${meta.book_id}")
        if (params.imageMode == "embedded") downloadImages(valid, meta.book_id)
        progress("format", 0, 0, "正在追加楼层…")
        val added = NativeBookWriter.appendContainer(
            ngaLibraryRoot = appPaths.ngaLibraryDir,
            folderName = folderName,
            newFloors = valid,
            perChapter = params.perChapter,
            imageMode = params.imageMode,
            theme = params.theme,
            imagesDir = imagesDir,
        )
        saveState(folderName, lastPage, valid, params.copy(tid = tid))
        progress("done", lastWanted, lastWanted, if (added > 0) "已更新 $added 楼" else "已是最新")
        return added
    }

    /**
     * 目录楼 → 章节目录（tocPid<=0 或解析不出条目时返回 null，调用方回退按楼分章）。
     * 目录是可选增强：网络/解析失败只记诊断并降级，不让整本下载失败。
     */
    private fun fetchTocChapters(
        client: NgaClient,
        params: NgaDownloadParams,
    ): List<NativeTocChapter>? {
        if (params.tocPid <= 0) return null
        progress("format", 0, 0, "正在解析目录楼…")
        val content = try {
            client.fetchFloorContent(params.tid, params.tocPid)
        } catch (e: NgaHttpException) {
            LogEvents.event(
                "nga",
                "toc_fetch_failed",
                "task_id" to taskId,
                "tid" to params.tid,
                "toc_pid" to params.tocPid,
                "error" to e.toString(),
            )
            return null
        }
        val chapters = NgaTocParser.parseToc(content)
        LogEvents.event(
            "nga",
            "toc_parsed",
            "task_id" to taskId,
            "tid" to params.tid,
            "toc_pid" to params.tocPid,
            "chapters" to chapters.size,
        )
        return chapters.ifEmpty { null }
    }

    /**
     * 返回某本已下载 NGA 书的更新默认参数（对齐桌面 update_defaults）：
     * 优先 download.json（最近一次下载/更新设置），回退 meta.json。
     */
    fun defaultsFor(bookId: String): NgaDownloadParams? {
        val record = repository.recordOf(bookId) ?: return null
        val nativeDir = File(record.path)
        if (!NativeBookWriter.isNativeDir(nativeDir)) return null
        val meta = try {
            NativeBookWriter.loadMeta(nativeDir)
        } catch (_: Exception) {
            return null
        }
        val folderName = nativeDir.parentFile?.name ?: ""
        val state = try {
            loadState(folderName, meta.tid, meta.author_id)
        } catch (_: Exception) {
            NgaDownloadState(tid = meta.tid, author_id = meta.author_id)
        }
        return NgaDownloadParams(
            tid = meta.tid,
            authorId = state.author_id,
            imageMode = if (state.image_mode in setOf("online", "embedded", "none")) {
                state.image_mode
            } else {
                meta.image_mode
            },
            theme = if (state.theme in setOf("light", "dark")) state.theme else meta.theme,
            perChapter = maxOf(1, state.per_chapter),
            maxFloors = 0,
            pageLimit = maxOf(0, state.page_limit),
            tocPid = maxOf(0, state.toc_pid),
            tocMode = if (state.toc_mode in setOf("index", "split")) state.toc_mode else meta.toc_mode,
        )
    }

    private fun validFloors(floors: List<NativeFloor>, maxFloors: Int): List<NativeFloor> {
        var valid = floors.filter { it.lou != -1 && (maxFloors <= 0 || it.lou <= maxFloors) }
        if (maxFloors > 0 && valid.size > maxFloors) {
            valid = valid.take(maxFloors)
        }
        return valid
    }

    private fun stateFile(folderName: String): File =
        File(appPaths.ngaLibraryDir, "$folderName/download.json")

    private fun saveState(folderName: String, maxPage: Int, floors: List<NativeFloor>, params: NgaDownloadParams) {
        val maxFloor = floors.maxOfOrNull { it.lou } ?: -1
        atomicWriteJson(
            stateFile(folderName),
            json.encodeToString(
                NgaDownloadState.serializer(),
                NgaDownloadState(
                    tid = params.tid,
                    author_id = params.authorId,
                    max_page = maxPage,
                    max_floor = maxFloor,
                    theme = params.theme,
                    image_mode = params.imageMode,
                    per_chapter = params.perChapter,
                    page_limit = params.pageLimit,
                    toc_pid = params.tocPid,
                    toc_mode = params.tocMode,
                ),
            ),
        )
    }

    private fun loadState(folderName: String, tid: Long, authorId: Long): NgaDownloadState {
        val f = stateFile(folderName)
        return try {
            json.decodeFromString<NgaDownloadState>(f.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            NgaDownloadState(tid = tid, author_id = authorId, max_page = 1, max_floor = -1)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun folderNameFor(tid: Long, authorId: Long): String =
            if (authorId > 0) "$tid($authorId)" else tid.toString()
    }
}

class NgaCancelled : Exception("已取消")

/** 原生书 id（与桌面一致：md5(目录绝对路径)）。 */
fun nativeBookId(dir: File): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    val digest = md.digest(dir.absolutePath.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
