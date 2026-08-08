package io.github.gighi947.ankeshelf.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.gighi947.ankeshelf.MainActivity
import io.github.gighi947.ankeshelf.AnkeShelfApp
import io.github.gighi947.ankeshelf.R
import io.github.gighi947.ankeshelf.data.AppPaths
import io.github.gighi947.ankeshelf.data.NgaConfig
import io.github.gighi947.ankeshelf.data.Shelf
import io.github.gighi947.ankeshelf.data.ProgressStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** 下载服务全局状态（UI 轮询用）。 */
object NgaServiceStatus {
    @Volatile
    var running: Boolean = false

    @Volatile
    var stage: String = "idle"

    @Volatile
    var current: Int = 0

    @Volatile
    var total: Int = 0

    @Volatile
    var detail: String = ""

    @Volatile
    var bookId: String = ""

    @Volatile
    var error: String = ""

    @Volatile
    var action: String = "download"

    fun snapshot(): NgaProgress =
        NgaProgress(stage = stage, current = current, total = total, detail = detail)
}

/**
 * NGA 下载前台服务：单飞任务、进度通知、可取消；取消时清理本次新建的半成品目录。
 * 对齐桌面 NgaService.start / cancel / status。
 */
class NgaDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloader: NgaDownloader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                downloader?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (NgaServiceStatus.running) return START_NOT_STICKY
                val tid = intent.getLongExtra("tid", 0L)
                if (tid <= 0) {
                    NgaServiceStatus.error = "无效的帖子 id"
                    return START_NOT_STICKY
                }
                startAsForeground()
                val params = NgaDownloadParams(
                    tid = tid,
                    authorId = intent.getLongExtra("authorId", 0L),
                    maxFloors = intent.getIntExtra("maxFloors", 0),
                    imageMode = intent.getStringExtra("imageMode") ?: "online",
                    theme = intent.getStringExtra("theme") ?: "light",
                    perChapter = intent.getIntExtra("perChapter", 20).coerceIn(1, 200),
                    fullRedownload = intent.getBooleanExtra("fullRedownload", false),
                )
                val bookId = intent.getStringExtra("bookId") ?: ""
                val action = intent.getStringExtra("action") ?: "download"
                scope.launch {
                    runTask(params, bookId, action)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runTask(params: NgaDownloadParams, bookId: String, action: String) {
        // 与 UI 共享同一 AppContainer 的 Shelf/仓库，下载完成后书架内存立即更新。
        val container = (application as AnkeShelfApp).container
        val downloader = NgaDownloader(
            container.appPaths,
            container.repository,
            container.ngaConfig,
        )
        this.downloader = downloader
        NgaServiceStatus.running = true
        NgaServiceStatus.error = ""
        NgaServiceStatus.action = action
        NgaServiceStatus.bookId = bookId
        downloader.setListener { p ->
            NgaServiceStatus.stage = p.stage
            NgaServiceStatus.current = p.current
            NgaServiceStatus.total = p.total
            NgaServiceStatus.detail = p.detail
            updateNotification(p)
        }
        var finalText = "任务结束"
        try {
            if (action == "update") {
                // 更新默认参数回填：UI 未传时用最近一次下载设置 / meta（对齐桌面 update_defaults）。
                val d = downloader.defaultsFor(bookId)
                val effective = if (d != null) {
                    NgaDownloadParams(
                        tid = params.tid,
                        authorId = if (params.authorId > 0) params.authorId else d.authorId,
                        imageMode = if (params.imageMode in setOf("online", "embedded", "none")) {
                            params.imageMode
                        } else {
                            d.imageMode
                        },
                        theme = if (params.theme in setOf("light", "dark")) params.theme else d.theme,
                        perChapter = if (params.perChapter > 0) params.perChapter else d.perChapter,
                        maxFloors = 0,
                    )
                } else {
                    params
                }
                val added = downloader.update(bookId, effective)
                NgaServiceStatus.detail = if (added > 0) "已更新 $added 楼" else "已是最新"
                NgaServiceStatus.stage = "done"
                NgaServiceStatus.bookId = bookId
                finalText = NgaServiceStatus.detail
            } else {
                val newId = downloader.download(params)
                NgaServiceStatus.bookId = newId
                NgaServiceStatus.stage = "done"
                NgaServiceStatus.detail = "下载完成"
                finalText = "下载完成"
            }
            NgaServiceStatus.error = ""
        } catch (e: NgaCancelled) {
            NgaServiceStatus.stage = "cancelled"
            NgaServiceStatus.detail = "已取消"
            finalText = "已取消"
        } catch (e: Exception) {
            NgaServiceStatus.stage = "error"
            NgaServiceStatus.error = e.message ?: "下载失败"
            NgaServiceStatus.detail = ""
            finalText = "任务失败：${NgaServiceStatus.error}"
        } finally {
            NgaServiceStatus.running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            postFinalNotification(finalText)
            stopSelf()
        }
    }

    /** 任务结束后保留一条非持续通知，明确提示“已是最新 / 已更新 X 楼 / 失败”。 */
    private fun postFinalNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("安科书架 · NGA 任务")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startAsForeground() {
        createChannel()
        val notification = buildNotification(NgaProgress("pages", 0, 1, "正在初始化…"))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NGA 下载",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(p: NgaProgress): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, NgaDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (p.stage) {
            "pages" -> p.detail
            "format" -> "正在写入原生书…"
            "done" -> "下载完成"
            "cancelled" -> "已取消"
            else -> p.detail
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("安科书架 · NGA 下载")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(p.total.coerceAtLeast(1), p.current.coerceIn(0, p.total.coerceAtLeast(1)), p.total <= 0)
            .addAction(0, "取消", cancelIntent)
            .build()
    }

    private fun updateNotification(p: NgaProgress) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(p))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.gighi947.ankeshelf.action.NGA_START"
        const val ACTION_CANCEL = "io.github.gighi947.ankeshelf.action.NGA_CANCEL"
        private const val CHANNEL_ID = "nga_download"
        private const val NOTIFICATION_ID = 1001
    }
}
