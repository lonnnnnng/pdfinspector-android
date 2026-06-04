package SVS.pdfinspector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.AspectRatio
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.DeviceFloppy
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
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(60.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                    .padding(start = 6.dp, end = 8.dp),
            )

            FitMenuButton(onFitWidth = onFitWidth, onFitHeight = onFitHeight)
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (fullscreen) TablerIcons.Minimize else TablerIcons.Maximize,
                    contentDescription = "Toggle full screen",
                    modifier = Modifier.size(20.dp),
                    tint = if (fullscreen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ToolDivider()
            IconButton(onClick = onPrev, enabled = pageIndex > 0) {
                Icon(TablerIcons.ChevronLeft, "Previous page", Modifier.size(20.dp))
            }
            Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
                Icon(TablerIcons.ChevronRight, "Next page", Modifier.size(20.dp))
            }

            ToolDivider()
            IconButton(onClick = onOpen) {
                Icon(TablerIcons.Folder, "Open a PDF", Modifier.size(20.dp))
            }
            IconButton(
                onClick = onSave,
                enabled = dirty,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(TablerIcons.DeviceFloppy, "Save a copy", Modifier.size(20.dp))
            }
            IconButton(onClick = onSettings) {
                Icon(TablerIcons.Settings, "Settings", Modifier.size(20.dp))
            }
        }
    }
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
