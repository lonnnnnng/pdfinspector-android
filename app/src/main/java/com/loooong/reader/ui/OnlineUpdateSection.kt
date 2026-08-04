package com.loooong.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.CloudDownload
import compose.icons.tablericons.ExternalLink
import compose.icons.tablericons.Refresh
import com.loooong.reader.BuildConfig
import com.loooong.reader.OnlineUpdateManager
import com.loooong.reader.PendingUpdateDownload
import com.loooong.reader.ReleaseInfo
import com.loooong.reader.UpdateCheckResult
import com.loooong.reader.UpdateChecker
import com.loooong.reader.UpdateDownloadSnapshot
import com.loooong.reader.UpdateInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun OnlineUpdateSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val manager = remember(context.applicationContext) { OnlineUpdateManager(context) }
    var state by remember { mutableStateOf<OnlineUpdateUiState>(OnlineUpdateUiState.Idle) }
    var activeDownload by remember { mutableStateOf<PendingUpdateDownload?>(null) }

    val beginInstall: (PendingUpdateDownload) -> Unit = { pending ->
        scope.launch {
            state = OnlineUpdateUiState.Installing(pending.tagName)
            state = when (val result = withContext(Dispatchers.IO) { manager.install(pending) }) {
                UpdateInstallResult.Started -> OnlineUpdateUiState.InstallRequested(pending.tagName)
                is UpdateInstallResult.Failed -> OnlineUpdateUiState.InstallFailed(
                    tagName = pending.tagName,
                    message = result.message,
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val pending = activeDownload ?: return@rememberLauncherForActivityResult
        scope.launch {
            state = OnlineUpdateUiState.CheckingInstallPermission(pending.tagName)
            // long: 授权页返回时 PackageManager 仍可能繁忙，避免 Binder 查询阻塞主线程并触发输入 ANR。
            val canInstall = runCatching {
                withContext(Dispatchers.IO) { manager.canRequestPackageInstalls() }
            }.getOrElse { error ->
                state = OnlineUpdateUiState.InstallFailed(
                    tagName = pending.tagName,
                    message = error.message?.takeIf { it.isNotBlank() } ?: "无法确认安装授权状态",
                )
                return@launch
            }
            if (canInstall) {
                beginInstall(pending)
            } else {
                state = OnlineUpdateUiState.PermissionRequired(pending.tagName)
            }
        }
    }

    val requestInstall: (PendingUpdateDownload) -> Unit = { pending ->
        scope.launch {
            state = OnlineUpdateUiState.CheckingInstallPermission(pending.tagName)
            // long: 安装权限检查和 APK 会话写入都可能触发磁盘或系统服务等待，整条准备链路均不得占用主线程。
            val canInstall = runCatching {
                withContext(Dispatchers.IO) { manager.canRequestPackageInstalls() }
            }.getOrElse { error ->
                state = OnlineUpdateUiState.InstallFailed(
                    tagName = pending.tagName,
                    message = error.message?.takeIf { it.isNotBlank() } ?: "无法确认安装授权状态",
                )
                return@launch
            }
            if (canInstall) {
                beginInstall(pending)
            } else {
                state = OnlineUpdateUiState.PermissionRequired(pending.tagName)
                runCatching { permissionLauncher.launch(manager.unknownSourcesSettingsIntent()) }
                    .onFailure {
                        state = OnlineUpdateUiState.InstallFailed(
                            tagName = pending.tagName,
                            message = "无法打开未知来源应用授权页面",
                        )
                    }
            }
        }
    }

    LaunchedEffect(Unit) {
        activeDownload = withContext(Dispatchers.IO) {
            manager.restorePending(BuildConfig.VERSION_NAME)
        }
    }

    // long: 设置页重新进入后从持久化下载 ID 恢复查询，下载不依赖当前 Activity 一直存活。
    LaunchedEffect(activeDownload?.downloadId) {
        val pending = activeDownload ?: return@LaunchedEffect
        while (isActive) {
            when (val snapshot = withContext(Dispatchers.IO) { manager.query(pending) }) {
                is UpdateDownloadSnapshot.Waiting -> {
                    state = OnlineUpdateUiState.Downloading(
                        tagName = pending.tagName,
                        progressPercent = snapshot.progressPercent,
                        downloadedBytes = snapshot.downloadedBytes,
                        totalBytes = snapshot.totalBytes,
                        message = snapshot.message,
                    )
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                }
                UpdateDownloadSnapshot.Ready -> {
                    state = OnlineUpdateUiState.Ready(pending.tagName)
                    break
                }
                is UpdateDownloadSnapshot.Failed -> {
                    withContext(Dispatchers.IO) { manager.clearFailedDownload(pending) }
                    activeDownload = null
                    state = OnlineUpdateUiState.Failed(snapshot.message)
                    break
                }
                UpdateDownloadSnapshot.Missing -> {
                    withContext(Dispatchers.IO) { manager.clearFailedDownload(pending) }
                    activeDownload = null
                    state = OnlineUpdateUiState.Failed("找不到更新下载任务，请重新下载")
                    break
                }
            }
        }
    }

    val checkAllowed = when (state) {
        OnlineUpdateUiState.Idle,
        is OnlineUpdateUiState.UpToDate,
        is OnlineUpdateUiState.Available,
        is OnlineUpdateUiState.Failed,
        -> activeDownload == null
        else -> false
    }
    val checkUpdates = {
        if (checkAllowed) {
            scope.launch {
                state = OnlineUpdateUiState.Checking
                state = when (val result = UpdateChecker.check(BuildConfig.VERSION_NAME)) {
                    is UpdateCheckResult.Available -> OnlineUpdateUiState.Available(result.release)
                    is UpdateCheckResult.UpToDate -> OnlineUpdateUiState.UpToDate(result.release.tagName)
                    is UpdateCheckResult.Failed -> OnlineUpdateUiState.Failed(result.message)
                }
            }
        }
    }
    val startDownload: (ReleaseInfo) -> Unit = { release ->
        scope.launch {
            state = OnlineUpdateUiState.StartingDownload(release.tagName)
            runCatching {
                withContext(Dispatchers.IO) { manager.startDownload(release) }
            }.onSuccess { pending ->
                activeDownload = pending
                state = OnlineUpdateUiState.Downloading(
                    tagName = pending.tagName,
                    progressPercent = null,
                    downloadedBytes = 0L,
                    totalBytes = -1L,
                    message = "等待开始下载",
                )
            }.onFailure { error ->
                state = OnlineUpdateUiState.Failed(
                    error.message?.takeIf { it.isNotBlank() } ?: "无法创建更新下载任务",
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(enabled = checkAllowed, onClick = checkUpdates),
            headlineContent = { Text("在线更新") },
            supportingContent = { OnlineUpdateStatus(state) },
            leadingContent = { Icon(TablerIcons.CloudDownload, contentDescription = null) },
            trailingContent = {
                when (state) {
                    OnlineUpdateUiState.Checking,
                    is OnlineUpdateUiState.StartingDownload,
                    is OnlineUpdateUiState.CheckingInstallPermission,
                    is OnlineUpdateUiState.Installing,
                    -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else -> if (checkAllowed) {
                        IconButton(onClick = checkUpdates) {
                            Icon(TablerIcons.Refresh, contentDescription = "立即检查")
                        }
                    }
                }
            },
        )

        when (val current = state) {
            is OnlineUpdateUiState.Available -> UpdateAvailableDetails(
                release = current.release,
                onDownload = { startDownload(current.release) },
                onOpenRelease = { uriHandler.openUri(current.release.releaseUrl) },
            )
            is OnlineUpdateUiState.StartingDownload -> UpdateProgressDetails(
                title = "正在准备 ${current.tagName}",
                progressPercent = null,
                detail = "正在创建系统下载任务",
            )
            is OnlineUpdateUiState.Downloading -> UpdateProgressDetails(
                title = current.message,
                progressPercent = current.progressPercent,
                detail = formatUpdateProgress(current.downloadedBytes, current.totalBytes),
            )
            is OnlineUpdateUiState.Ready -> UpdateInstallDetails(
                title = "${current.tagName} 已下载完成",
                description = "安装过程由 Android 系统确认，不会静默替换应用。",
                buttonText = "安装更新",
                onClick = { activeDownload?.let(requestInstall) },
            )
            is OnlineUpdateUiState.PermissionRequired -> UpdateInstallDetails(
                title = "需要允许安装未知来源应用",
                description = "请允许阅读安装已下载的更新，然后返回继续。",
                buttonText = "授权并继续安装",
                onClick = { activeDownload?.let(requestInstall) },
            )
            is OnlineUpdateUiState.CheckingInstallPermission -> UpdateProgressDetails(
                title = "正在检查安装权限",
                progressPercent = null,
                detail = "正在确认系统是否允许阅读安装更新",
            )
            is OnlineUpdateUiState.Installing -> UpdateProgressDetails(
                title = "正在准备安装 ${current.tagName}",
                progressPercent = null,
                detail = "正在校验并提交给系统安装器",
            )
            is OnlineUpdateUiState.InstallRequested -> UpdateInstallDetails(
                title = "已打开系统安装器",
                description = "请按系统提示确认更新；取消后可以重新发起安装。",
                buttonText = "重新发起安装",
                onClick = { activeDownload?.let(requestInstall) },
            )
            is OnlineUpdateUiState.InstallFailed -> UpdateInstallDetails(
                title = "安装准备失败",
                description = current.message,
                buttonText = "重试安装",
                onClick = { activeDownload?.let(requestInstall) },
            )
            else -> Unit
        }
    }
}

@Composable
private fun UpdateAvailableDetails(
    release: ReleaseInfo,
    onDownload: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("版本 ${release.tagName}", style = MaterialTheme.typography.titleMedium)
        if (release.notes.isNotBlank()) {
            Text("版本说明", style = MaterialTheme.typography.labelLarge)
            Text(
                release.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(onClick = if (release.downloadUrl != null) onDownload else onOpenRelease) {
            Icon(
                if (release.downloadUrl != null) TablerIcons.CloudDownload else TablerIcons.ExternalLink,
                contentDescription = null,
            )
            Spacer(Modifier.size(8.dp))
            Text(if (release.downloadUrl != null) "下载并安装" else "查看版本")
        }
    }
}

@Composable
private fun UpdateProgressDetails(
    title: String,
    progressPercent: Int?,
    detail: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (progressPercent == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateInstallDetails(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onClick) {
            Icon(TablerIcons.CloudDownload, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(buttonText)
        }
    }
}

@Composable
private fun OnlineUpdateStatus(state: OnlineUpdateUiState) {
    val color = if (state is OnlineUpdateUiState.Failed || state is OnlineUpdateUiState.InstallFailed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (state) {
        OnlineUpdateUiState.Idle -> "当前版本 ${BuildConfig.VERSION_NAME}"
        OnlineUpdateUiState.Checking -> "正在连接 GitHub"
        is OnlineUpdateUiState.UpToDate -> "已是最新版本 · ${state.latestVersion}"
        is OnlineUpdateUiState.Available -> "发现新版本 ${state.release.tagName}"
        is OnlineUpdateUiState.StartingDownload -> "正在准备下载 ${state.tagName}"
        is OnlineUpdateUiState.Downloading -> state.progressPercent?.let {
            "正在下载 ${state.tagName} · $it%"
        } ?: "正在下载 ${state.tagName}"
        is OnlineUpdateUiState.Ready -> "更新 ${state.tagName} 已下载"
        is OnlineUpdateUiState.PermissionRequired -> "等待授予安装权限"
        is OnlineUpdateUiState.CheckingInstallPermission -> "正在检查安装权限"
        is OnlineUpdateUiState.Installing -> "正在准备安装 ${state.tagName}"
        is OnlineUpdateUiState.InstallRequested -> "等待系统确认安装 ${state.tagName}"
        is OnlineUpdateUiState.InstallFailed -> state.message
        is OnlineUpdateUiState.Failed -> state.message
    }
    Text(text, color = color)
}

private fun formatUpdateProgress(downloadedBytes: Long, totalBytes: Long): String {
    val downloaded = formatBytes(downloadedBytes)
    return if (totalBytes > 0L) "$downloaded / ${formatBytes(totalBytes)}" else downloaded
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 0L -> "正在获取文件大小"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

private sealed interface OnlineUpdateUiState {
    data object Idle : OnlineUpdateUiState
    data object Checking : OnlineUpdateUiState
    data class UpToDate(val latestVersion: String) : OnlineUpdateUiState
    data class Available(val release: ReleaseInfo) : OnlineUpdateUiState
    data class StartingDownload(val tagName: String) : OnlineUpdateUiState
    data class Downloading(
        val tagName: String,
        val progressPercent: Int?,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val message: String,
    ) : OnlineUpdateUiState
    data class Ready(val tagName: String) : OnlineUpdateUiState
    data class PermissionRequired(val tagName: String) : OnlineUpdateUiState
    data class CheckingInstallPermission(val tagName: String) : OnlineUpdateUiState
    data class Installing(val tagName: String) : OnlineUpdateUiState
    data class InstallRequested(val tagName: String) : OnlineUpdateUiState
    data class InstallFailed(val tagName: String, val message: String) : OnlineUpdateUiState
    data class Failed(val message: String) : OnlineUpdateUiState
}

private const val DOWNLOAD_POLL_INTERVAL_MS = 750L
