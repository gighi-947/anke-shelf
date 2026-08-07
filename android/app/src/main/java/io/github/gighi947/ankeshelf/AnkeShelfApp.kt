package io.github.gighi947.ankeshelf

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口：安装崩溃日志与调试期内存泄漏检测。
 * 数据目录约定：filesDir/AnkeShelf/（书架/进度/设置/标注/统计/NGA 库）。
 */
class AnkeShelfApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // LeakCanary 2.x 在 debug 构建中通过 ContentProvider 自动安装，无需手动调用。
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(filesDir, "AnkeShelf/logs").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "crash-$stamp.log").writeText(throwable.stackTraceToString())
                Log.e(TAG, "uncaught exception", throwable)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private companion object {
        const val TAG = "AnkeShelf"
    }
}
