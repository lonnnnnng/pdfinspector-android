package com.loooong.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Code
import compose.icons.tablericons.Braces
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.Droplet
import compose.icons.tablericons.Edit
import compose.icons.tablericons.LayoutBottombar
import compose.icons.tablericons.LayoutSidebarRight
import compose.icons.tablericons.LetterT
import compose.icons.tablericons.Photo
import compose.icons.tablericons.Trash
import compose.icons.tablericons.VectorBeizer
import com.loooong.reader.engine.DrawNode
import com.loooong.reader.engine.NodeKind
import com.loooong.reader.engine.ParsedPage

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
    swatchColors: Map<Int, Int>,
    revealTick: Int,
    showRaw: Boolean,
    canDelete: Boolean,
    dock: Dock,
    transparent: Boolean,
    onSelect: (Int) -> Unit,
    onToggleExpand: (Int) -> Unit,
    onToggleRaw: () -> Unit,
    onToggleDock: () -> Unit,
    onToggleTransparent: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "元素检查器",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${page.leaves.size} 个元素",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconToggleButton(checked = showRaw, onCheckedChange = { onToggleRaw() }) {
                Icon(TablerIcons.Code, "切换原始运算符", Modifier.size(20.dp))
            }
            IconButton(onClick = onToggleDock) {
                Icon(
                    imageVector = if (dock == Dock.BOTTOM) TablerIcons.LayoutSidebarRight else TablerIcons.LayoutBottombar,
                    contentDescription = "切换面板停靠位置",
                    modifier = Modifier.size(20.dp),
                )
            }
            IconToggleButton(checked = transparent, onCheckedChange = { onToggleTransparent() }) {
                Icon(TablerIcons.Droplet, "切换透明模式", Modifier.size(20.dp))
            }
            FilledTonalIconButton(
                onClick = onDelete,
                enabled = canDelete,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(TablerIcons.Trash, "删除元素", Modifier.size(20.dp))
            }
        }
        HorizontalDivider()

        val rows = remember(page, expanded) { flatten(page.root, expanded) }
        val listState = rememberLazyListState()
        LaunchedEffect(revealTick) {
            if (selectedId != null) {
                val index = rows.indexOfFirst { it.node.id == selectedId }
                if (index >= 0) listState.animateScrollToItem(index)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.node.id }) { row ->
                val swatch = swatchColors[row.node.id] ?: row.node.colorArgb
                TreeRowItem(row, row.node.id == selectedId, showRaw, swatch, onSelect, onToggleExpand, onEdit)
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
    swatchColor: Int?,
    onSelect: (Int) -> Unit,
    onToggleExpand: (Int) -> Unit,
    onEdit: (Int) -> Unit,
) {
    val node = row.node
    val background =
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .semantics { this.selected = selected }
            .clickable { onSelect(node.id) }
            .heightIn(min = 52.dp)
            .padding(start = (4 + row.depth * 16).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.hasChildren) {
            IconButton(onClick = { onToggleExpand(node.id) }) {
                Icon(
                    imageVector = TablerIcons.ChevronRight,
                    contentDescription = if (row.expanded) "收起分组" else "展开分组",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (row.expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
        KindBadge(node.kind)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
        swatchColor?.let { argb ->
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(18.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color(argb))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
                    .semantics { contentDescription = "元素颜色 ${argb.toUInt().toString(16)}" },
            )
        }
        if (selected && node.kind != NodeKind.GROUP) {
            IconButton(onClick = { onEdit(node.id) }) {
                Icon(TablerIcons.Edit, "编辑元素", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun KindBadge(kind: NodeKind) {
    val scheme = MaterialTheme.colorScheme
    val (icon, label, color) = when (kind) {
        NodeKind.GROUP -> Triple(TablerIcons.Braces, "分组", scheme.outline)
        NodeKind.TEXT -> Triple(TablerIcons.LetterT, "文本", scheme.primary)
        NodeKind.PATH -> Triple(TablerIcons.VectorBeizer, "路径", scheme.tertiary)
        NodeKind.IMAGE -> Triple(TablerIcons.Photo, "图像", scheme.secondary)
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp), tint = color)
    }
}
