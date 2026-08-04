package com.loooong.reader

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import java.net.URI

data class PendingUpdateDownload(
    val downloadId: Long,
    val tagName: String,
)

sealed interface UpdateDownloadSnapshot {
    data class Waiting(
        val progressPercent: Int?,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val message: String,
    ) : UpdateDownloadSnapshot

    data object Ready : UpdateDownloadSnapshot
    data class Failed(val message: String) : UpdateDownloadSnapshot
    data object Missing : UpdateDownloadSnapshot
}

sealed interface UpdateInstallResult {
    data object Started : UpdateInstallResult
    data class Failed(val message: String) : UpdateInstallResult
}

class OnlineUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun restorePending(currentVersion: String): PendingUpdateDownload? {
        val pending = storedPending() ?: return null
        if (!isVersionNewer(pending.tagName, currentVersion)) {
            // long: 应用已经升级到目标版本后清理旧 APK，避免设置页继续提示安装已完成的版本。
            downloadManager.remove(pending.downloadId)
            clearPending()
            return null
        }
        return pending
    }

    fun startDownload(release: ReleaseInfo): PendingUpdateDownload {
        val downloadUrl = requireNotNull(release.downloadUrl) { "该版本没有可下载的 APK" }
        require(URI(downloadUrl).scheme.equals("https", ignoreCase = true)) { "更新下载地址不安全" }
        storedPending()?.let { downloadManager.remove(it.downloadId) }

        val fileName = updateApkFileName(downloadUrl, release.tagName)
        val relativePath = "updates/${System.currentTimeMillis()}-$fileName"
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("阅读 ${release.tagName}")
            .setDescription("正在下载应用更新")
            .setMimeType(APK_MIME_TYPE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, relativePath)

        val pending = PendingUpdateDownload(
            downloadId = downloadManager.enqueue(request),
            tagName = release.tagName,
        )
        preferences.edit {
            putLong(KEY_DOWNLOAD_ID, pending.downloadId)
            putString(KEY_TAG_NAME, pending.tagName)
        }
        return pending
    }

    fun query(pending: PendingUpdateDownload): UpdateDownloadSnapshot {
        val query = DownloadManager.Query().setFilterById(pending.downloadId)
        return downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use UpdateDownloadSnapshot.Missing
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadSnapshot.Ready
                DownloadManager.STATUS_FAILED -> UpdateDownloadSnapshot.Failed(
                    updateDownloadFailureMessage(reason),
                )
                DownloadManager.STATUS_PAUSED -> UpdateDownloadSnapshot.Waiting(
                    progressPercent = updateProgressPercent(downloaded, total),
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    message = "下载已暂停，等待网络恢复",
                )
                DownloadManager.STATUS_RUNNING -> UpdateDownloadSnapshot.Waiting(
                    progressPercent = updateProgressPercent(downloaded, total),
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    message = "正在下载更新",
                )
                else -> UpdateDownloadSnapshot.Waiting(
                    progressPercent = updateProgressPercent(downloaded, total),
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    message = "等待开始下载",
                )
            }
        } ?: UpdateDownloadSnapshot.Missing
    }

    fun canRequestPackageInstalls(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }

    fun install(pending: PendingUpdateDownload): UpdateInstallResult {
        if (query(pending) !is UpdateDownloadSnapshot.Ready) {
            return UpdateInstallResult.Failed("APK 尚未下载完成")
        }
        if (!canRequestPackageInstalls()) {
            return UpdateInstallResult.Failed("请先允许阅读安装未知来源应用")
        }

        val apkUri = downloadManager.getUriForDownloadedFile(pending.downloadId)
            ?: return UpdateInstallResult.Failed("无法读取已下载的 APK")
        val packageInstaller = appContext.packageManager.packageInstaller
        var sessionId: Int? = null
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appContext.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
            }
            sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                appContext.contentResolver.openInputStream(apkUri).use { input ->
                    requireNotNull(input) { "无法打开已下载的 APK" }
                    session.openWrite("base.apk", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                // long: PackageInstaller 通过可变 PendingIntent 回填安装状态，接收器再负责拉起系统确认页。
                val statusIntent = Intent(appContext, UpdateInstallReceiver::class.java).apply {
                    action = ACTION_UPDATE_INSTALL_STATUS
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val statusSender = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    statusIntent,
                    flags,
                ).intentSender
                session.commit(statusSender)
            }
            UpdateInstallResult.Started
        } catch (t: Throwable) {
            sessionId?.let { runCatching { packageInstaller.abandonSession(it) } }
            UpdateInstallResult.Failed(t.message?.takeIf { it.isNotBlank() } ?: "无法启动系统安装器")
        }
    }

    fun clearFailedDownload(pending: PendingUpdateDownload) {
        downloadManager.remove(pending.downloadId)
        clearPending()
    }

    private fun storedPending(): PendingUpdateDownload? {
        val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        val tagName = preferences.getString(KEY_TAG_NAME, null).orEmpty()
        return if (downloadId >= 0L && tagName.isNotBlank()) {
            PendingUpdateDownload(downloadId, tagName)
        } else {
            null
        }
    }

    private fun clearPending() {
        preferences.edit {
            remove(KEY_DOWNLOAD_ID)
            remove(KEY_TAG_NAME)
        }
    }

    private companion object {
        const val PREFS_NAME = "online_update"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_TAG_NAME = "tag_name"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_INSTALL_STATUS) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmation == null) {
                    Toast.makeText(context, "无法打开系统安装确认页", Toast.LENGTH_LONG).show()
                } else {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "更新安装完成", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Toast.makeText(context, "已取消安装更新", Toast.LENGTH_LONG).show()
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "系统安装器返回错误 $status"
                Toast.makeText(context, "安装失败：$message", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun updateProgressPercent(downloadedBytes: Long, totalBytes: Long): Int? =
    if (downloadedBytes >= 0L && totalBytes > 0L) {
        ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    } else {
        null
    }

internal fun updateDownloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "下载失败：设备存储空间不足"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载失败：存储设备不可用"
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "下载失败：更新文件已存在，请重试"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载失败：服务器重定向次数过多"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
    DownloadManager.ERROR_HTTP_DATA_ERROR,
    -> "下载失败：服务器连接异常"
    in 400..599 -> "下载失败：服务器返回 HTTP $reason"
    else -> "下载失败，请检查网络和存储空间后重试"
}

internal fun updateApkFileName(downloadUrl: String, tagName: String): String {
    val candidate = runCatching { URI(downloadUrl).path.substringAfterLast('/') }.getOrNull()
    val safeCandidate = candidate
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
    if (safeCandidate != null) return safeCandidate
    val safeTag = tagName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
    return "reader-$safeTag.apk"
}

internal const val ACTION_UPDATE_INSTALL_STATUS =
    "com.loooong.reader.action.UPDATE_INSTALL_STATUS"
