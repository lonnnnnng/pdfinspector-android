package SVS.pdfinspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import SVS.pdfinspector.engine.DrawNode
import SVS.pdfinspector.engine.NodeKind
import SVS.pdfinspector.engine.ParsedPage

private class TreeRow(
    val node: DrawNode,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

@Composable
fun InspectorPane(
    page: ParsedPage,
    expanded: Set<Int>,
    selectedId: Int?,
    showRaw: Boolean,
    canDelete: Boolean,
    onSelect: (Int) -> Unit,
    onToggleExpand: (Int) -> Unit,
    onToggleRaw: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Inspector", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            FilterChip(
                selected = showRaw,
                onClick = onToggleRaw,
                label = { Text(if (showRaw) "Raw" else "Friendly") },
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = onDelete, enabled = canDelete) { Text("Delete") }
        }
        HorizontalDivider()

        val rows = remember(page, expanded) { flatten(page.root, expanded) }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(rows, key = { it.node.id }) { row ->
                TreeRowItem(
                    row = row,
                    selected = row.node.id == selectedId,
                    showRaw = showRaw,
                    onSelect = onSelect,
                    onToggleExpand = onToggleExpand,
                )
            }
        }
    }
}

private fun flatten(root: DrawNode, expanded: Set<Int>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun walk(node: DrawNode, depth: Int) {
        for (child in node.children) {
            val hasChildren = child.children.isNotEmpty()
            val isOpen = child.id in expanded
            out.add(TreeRow(child, depth, hasChildren, isOpen))
            if (hasChildren && isOpen) walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    selected: Boolean,
    showRaw: Boolean,
    onSelect: (Int) -> Unit,
    onToggleExpand: (Int) -> Unit,
) {
    val node = row.node
    val background =
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable { onSelect(node.id) }
            .padding(start = (8 + row.depth * 16).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (row.hasChildren) {
                Text(
                    text = if (row.expanded) "▾" else "▸",
                    modifier = Modifier.clickable { onToggleExpand(node.id) },
                )
            }
        }
        KindBadge(node.kind)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = if (showRaw) node.raw else node.detail
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (showRaw) FontFamily.Monospace else FontFamily.Default,
                    maxLines = if (showRaw) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        node.colorArgb?.let { argb ->
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(argb)),
            )
        }
    }
}

@Composable
private fun KindBadge(kind: NodeKind) {
    val (text, color) = when (kind) {
        NodeKind.GROUP -> "{}" to Color(0xFF6B7280)
        NodeKind.TEXT -> "T" to Color(0xFF2563EB)
        NodeKind.PATH -> "◑" to Color(0xFF9333EA)
        NodeKind.IMAGE -> "▣" to Color(0xFF0B6E4F)
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
