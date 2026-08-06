package com.loooong.reader.ui

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
import compose.icons.tablericons.Plus
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
    canCopy: Boolean,
    onCopyElement: () -> Unit,
    canPasteElement: Boolean,
    onPasteElement: () -> Unit,
    onPasteText: () -> Unit,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
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
                    canCopy = canCopy,
                    onCopyElement = onCopyElement,
                    canPasteElement = canPasteElement,
                    onPasteElement = onPasteElement,
                    onPasteText = onPasteText,
                    onInsertText = onInsertText,
                    onInsertImage = onInsertImage,
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
    onCopyElement: () -> Unit,
    canPasteElement: Boolean,
    onPasteElement: () -> Unit,
    onPasteText: () -> Unit,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
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
            onCopyElement = onCopyElement,
            canPasteElement = canPasteElement,
            onPasteElement = onPasteElement,
            onPasteText = onPasteText,
            onInsertText = onInsertText,
            onInsertImage = onInsertImage,
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
            IconButton(onClick = onCopyElement) {
                Icon(TablerIcons.Copy, "复制元素", Modifier.size(20.dp))
            }
        }
        InsertMenuButton(
            canPasteElement = canPasteElement,
            onPasteElement = onPasteElement,
            onPasteText = onPasteText,
            onInsertText = onInsertText,
            onInsertImage = onInsertImage,
        )
        IconButton(
            onClick = onSave,
            enabled = dirty,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(TablerIcons.DeviceFloppy, "保存副本", Modifier.size(20.dp))
        }
        IconButton(onClick = onOpen) {
            Icon(TablerIcons.Folder, "打开 PDF", Modifier.size(20.dp))
        }
        ToolDivider()
        IconButton(onClick = onPrev, enabled = pageIndex > 0) {
            Icon(TablerIcons.ChevronLeft, "上一页", Modifier.size(20.dp))
        }
        PageIndicator(pageIndex, pageCount)
        IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
            Icon(TablerIcons.ChevronRight, "下一页", Modifier.size(20.dp))
        }
        ToolDivider()
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(TablerIcons.ArrowBackUp, "撤销", Modifier.size(20.dp))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(TablerIcons.ArrowForwardUp, "重做", Modifier.size(20.dp))
        }
        ToolDivider()
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                imageVector = if (fullscreen) TablerIcons.Minimize else TablerIcons.Maximize,
                contentDescription = if (fullscreen) "退出全屏" else "进入全屏",
                modifier = Modifier.size(20.dp),
                tint = if (fullscreen) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FitMenuButton(onFitWidth = onFitWidth, onFitHeight = onFitHeight)
        IconButton(onClick = onSettings) {
            Icon(TablerIcons.Settings, "设置", Modifier.size(20.dp))
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
    onCopyElement: () -> Unit,
    canPasteElement: Boolean,
    onPasteElement: () -> Unit,
    onPasteText: () -> Unit,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
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
            Icon(TablerIcons.DeviceFloppy, "保存副本", Modifier.size(20.dp))
        }
        IconButton(onClick = onPrev, enabled = pageIndex > 0) {
            Icon(TablerIcons.ChevronLeft, "上一页", Modifier.size(20.dp))
        }
        PageIndicator(pageIndex, pageCount)
        IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
            Icon(TablerIcons.ChevronRight, "下一页", Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(TablerIcons.Dots, "更多操作", Modifier.size(20.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                fun run(action: () -> Unit) {
                    expanded = false
                    action()
                }
                DropdownMenuItem(text = { Text("打开 PDF") }, onClick = { run(onOpen) })
                if (canCopy) {
                    DropdownMenuItem(text = { Text("复制所选元素") }, onClick = { run(onCopyElement) })
                }
                DropdownMenuItem(text = { Text("插入文本") }, onClick = { run(onInsertText) })
                DropdownMenuItem(text = { Text("插入图片") }, onClick = { run(onInsertImage) })
                if (canPasteElement) {
                    DropdownMenuItem(text = { Text("粘贴元素") }, onClick = { run(onPasteElement) })
                }
                DropdownMenuItem(text = { Text("粘贴剪贴板文本") }, onClick = { run(onPasteText) })
                DropdownMenuItem(text = { Text("撤销") }, enabled = canUndo, onClick = { run(onUndo) })
                DropdownMenuItem(text = { Text("重做") }, enabled = canRedo, onClick = { run(onRedo) })
                DropdownMenuItem(text = { Text("适合宽度") }, onClick = { run(onFitWidth) })
                DropdownMenuItem(text = { Text("适合高度") }, onClick = { run(onFitHeight) })
                DropdownMenuItem(
                    text = { Text(if (fullscreen) "退出全屏" else "进入全屏") },
                    onClick = { run(onToggleFullscreen) },
                )
                DropdownMenuItem(text = { Text("设置") }, onClick = { run(onSettings) })
            }
        }
    }
}

@Composable
private fun InsertMenuButton(
    canPasteElement: Boolean,
    onPasteElement: () -> Unit,
    onPasteText: () -> Unit,
    onInsertText: () -> Unit,
    onInsertImage: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(TablerIcons.Plus, "插入内容", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fun run(action: () -> Unit) {
                expanded = false
                action()
            }
            DropdownMenuItem(text = { Text("插入文本") }, onClick = { run(onInsertText) })
            DropdownMenuItem(text = { Text("插入图片") }, onClick = { run(onInsertImage) })
            if (canPasteElement) {
                DropdownMenuItem(text = { Text("粘贴元素") }, onClick = { run(onPasteElement) })
            }
            DropdownMenuItem(text = { Text("粘贴剪贴板文本") }, onClick = { run(onPasteText) })
        }
    }
}

@Composable
private fun PageIndicator(pageIndex: Int, pageCount: Int) {
    Text(
        text = "${pageIndex + 1} / $pageCount",
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .semantics { contentDescription = "第 ${pageIndex + 1} 页，共 $pageCount 页" },
    )
}

@Composable
private fun FitMenuButton(onFitWidth: () -> Unit, onFitHeight: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(TablerIcons.AspectRatio, "页面适配", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("适合宽度") },
                onClick = { expanded = false; onFitWidth() },
            )
            DropdownMenuItem(
                text = { Text("适合高度") },
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
