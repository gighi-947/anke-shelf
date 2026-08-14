package io.github.gighi947.ankeshelf.service

import android.content.Context
import android.os.Build
import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.ui.reader.BridgeProtocol
import java.io.File

data class DataFileInfo(
    val name: String,
    val exists: Boolean,
    val size: Long,
    val version: Int?,
)

/**
 * Android 诊断报告：应用/系统/WebView 版本、桥版本、数据文件元信息（仅版本号，
 * 不含内容）、最近结构化事件、最近任务状态。
 *
 * 红线：绝不包含 Cookie、NGA 凭据、书籍正文或签名信息；[report] 为纯函数可单测，
 * [collect] 负责设备侧采集。
 */
object Diagnostics {
    private val VERSION_RE = Regex("\"(?:settings_version|version)\"\\s*:\\s*(-?\\d+)")

    fun report(
        appVersion: String,
        bridgeVersion: Int,
        system: Map<String, String>,
        dataFiles: List<DataFileInfo>,
        events: List<String>,
        taskState: String,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("AnkeShelf 诊断报告（不含 Cookie/凭据/书籍正文）")
        sb.appendLine("app_version=$appVersion")
        sb.appendLine("bridge_version=$bridgeVersion")
        system.forEach { (key, value) -> sb.appendLine("system.$key=$value") }
        sb.appendLine("task=$taskState")
        sb.appendLine("[data files]")
        dataFiles.forEach { f ->
            sb.appendLine("${f.name} exists=${f.exists} size=${f.size} version=${f.version ?: "?"}")
        }
        sb.appendLine("[recent events]")
        events.takeLast(50).forEach { sb.appendLine(it) }
        return sb.toString()
    }

    fun collect(context: Context, appPaths: AppPaths, appVersion: String): String = report(
        appVersion = appVersion,
        bridgeVersion = BridgeProtocol.VERSION,
        system = mapOf(
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "webview" to webViewVersion(context),
        ),
        dataFiles = listOf(
            "shelf.json" to appPaths.shelfFile,
            "progress.json" to appPaths.progressFile,
            "settings.json" to appPaths.settingsFile,
            "annotations.json" to appPaths.annotationsFile,
            "statistics.json" to appPaths.statisticsFile,
            "search_history.json" to appPaths.searchHistoryFile,
        ).map { (name, file) ->
            DataFileInfo(name, file.isFile, file.length(), readVersionOf(file))
        },
        events = LogEvents.snapshot(),
        taskState = buildString {
            append("stage=").append(NgaServiceStatus.stage)
            append(" running=").append(NgaServiceStatus.running)
            append(" task_id=").append(NgaServiceStatus.taskId.ifBlank { "-" })
            append(" detail=").append(NgaServiceStatus.detail)
            append(" error=").append(NgaServiceStatus.error)
            append(" book_id_hash=")
            append(
                if (NgaServiceStatus.bookId.isBlank()) "-"
                else LogEvents.bookIdHash(NgaServiceStatus.bookId),
            )
        },
    )

    private fun readVersionOf(file: File): Int? {
        if (!file.isFile) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        return VERSION_RE.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun webViewVersion(context: Context): String =
        listOf("com.google.android.webview", "com.android.webview")
            .firstNotNullOfOrNull { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
            } ?: "unknown"
}
