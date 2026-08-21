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
import io.github.gighi947.ankeshelf.AnkeShelfApp
import io.github.gighi947.ankeshelf.MainActivity
import io.github.gighi947.ankeshelf.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 骨碌碌任务全局状态（UI 轮询用，与 NgaServiceStatus 同款约定）。 */
object GululuServiceStatus {
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
    var error: String = ""

    @Volatile
    var bookId: String = ""

    @Volatile
    var sourceId: Int = 0

    @Volatile
    var taskId: String = ""
}

/**
 * 骨碌碌导入前台服务：单飞任务、进度通知、可取消。
 * 对齐桌面 `GululuService.start / cancel / status`（Windows 用后台线程 + 状态轮询，
 * Android 必须前台服务才能在后台稳定跑网络任务）。
 */
class GululuImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importer: GululuImporter? = null
    private var updater: GululuUpdater? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                importer?.cancel()
                updater?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (GululuServiceStatus.running) return START_NOT_STICKY
                val sourceId = intent.getIntExtra("sourceId", 0)
                if (sourceId <= 0) {
                    GululuServiceStatus.error = "无效的骨碌碌书籍 ID"
                    return START_NOT_STICKY
                }
                val mode = GululuImageMode.fromWire(intent.getStringExtra("imageMode"))
                if (mode == null) {
                    GululuServiceStatus.error = GululuImageMode.INVALID_MESSAGE
                    return START_NOT_STICKY
                }
                val action = intent.getStringExtra("action") ?: "import"
                startAsForeground()
                scope.launch {
                    if (action == "update") runUpdate(sourceId, mode) else runImport(sourceId, mode)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runImport(sourceId: Int, mode: GululuImageMode) {
        val container = (application as AnkeShelfApp).container
        val importer = GululuImporter(container.appPaths, container.repository)
        this.importer = importer
        val taskId = "gululu-import-$sourceId-${System.currentTimeMillis()}"
        importer.taskId = taskId
        GululuServiceStatus.running = true
        GululuServiceStatus.error = ""
        GululuServiceStatus.bookId = ""
        GululuServiceStatus.sourceId = sourceId
        GululuServiceStatus.taskId = taskId
        importer.setListener { stage, current, total, detail ->
            GululuServiceStatus.stage = stage
            GululuServiceStatus.current = current
            GululuServiceStatus.total = total
            GululuServiceStatus.detail = detail
            updateNotification(detail.ifEmpty { "正在导入…" })
        }
        var finalText = "任务结束"
        try {
            when (val result = importer.import(sourceId, mode)) {
                is GululuImportResult.Ok -> {
                    GululuServiceStatus.stage = "done"
                    GululuServiceStatus.bookId = result.bookId
                    val extra = if (result.imageFailed > 0) {
                        "；${result.imageFailed} 张图片失败已显示占位"
                    } else {
                        ""
                    }
                    GululuServiceStatus.detail = "导入完成$extra"
                    finalText = GululuServiceStatus.detail
                }
                GululuImportResult.Cancelled -> {
                    GululuServiceStatus.stage = "cancelled"
                    GululuServiceStatus.detail = "已取消"
                    finalText = "已取消"
                }
                is GululuImportResult.Err -> {
                    GululuServiceStatus.stage = "error"
                    GululuServiceStatus.error = result.message
                    GululuServiceStatus.detail = ""
                    finalText = "导入失败：${result.message}"
                }
            }
        } finally {
            GululuServiceStatus.running = false
            GululuServiceStatus.taskId = ""
            stopForeground(STOP_FOREGROUND_REMOVE)
            postFinalNotification(finalText)
            stopSelf()
        }
    }

    /** 热更新：与导入共用状态与通知，决策全在 [GululuUpdater]。 */
    private fun runUpdate(sourceId: Int, mode: GululuImageMode) {
        val container = (application as AnkeShelfApp).container
        val importer = GululuImporter(container.appPaths, container.repository)
        val updater = GululuUpdater(container.appPaths, container.repository, importer)
        this.importer = importer
        this.updater = updater
        val taskId = "gululu-update-$sourceId-${System.currentTimeMillis()}"
        updater.taskId = taskId
        GululuServiceStatus.running = true
        GululuServiceStatus.error = ""
        GululuServiceStatus.sourceId = sourceId
        GululuServiceStatus.taskId = taskId
        updater.setListener { stage, current, total, detail ->
            GululuServiceStatus.stage = stage
            GululuServiceStatus.current = current
            GululuServiceStatus.total = total
            GululuServiceStatus.detail = detail
            updateNotification(detail.ifEmpty { "正在检查更新…" })
        }
        var finalText = "任务结束"
        try {
            when (val result = updater.update(sourceId, mode)) {
                is GululuUpdateResult.UpToDate -> {
                    GululuServiceStatus.stage = "done"
                    GululuServiceStatus.detail = if (result.baselineInitialized) {
                        "已是最新；已建立增量基线"
                    } else {
                        "已是最新"
                    }
                    finalText = GululuServiceStatus.detail
                }
                is GululuUpdateResult.Updated -> {
                    GululuServiceStatus.stage = "done"
                    GululuServiceStatus.bookId = result.bookId
                    GululuServiceStatus.detail = if (result.newCount > 0) {
                        "已更新 ${result.newCount} 楼"
                    } else {
                        "已更新图片模式"
                    }
                    finalText = GululuServiceStatus.detail
                }
                GululuUpdateResult.Cancelled -> {
                    GululuServiceStatus.stage = "cancelled"
                    GululuServiceStatus.detail = "已取消"
                    finalText = "已取消"
                }
                is GululuUpdateResult.Err -> {
                    GululuServiceStatus.stage = "error"
                    GululuServiceStatus.error = result.message
                    GululuServiceStatus.detail = ""
                    finalText = "更新失败：${result.message}"
                }
            }
        } finally {
            GululuServiceStatus.running = false
            GululuServiceStatus.taskId = ""
            stopForeground(STOP_FOREGROUND_REMOVE)
            postFinalNotification(finalText)
            stopSelf()
        }
    }

    private fun startAsForeground() {        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("正在读取书籍信息…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "骨碌碌导入", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            10,
            Intent(this, GululuImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            11,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("安科书架 · 骨碌碌导入")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "取消", cancelIntent)
        if (GululuServiceStatus.total > 0) {
            builder.setProgress(GululuServiceStatus.total, GululuServiceStatus.current, false)
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun postFinalNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            this,
            12,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("安科书架 · 骨碌碌导入")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.gighi947.ankeshelf.GULULU_IMPORT_START"
        const val ACTION_CANCEL = "io.github.gighi947.ankeshelf.GULULU_IMPORT_CANCEL"
        private const val CHANNEL_ID = "gululu_import"
        private const val NOTIFICATION_ID = 2101
    }
}
