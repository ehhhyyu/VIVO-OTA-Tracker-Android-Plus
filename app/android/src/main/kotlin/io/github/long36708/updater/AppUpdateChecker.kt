package io.github.long36708.updater

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 应用更新信息（来自 GitHub Releases 最新发布）。 */
data class AppUpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    val releaseNotes: String
)

/** 单次自更新检查的结果。 */
sealed interface AppUpdateCheckResult {
    /** 网络失败（含限流）；调用方按触发来源决定是否提示。 */
    data object Failed : AppUpdateCheckResult

    /** 请求成功；info 为空表示当前已是最新版本。 */
    data class Success(val info: AppUpdateInfo?) : AppUpdateCheckResult
}

/**
 * 应用自更新检测：读取 GitHub Releases 的 latest 发布并与本地版本号比较。
 * /latest 端点天然排除 prerelease 与 draft。
 * 需在子线程调用；任何失败都被吞掉并返回 [AppUpdateCheckResult.Failed]，绝不影响主功能。
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    private const val API_URL =
        "https://api.github.com/repos/long36708/VIVO-OTA-Tracker-Android-Plus/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    fun check(): AppUpdateCheckResult {
        val raw = fetch() ?: return AppUpdateCheckResult.Failed
        return try {
            val json = JSONObject(raw)
            val tag = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            val url = json.optString("html_url")
            if (tag.isEmpty() || url.isEmpty()) return AppUpdateCheckResult.Failed
            val info = AppUpdateInfo(
                latestVersion = tag,
                releaseUrl = url,
                releaseNotes = json.optString("body")
            )
            if (isNewer(tag, AndroidAppContext.versionName)) {
                AppUpdateCheckResult.Success(info)
            } else {
                AppUpdateCheckResult.Success(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check parse failed", e)
            AppUpdateCheckResult.Failed
        }
    }

    private fun fetch(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // GitHub API 要求 UA；Accept 显式声明 JSON 版本
                setRequestProperty("User-Agent", "VivoOtaTracker-Plus")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "GitHub releases HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Update check download failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 逐段数字比较版本号（x.y.z）；段数不齐按 0 补齐，非数字段视为 0。相等返回 false。 */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parseSegments(remote)
        val c = parseSegments(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parseSegments(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
}
