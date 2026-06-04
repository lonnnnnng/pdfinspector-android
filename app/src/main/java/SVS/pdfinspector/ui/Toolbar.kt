package SVS.pdfinspector.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InspectorToolbar(
    tool: Tool,
    hasDocument: Boolean,
    pageIndex: Int,
    pageCount: Int,
    dirty: Boolean,
    onTool: (Tool) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
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
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "PDF Inspector",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ToolDivider()

            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = tool == Tool.PAN,
                    onClick = { onTool(Tool.PAN) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = {},
                ) {
                    Icon(Icons.Filled.PanTool, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pan")
                }
                SegmentedButton(
                    selected = tool == Tool.SELECT,
                    onClick = { onTool(Tool.SELECT) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = {},
                ) {
                    Icon(Icons.Filled.HighlightAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Select")
                }
            }

            if (hasDocument && pageCount > 0) {
                ToolDivider()
                IconButton(onClick = onPrev, enabled = pageIndex > 0) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
                }
                Text("${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
                }
            }

            ToolDivider()
            FilledTonalButton(onClick = onOpen) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open")
            }
            IconButton(onClick = onSave, enabled = dirty) {
                Icon(Icons.Filled.Save, contentDescription = "Save a copy")
            }
        }
    }
}

@Composable
private fun ToolDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(28.dp)
            .padding(horizontal = 2.dp),
    )
}
