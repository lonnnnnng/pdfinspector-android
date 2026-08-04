package com.loooong.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val notes: String,
    val releaseUrl: String,
    val downloadUrl: String?,
)

sealed class UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult()
    data class UpToDate(val release: ReleaseInfo) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/lonnnnnng/pdfinspector-android/releases/latest"

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease()
            if (isVersionNewer(release.tagName, currentVersion)) {
                UpdateCheckResult.Available(release)
            } else {
                UpdateCheckResult.UpToDate(release)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val message = t.message.orEmpty()
            UpdateCheckResult.Failed(
                when {
                    message == "未找到已发布的版本" -> message
                    message.startsWith("GitHub 返回 HTTP ") -> message
                    else -> "无法检查更新，请检查网络连接后重试"
                },
            )
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Reader-Android")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val message = if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    "未找到已发布的版本"
                } else {
                    "GitHub 返回 HTTP $status"
                }
                error(message)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseRelease(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseRelease(json: String): ReleaseInfo {
        val root = JSONObject(json)
        val tag = root.getString("tag_name").trim()
        val releaseUrl = root.getString("html_url")
        val assets = root.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val fileName = asset.optString("name")
                val contentType = asset.optString("content_type")
                if (fileName.endsWith(".apk", ignoreCase = true) ||
                    contentType == "application/vnd.android.package-archive"
                ) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
        }
        return ReleaseInfo(
            tagName = tag,
            name = root.optionalText("name").takeIf { it.isNotBlank() } ?: tag,
            notes = root.optionalText("body"),
            releaseUrl = releaseUrl,
            downloadUrl = apkUrl,
        )
    }
}

private fun JSONObject.optionalText(key: String): String =
    if (isNull(key)) "" else optString(key, "")

// GitHub release 标签和应用 versionName 都按 SemVer 数字段比较；v 前缀和预发布后缀不影响主版本判断。
internal fun isVersionNewer(latest: String, current: String): Boolean {
    val latestParts = versionParts(latest) ?: return false
    val currentParts = versionParts(current) ?: return false
    val size = maxOf(latestParts.size, currentParts.size)
    for (index in 0 until size) {
        val left = latestParts.getOrElse(index) { 0 }
        val right = currentParts.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

private fun versionParts(value: String): List<Int>? {
    val match = Regex("""(?i)^v?(\d+(?:\.\d+)*)""").find(value.trim()) ?: return null
    return match.groupValues[1].split('.').mapNotNull(String::toIntOrNull).takeIf { it.isNotEmpty() }
}
