package SVS.pdfinspector.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import compose.icons.tablericons.Bug
import compose.icons.tablericons.Bulb
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.CloudDownload
import compose.icons.tablericons.ExternalLink
import compose.icons.tablericons.Heart
import compose.icons.tablericons.Refresh
import SVS.pdfinspector.BuildConfig
import SVS.pdfinspector.ReleaseInfo
import SVS.pdfinspector.UpdateCheckResult
import SVS.pdfinspector.UpdateChecker
import SVS.pdfinspector.ui.theme.Accent
import SVS.pdfinspector.ui.theme.Palettes
import SVS.pdfinspector.ui.theme.ThemeMode
import SVS.pdfinspector.ui.theme.ThemeState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(theme: ThemeState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    val checking = updateState is UpdateUiState.Checking
    val checkUpdates = {
        if (!checking) {
            scope.launch {
                updateState = UpdateUiState.Checking
                updateState = when (val result = UpdateChecker.check(BuildConfig.VERSION_NAME)) {
                    is UpdateCheckResult.Available -> UpdateUiState.Available(result.release)
                    is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.release.tagName)
                    is UpdateCheckResult.Failed -> UpdateUiState.Failed(result.message)
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(TablerIcons.ChevronLeft, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
            item { SectionLabel("Appearance") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = ThemeMode.entries
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = theme.mode == mode,
                                onClick = { theme.updateMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            ) { Text(mode.label) }
                        }
                    }
                }
            }

            val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (dynamicAvailable) {
                item {
                    ListItem(
                        headlineContent = { Text("Dynamic color") },
                        supportingContent = { Text("Match colors to your wallpaper") },
                        trailingContent = {
                            Switch(
                                checked = theme.dynamic,
                                onCheckedChange = theme::updateDynamic,
                            )
                        },
                    )
                }
            }

            item {
                val accentEnabled = !(theme.dynamic && dynamicAvailable)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Accent",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.alpha(if (accentEnabled) 1f else 0.5f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Accent.entries.forEach { accent ->
                            Swatch(
                                label = accent.label,
                                color = Palettes.swatch(accent),
                                selected = theme.accent == accent,
                                enabled = accentEnabled,
                                onClick = { theme.updateAccent(accent) },
                            )
                        }
                    }
                    if (!accentEnabled) {
                        Text(
                            "Turn off dynamic color to pick an accent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            item { SectionLabel("Updates") }
            item {
                ListItem(
                    modifier = Modifier.clickable(enabled = !checking, onClick = checkUpdates),
                    headlineContent = { Text("Check for updates") },
                    supportingContent = { UpdateStatus(updateState) },
                    leadingContent = {
                        Icon(TablerIcons.CloudDownload, contentDescription = null)
                    },
                    trailingContent = {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = checkUpdates) {
                                Icon(TablerIcons.Refresh, contentDescription = "Check now")
                            }
                        }
                    },
                )
            }

            val available = updateState as? UpdateUiState.Available
            if (available != null) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(available.release.name, style = MaterialTheme.typography.titleMedium)
                        if (available.release.notes.isNotBlank()) {
                            Text(
                                available.release.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                uriHandler.openUri(
                                    available.release.downloadUrl ?: available.release.releaseUrl,
                                )
                            },
                        ) {
                            Icon(TablerIcons.ExternalLink, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (available.release.downloadUrl != null) "Download APK" else "View release")
                        }
                    }
                }
            }
                item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
                item { SectionLabel("Support") }
                item {
                    SupportItem(
                        icon = TablerIcons.Bulb,
                        title = "Request a feature",
                        subtitle = "Suggest an improvement on GitHub",
                        onClick = { uriHandler.openUri(FEATURE_URL) },
                    )
                }
                item {
                    SupportItem(
                        icon = TablerIcons.Bug,
                        title = "Report a bug",
                        subtitle = "Open a bug report on GitHub",
                        onClick = { uriHandler.openUri(BUG_URL) },
                    )
                }
                item {
                    SupportItem(
                        icon = TablerIcons.Heart,
                        title = "Sponsor the original author",
                        subtitle = "Support the upstream PDF Inspector project",
                        onClick = { uriHandler.openUri(SPONSOR_URL) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Icon(
                TablerIcons.ExternalLink,
                contentDescription = "Open in browser",
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun UpdateStatus(state: UpdateUiState) {
    val color = if (state is UpdateUiState.Failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (state) {
        UpdateUiState.Idle -> "Current version ${BuildConfig.VERSION_NAME}"
        UpdateUiState.Checking -> "Contacting GitHub"
        is UpdateUiState.UpToDate -> "Up to date · ${state.latestVersion}"
        is UpdateUiState.Available -> "${state.release.tagName} is available"
        is UpdateUiState.Failed -> state.message
    }
    Text(text, color = color)
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val latestVersion: String) : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

@Composable
private fun Swatch(
    label: String,
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
            .semantics {
                contentDescription = "$label accent"
                this.selected = selected
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = TablerIcons.Check,
                contentDescription = null,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
    }
}

private const val FEATURE_URL =
    "https://github.com/lonnnnnng/pdfinspector-android/issues/new?labels=enhancement"
private const val BUG_URL = "https://github.com/lonnnnnng/pdfinspector-android/issues/new?labels=bug"
private const val SPONSOR_URL = "https://github.com/sponsors/shardulvs"
