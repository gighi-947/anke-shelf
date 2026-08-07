package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.NativeBook
import io.github.gighi947.ankeshelf.data.NativeBookWriter
import io.github.gighi947.ankeshelf.data.NativeFloor
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.atomicWriteJson
import io.github.gighi947.ankeshelf.data.nowIso
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 下载/更新参数（对齐桌面 NgaService.start / update_book 的子集）。 */
data class NgaDownloadParams(
    val tid: Long,
    val authorId: Long = 0,
    val maxFloors: Int = 0,
    val imageMode: String = "online",
    val theme: String = "light",
    val perChapter: Int = 20,
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
)

/**
 * NGA 下载编排（对齐桌面 app/nga_service.py _download / _update_core）：
 * 拉页 → 收集楼层 → NativeBookWriter 首建/增量追加 → 注册书架。
 */
class NgaDownloader(
    private val appPaths: AppPaths,
    private val repository: BookRepository,
    private val config: NgaConfig,
) {

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
        }
        try {
            progress("pages", 0, 1, "正在初始化…")
            val first = client.fetchPageFull(params.tid, 1, params.authorId)
            if (first.code != 0) {
                throw NgaHttpException("NGA 返回代码不为 0：${first.code} ${first.msg}")
            }
            val totalPage = first.totalPage.coerceAtLeast(1)
            val floors = mutableListOf<NativeFloor>()
            floors.addAll(first.floors)
            progress("pages", 1, totalPage, "正在下载第 1/$totalPage 页")
            for (p in 2..totalPage) {
                checkCancel()
                val pageData = client.fetchPageFull(params.tid, p, params.authorId)
                if (pageData.code != 0) {
                    throw NgaHttpException("NGA 返回代码不为 0：${pageData.code} ${pageData.msg}")
                }
                floors.addAll(pageData.floors)
                progress("pages", p, totalPage, "正在下载第 $p/$totalPage 页")
                if (params.maxFloors > 0 &&
                    floors.count { it.lou != -1 } >= params.maxFloors
                ) {
                    break
                }
            }
            checkCancel()
            progress("format", 0, 0, "正在写入原生书…")
            val valid = validFloors(floors, params.maxFloors)
            val bookId = if (valid.isNotEmpty()) {
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
                    bookId = nativeBookId(nativeDir),
                )
                saveState(folderName, first.totalPage, valid, params)
                repository.registerNativeDir(nativeDir, params.tid)
                nativeBookId(nativeDir)
            } else {
                throw NgaHttpException("没有可用的楼层内容")
            }
            progress("done", totalPage, totalPage, "下载完成")
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
        val cfg = config.load()
        if (!cfg.configured) {
            throw NgaHttpException("请先配置 NGA Cookie")
        }
        val record = repository.recordOf(bookId) ?: throw NgaHttpException("书架中找不到该书")
        val nativeDir = File(record.path)
        if (!NativeBookWriter.isNativeDir(nativeDir)) {
            throw NgaHttpException("仅支持更新 NGA 下载的原生书")
        }
        val folderName = nativeDir.parentFile.name
        val state = loadState(folderName, params.tid, params.authorId)
        val client = NgaClient(cookieUid = cfg.uid, cookieCid = cfg.cid, userAgent = cfg.ua, baseUrl = cfg.baseUrl)
        val startPage = state.max_page
        progress("pages", startPage, startPage, "正在检查更新…")
        val first = client.fetchPageFull(params.tid, startPage.coerceAtLeast(1), params.authorId)
        if (first.code != 0) {
            throw NgaHttpException("NGA 返回代码不为 0：${first.code} ${first.msg}")
        }
        val totalPage = first.totalPage.coerceAtLeast(1)
        if (totalPage <= startPage) {
            progress("done", totalPage, totalPage, "已是最新")
            return 0
        }
        val newFloors = mutableListOf<NativeFloor>()
        newFloors.addAll(first.floors)
        for (p in (startPage + 1)..totalPage) {
            checkCancel()
            val pageData = client.fetchPageFull(params.tid, p, params.authorId)
            if (pageData.code != 0) {
                throw NgaHttpException("NGA 返回代码不为 0：${pageData.code} ${pageData.msg}")
            }
            newFloors.addAll(pageData.floors)
            progress("pages", p, totalPage, "正在下载第 $p/$totalPage 页")
            if (params.maxFloors > 0 &&
                validFloors(newFloors, params.maxFloors).size >= params.maxFloors
            ) {
                break
            }
        }
        checkCancel()
        progress("format", 0, 0, "正在追加楼层…")
        val valid = validFloors(newFloors, params.maxFloors)
        val added = NativeBookWriter.appendContainer(
            ngaLibraryRoot = appPaths.ngaLibraryDir,
            folderName = folderName,
            newFloors = valid,
            perChapter = params.perChapter,
            imageMode = params.imageMode,
            theme = params.theme,
        )
        saveState(folderName, totalPage, valid, params.copy(tid = params.tid))
        progress("done", totalPage, totalPage, if (added > 0) "已更新 $added 楼" else "已是最新")
        return added
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
