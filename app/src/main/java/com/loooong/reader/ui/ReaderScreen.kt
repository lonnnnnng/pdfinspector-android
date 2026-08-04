package com.loooong.reader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.TablerIcons
import compose.icons.tablericons.Bookmarks
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.Copy
import compose.icons.tablericons.DotsVertical
import compose.icons.tablericons.Edit
import compose.icons.tablericons.FileSearch
import compose.icons.tablericons.GridDots
import compose.icons.tablericons.Maximize
import compose.icons.tablericons.Minimize
import compose.icons.tablericons.Search
import compose.icons.tablericons.X
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.loooong.reader.AppMode
import com.loooong.reader.PdfDocumentViewModel
import com.loooong.reader.PdfUiState
import com.loooong.reader.ReaderOutlineEntry
import com.loooong.reader.ReaderPageInfo
import com.loooong.reader.ReaderSearchResult
import com.loooong.reader.ReaderUiState

private enum class ReaderPanel {
    THUMBNAILS,
    OUTLINE,
    SEARCH,
    TEXT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: PdfDocumentViewModel,
    state: PdfUiState,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val readerState = viewModel.readerState
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.pageIndex)
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var zoomPage by remember { mutableStateOf<Int?>(null) }

    fun jumpTo(pageIndex: Int) {
        val target = pageIndex.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        scope.launch {
            listState.animateScrollToItem(target)
            viewModel.updateReaderPosition(context, target)
        }
    }

    // long: 阅读进度以首个可见页为准，既能恢复到稳定位置，也避免滚动时高频写入像素偏移。
    LaunchedEffect(listState, state.documentToken) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.updateReaderPosition(context, it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "连续阅读 · 第 ${state.pageIndex + 1} 页",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(TablerIcons.ChevronLeft, contentDescription = "返回首页")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleReaderBookmark(context, state.pageIndex) }) {
                        Icon(
                            TablerIcons.Bookmarks,
                            contentDescription = if (state.pageIndex in readerState.bookmarks) {
                                "取消当前页书签"
                            } else {
                                "收藏当前页"
                            },
                            tint = if (state.pageIndex in readerState.bookmarks) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = { panel = ReaderPanel.SEARCH }) {
                        Icon(TablerIcons.Search, contentDescription = "全文搜索")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(TablerIcons.DotsVertical, contentDescription = "更多阅读操作")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("跳转到指定页") },
                                onClick = {
                                    menuExpanded = false
                                    showJumpDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (fullscreen) "退出全屏" else "进入全屏") },
                                leadingIcon = {
                                    Icon(
                                        if (fullscreen) TablerIcons.Minimize else TablerIcons.Maximize,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onToggleFullscreen()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("进入编辑模式") },
                                leadingIcon = { Icon(TablerIcons.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.switchMode(AppMode.EDIT)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("打开其他 PDF") },
                                onClick = {
                                    menuExpanded = false
                                    onOpen()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    menuExpanded = false
                                    onSettings()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                ReaderBottomAction(
                    icon = { Icon(TablerIcons.GridDots, contentDescription = null) },
                    label = "缩略图",
                    selected = panel == ReaderPanel.THUMBNAILS,
                    onClick = { panel = ReaderPanel.THUMBNAILS },
                    modifier = Modifier.weight(1f),
                )
                ReaderBottomAction(
                    icon = { Icon(TablerIcons.Bookmarks, contentDescription = null) },
                    label = "目录/书签",
                    selected = panel == ReaderPanel.OUTLINE,
                    onClick = { panel = ReaderPanel.OUTLINE },
                    modifier = Modifier.weight(1f),
                )
                ReaderBottomAction(
                    icon = { Text("${state.pageIndex + 1}/${state.pageCount}") },
                    label = "跳页",
                    onClick = { showJumpDialog = true },
                    modifier = Modifier.weight(1f),
                )
                ReaderBottomAction(
                    icon = { Icon(TablerIcons.Copy, contentDescription = null) },
                    label = "选择文本",
                    selected = panel == ReaderPanel.TEXT,
                    onClick = { panel = ReaderPanel.TEXT },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(readerState.pageInfos, key = { it.pageIndex }) { info ->
                ReaderPage(
                    viewModel = viewModel,
                    info = info,
                    documentToken = state.documentToken,
                    onZoom = { zoomPage = info.pageIndex },
                )
            }
        }
    }

    when (panel) {
        ReaderPanel.THUMBNAILS -> ReaderThumbnailSheet(
            viewModel = viewModel,
            pageInfos = readerState.pageInfos,
            currentPage = state.pageIndex,
            onSelect = {
                panel = null
                jumpTo(it)
            },
            onDismiss = { panel = null },
        )
        ReaderPanel.OUTLINE -> ReaderOutlineSheet(
            readerState = readerState,
            onSelect = {
                panel = null
                jumpTo(it)
            },
            onDismiss = { panel = null },
        )
        ReaderPanel.SEARCH -> ReaderSearchSheet(
            viewModel = viewModel,
            readerState = readerState,
            pageCount = state.pageCount,
            onSelect = {
                panel = null
                jumpTo(it)
            },
            onDismiss = {
                panel = null
                viewModel.clearReaderSearch()
            },
        )
        ReaderPanel.TEXT -> ReaderTextSheet(
            viewModel = viewModel,
            pageIndex = state.pageIndex,
            onDismiss = { panel = null },
        )
        null -> Unit
    }

    if (showJumpDialog) {
        ReaderJumpDialog(
            currentPage = state.pageIndex,
            pageCount = state.pageCount,
            onJump = {
                showJumpDialog = false
                jumpTo(it)
            },
            onDismiss = { showJumpDialog = false },
        )
    }

    zoomPage?.let { pageIndex ->
        readerState.pageInfos.getOrNull(pageIndex)?.let { info ->
            ReaderZoomDialog(
                viewModel = viewModel,
                info = info,
                onDismiss = { zoomPage = null },
            )
        }
    }
}

@Composable
private fun ReaderBottomAction(
    icon: @Composable () -> Unit,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 56.dp)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            icon()
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ReaderPage(
    viewModel: PdfDocumentViewModel,
    info: ReaderPageInfo,
    documentToken: Int,
    onZoom: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val targetWidth = constraints.maxWidth.coerceAtLeast(1)
        LaunchedEffect(documentToken, info.pageIndex, targetWidth) {
            viewModel.ensureReaderPage(info.pageIndex, targetWidth)
        }
        val page = viewModel.readerPages[info.pageIndex]
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(info.widthPoints / info.heightPoints)
                    .clickable(onClick = onZoom),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        page?.bitmap != null -> Image(
                            bitmap = page.bitmap,
                            contentDescription = "第 ${info.pageIndex + 1} 页",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        page?.error != null -> Text(
                            page.error,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "第 ${info.pageIndex + 1} 页",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReaderZoomDialog(
    viewModel: PdfDocumentViewModel,
    info: ReaderPageInfo,
    onDismiss: () -> Unit,
) {
    val page = viewModel.readerPages[info.pageIndex]
    val bitmap = page?.bitmap
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(TablerIcons.X, contentDescription = "关闭放大视图")
                    }
                    Text("第 ${info.pageIndex + 1} 页", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "双指缩放和拖动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (bitmap == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val scaleState = remember(info.pageIndex) { mutableStateOf(1f) }
                    val offsetState = remember(info.pageIndex) { mutableStateOf(Offset.Zero) }
                    PdfCanvas(
                        bitmap = bitmap,
                        pageIndex = info.pageIndex,
                        scaleState = scaleState,
                        offsetState = offsetState,
                        leaves = emptyList(),
                        selectedRect = null,
                        highlightColor = MaterialTheme.colorScheme.primary,
                        backdropColor = MaterialTheme.colorScheme.surfaceVariant,
                        runBoxes = emptyList(),
                        editingRunId = null,
                        textBoxColor = MaterialTheme.colorScheme.outline,
                        onEditRun = {},
                        fitMode = FitMode.WIDTH,
                        onUserTransform = {},
                        onSelect = {},
                        renderTile = { pageIndex, src, outW, outH ->
                            // long: 阅读位图按屏幕宽度生成，而高清区域接口使用 144-DPI 坐标，先换算可避免 tile 覆盖后被放大裁切。
                            val renderSource = mapReaderTileToRenderCoordinates(
                                source = src,
                                bitmapWidth = bitmap.width,
                                bitmapHeight = bitmap.height,
                                pageWidthPoints = info.widthPoints,
                                pageHeightPoints = info.heightPoints,
                                renderDpi = PdfDocumentViewModel.RENDER_DPI,
                            )
                            viewModel.renderRegion(
                                pageIndex,
                                renderSource.left,
                                renderSource.top,
                                renderSource.right,
                                renderSource.bottom,
                                outW,
                                outH,
                            )?.asImageBitmap()
                        },
                        // long: 放大画布只占工具栏下方的剩余空间，避免按整屏测量后被父容器裁切并产生错误适宽比例。
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderThumbnailSheet(
    viewModel: PdfDocumentViewModel,
    pageInfos: List<ReaderPageInfo>,
    currentPage: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "页面缩略图",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pageInfos, key = { it.pageIndex }) { info ->
                LaunchedEffect(info.pageIndex) { viewModel.ensureReaderThumbnail(info.pageIndex) }
                val thumbnail = viewModel.readerThumbnails[info.pageIndex]
                Column(
                    modifier = Modifier
                        .width(112.dp)
                        .clickable { onSelect(info.pageIndex) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(info.widthPoints / info.heightPoints),
                        shape = MaterialTheme.shapes.small,
                        color = if (info.pageIndex == currentPage) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        shadowElevation = 1.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            thumbnail?.bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = "第 ${info.pageIndex + 1} 页缩略图",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds,
                                )
                            } ?: CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${info.pageIndex + 1}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderOutlineSheet(
    readerState: ReaderUiState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "目录与书签",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("目录") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("书签") })
        }
        if (tab == 0) {
            OutlineList(readerState.outline, onSelect)
        } else {
            BookmarkList(readerState.bookmarks.sorted(), onSelect)
        }
    }
}

@Composable
private fun OutlineList(entries: List<ReaderOutlineEntry>, onSelect: (Int) -> Unit) {
    if (entries.isEmpty()) {
        EmptyPanelMessage("此文档没有可用目录")
        return
    }
    LazyColumn(Modifier.heightIn(max = 520.dp)) {
        items(entries) { entry ->
            ListItem(
                modifier = Modifier.clickable { onSelect(entry.pageIndex) },
                headlineContent = {
                    Text(
                        entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = (entry.level * 16).dp),
                    )
                },
                supportingContent = { Text("第 ${entry.pageIndex + 1} 页") },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BookmarkList(pages: List<Int>, onSelect: (Int) -> Unit) {
    if (pages.isEmpty()) {
        EmptyPanelMessage("还没有添加书签")
        return
    }
    LazyColumn(Modifier.heightIn(max = 520.dp)) {
        items(pages) { pageIndex ->
            ListItem(
                modifier = Modifier.clickable { onSelect(pageIndex) },
                headlineContent = { Text("第 ${pageIndex + 1} 页") },
                leadingContent = { Icon(TablerIcons.Bookmarks, contentDescription = null) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSearchSheet(
    viewModel: PdfDocumentViewModel,
    readerState: ReaderUiState,
    pageCount: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(readerState.searchQuery) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("全文搜索", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("输入关键词") },
                leadingIcon = { Icon(TablerIcons.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { viewModel.searchReader(query) }) {
                        Icon(TablerIcons.FileSearch, contentDescription = "开始搜索")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.searchReader(query) }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (readerState.searching) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = {
                        if (pageCount == 0) 0f else readerState.searchProgress.toFloat() / pageCount
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "正在搜索 ${readerState.searchProgress}/$pageCount 页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            readerState.searchError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
        SearchResultList(
            results = readerState.searchResults,
            searching = readerState.searching,
            hasQuery = readerState.searchQuery.isNotBlank(),
            onSelect = onSelect,
        )
    }
}

@Composable
private fun SearchResultList(
    results: List<ReaderSearchResult>,
    searching: Boolean,
    hasQuery: Boolean,
    onSelect: (Int) -> Unit,
) {
    if (results.isEmpty()) {
        val message = when {
            searching -> "正在搜索整个文档"
            hasQuery -> "没有找到匹配内容"
            else -> "输入关键词后搜索整个文档"
        }
        EmptyPanelMessage(message)
        return
    }
    LazyColumn(Modifier.heightIn(max = 480.dp)) {
        items(results) { result ->
            ListItem(
                modifier = Modifier.clickable { onSelect(result.pageIndex) },
                headlineContent = { Text("第 ${result.pageIndex + 1} 页") },
                supportingContent = {
                    Text(result.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis)
                },
            )
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTextSheet(
    viewModel: PdfDocumentViewModel,
    pageIndex: Int,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember(pageIndex) { mutableStateOf<String?>(null) }
    LaunchedEffect(pageIndex) { text = viewModel.readerPageText(pageIndex) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("第 ${pageIndex + 1} 页文本", style = MaterialTheme.typography.titleLarge)
            Text(
                "长按文字可以选择并复制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            when {
                text == null -> Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                text.isNullOrBlank() -> EmptyPanelMessage("本页没有可提取的文本")
                else -> {
                    // long: 文本区单独滚动并限制高度，既保留长按选择手势，也确保整页复制按钮在小屏上始终可达。
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(text.orEmpty())
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { copyText(context, text.orEmpty()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(TablerIcons.Copy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("复制本页全部文本")
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun EmptyPanelMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReaderJumpDialog(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf((currentPage + 1).toString()) }
    val target = value.toIntOrNull()
    val valid = target != null && target in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到指定页") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit) },
                label = { Text("页码（1-$pageCount）") },
                singleLine = true,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = { onJump(requireNotNull(target) - 1) }, enabled = valid) {
                Text("跳转")
            }
        },
    )
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PDF 文本", text))
    Toast.makeText(context, "已复制本页文本", Toast.LENGTH_SHORT).show()
}

internal fun mapReaderTileToRenderCoordinates(
    source: Rect,
    bitmapWidth: Int,
    bitmapHeight: Int,
    pageWidthPoints: Float,
    pageHeightPoints: Float,
    renderDpi: Float,
): Rect {
    require(bitmapWidth > 0 && bitmapHeight > 0) { "阅读位图尺寸必须大于 0" }
    val sourceScaleX = (pageWidthPoints * renderDpi / 72f) / bitmapWidth
    val sourceScaleY = (pageHeightPoints * renderDpi / 72f) / bitmapHeight
    return Rect(
        left = source.left * sourceScaleX,
        top = source.top * sourceScaleY,
        right = source.right * sourceScaleX,
        bottom = source.bottom * sourceScaleY,
    )
}
