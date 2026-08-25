package io.github.gighi947.ankeshelf.service

import io.github.gighi947.ankeshelf.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** 版本更新检查：拉取 GitHub Releases，网络失败静默返回无更新。 */
object UpdateChecker {

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val htmlUrl: String,
    )

    fun check(okHttp: OkHttpClient): UpdateInfo {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/gighi-947/anke-shelf/releases?per_page=20")
                .header("User-Agent", "AnkeShelf")
                .header("Accept", "application/vnd.github+json")
                .build()
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return UpdateInfo(false, "", "")
                val body = resp.body?.string() ?: return UpdateInfo(false, "", "")
                val releases = JSONArray(body)
                val current = parseVersion(BuildConfig.VERSION_NAME) ?: return UpdateInfo(false, "", "")
                for (i in 0 until releases.length()) {
                    val release = releases.optJSONObject(i) ?: continue
                    if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue
                    val tag = release.optString("tag_name", "")
                    if (!tag.startsWith("android-v")) continue
                    val latest = parseVersion(tag.removePrefix("android-v")) ?: continue
                    if (isNewer(latest, current)) {
                        return UpdateInfo(
                            hasUpdate = true,
                            latestVersion = "android-v" + latest.joinToString("."),
                            htmlUrl = release.optString("html_url", ""),
                        )
                    }
                    return UpdateInfo(false, "", "")
                }
                UpdateInfo(false, "", "")
            }
        } catch (_: Exception) {
            UpdateInfo(false, "", "")
        }
    }

    private fun isNewer(latest: List<Int>, current: List<Int>): Boolean {
        val n = maxOf(latest.size, current.size)
        for (i in 0 until n) {
            val a = latest.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }

    private fun parseVersion(value: String): List<Int>? {
        val s = value.trim().removePrefix("v").removePrefix("V")
        val parts = s.split(".").take(3)
        if (parts.isEmpty()) return null
        val result = mutableListOf<Int>()
        for (part in parts) {
            val n = part.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
            result.add(n)
        }
        return result
    }
}
