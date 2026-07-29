package SVS.pdfinspector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBackUp
import compose.icons.tablericons.ArrowForwardUp
import compose.icons.tablericons.AspectRatio
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.Copy
import compose.icons.tablericons.DeviceFloppy
import compose.icons.tablericons.Dots
import compose.icons.tablericons.Folder
import compose.icons.tablericons.Maximize
import compose.icons.tablericons.Minimize
import compose.icons.tablericons.Settings

@Composable
fun InspectorToolbar(
    fileName: String,
    fullscreen: Boolean,
    pageIndex: Int,
    pageCount: Int,
    dirty: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    copyText: String?,
    onCopyText: () -> Unit,
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 720.dp
            Row(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = 4.dp),
                )
                ToolbarActions(
                    compact = compact,
                    fullscreen = fullscreen,
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    dirty = dirty,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    canCopy = copyText != null,
                    onCopyText = onCopyText,
                    onFitWidth = onFitWidth,
                    onFitHeight = onFitHeight,
                    onToggleFullscreen = onToggleFullscreen,
                    onPrev = onPrev,
                    onNext = onNext,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onOpen = onOpen,
                    onSave = onSave,
                    onSettings = onSettings,
                )
            }
        }
    }
}

@Composable
private fun ToolbarActions(
    compact: Boolean,
    fullscreen: Boolean,
    pageIndex: Int,
    pageCount: Int,
    dirty: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    canCopy: Boolean,
    onCopyText: () -> Unit,
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSettings: () -> Unit,
) {
    if (compact) {
        CompactToolbarActions(
            fullscreen = fullscreen,
            pageIndex = pageIndex,
            pageCount = pageCount,
            dirty = dirty,
            canUndo = canUndo,
            canRedo = canRedo,
            canCopy = canCopy,
            onCopyText = onCopyText,
            onFitWidth = onFitWidth,
            onFitHeight = onFitHeight,
            onToggleFullscreen = onToggleFullscreen,
            onPrev = onPrev,
            onNext = onNext,
            onUndo = onUndo,
            onRedo = onRedo,
            onOpen = onOpen,
            onSave = onSave,
            onSettings = onSettings,
        )
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (canCopy) {
            IconButton(onClick = onCopyText) {
                Icon(TablerIcons.Copy, "Copy text", Modifier.size(20.dp))
            }
        }
        IconButton(
            onClick = onSave,
            enabled = dirty,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(TablerIcons.DeviceFloppy, "Save a copy", Modifier.size(20.dp))
        }
        IconButton(onClick = onOpen) {
            Icon(TablerIcons.Folder, "Open a PDF", Modifier.size(20.dp))
        }
        ToolDivider()
        IconButton(onClick = onPrev, enabled = pageIndex > 0) {
            Icon(TablerIcons.ChevronLeft, "Previous page", Modifier.size(20.dp))
        }
        PageIndicator(pageIndex, pageCount)
        IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
            Icon(TablerIcons.ChevronRight, "Next page", Modifier.size(20.dp))
        }
        ToolDivider()
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(TablerIcons.ArrowBackUp, "Undo", Modifier.size(20.dp))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(TablerIcons.ArrowForwardUp, "Redo", Modifier.size(20.dp))
        }
        ToolDivider()
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                imageVector = if (fullscreen) TablerIcons.Minimize else TablerIcons.Maximize,
                contentDescription = if (fullscreen) "Exit full screen" else "Enter full screen",
                modifier = Modifier.size(20.dp),
                tint = if (fullscreen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FitMenuButton(onFitWidth = onFitWidth, onFitHeight = onFitHeight)
        IconButton(onClick = onSettings) {
            Icon(TablerIcons.Settings, "Settings", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CompactToolbarActions(
    fullscreen: Boolean,
    pageIndex: Int,
    pageCount: Int,
    dirty: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    canCopy: Boolean,
    onCopyText: () -> Unit,
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onSave,
            enabled = dirty,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(TablerIcons.DeviceFloppy, "Save a copy", Modifier.size(20.dp))
        }
        IconButton(onClick = onPrev, enabled = pageIndex > 0) {
            Icon(TablerIcons.ChevronLeft, "Previous page", Modifier.size(20.dp))
        }
        PageIndicator(pageIndex, pageCount)
        IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
            Icon(TablerIcons.ChevronRight, "Next page", Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(TablerIcons.Dots, "More actions", Modifier.size(20.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                fun run(action: () -> Unit) {
                    expanded = false
                    action()
                }
                DropdownMenuItem(text = { Text("Open PDF") }, onClick = { run(onOpen) })
                if (canCopy) {
                    DropdownMenuItem(text = { Text("Copy selected text") }, onClick = { run(onCopyText) })
                }
                DropdownMenuItem(text = { Text("Undo") }, enabled = canUndo, onClick = { run(onUndo) })
                DropdownMenuItem(text = { Text("Redo") }, enabled = canRedo, onClick = { run(onRedo) })
                DropdownMenuItem(text = { Text("Fit to width") }, onClick = { run(onFitWidth) })
                DropdownMenuItem(text = { Text("Fit to height") }, onClick = { run(onFitHeight) })
                DropdownMenuItem(
                    text = { Text(if (fullscreen) "Exit full screen" else "Enter full screen") },
                    onClick = { run(onToggleFullscreen) },
                )
                DropdownMenuItem(text = { Text("Settings") }, onClick = { run(onSettings) })
            }
        }
    }
}

@Composable
private fun PageIndicator(pageIndex: Int, pageCount: Int) {
    Text(
        text = "${pageIndex + 1} / $pageCount",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount" },
    )
}

@Composable
private fun FitMenuButton(onFitWidth: () -> Unit, onFitHeight: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(TablerIcons.AspectRatio, "Fit page", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Fit to width") },
                onClick = { expanded = false; onFitWidth() },
            )
            DropdownMenuItem(
                text = { Text("Fit to height") },
                onClick = { expanded = false; onFitHeight() },
            )
        }
    }
}

@Composable
private fun ToolDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(28.dp)
            .padding(horizontal = 4.dp),
    )
}
