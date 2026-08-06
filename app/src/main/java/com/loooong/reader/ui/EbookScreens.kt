package com.loooong.reader.ui

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import compose.icons.TablerIcons
import compose.icons.tablericons.Book
import compose.icons.tablericons.Bookmarks
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.CloudDownload
import compose.icons.tablericons.DotsVertical
import compose.icons.tablericons.FileText
import compose.icons.tablericons.Maximize
import compose.icons.tablericons.Minimize
import compose.icons.tablericons.Search
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Trash
import compose.icons.tablericons.X
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.readium.r2.navigator.R2BasicWebView
import org.readium.r2.navigator.ExperimentalDecorator
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.LocatorService
import com.loooong.reader.EbookActivity
import com.loooong.reader.EpubEbookDocument
import com.loooong.reader.EbookFormat
import com.loooong.reader.EbookHistoryEntry
import com.loooong.reader.EbookPageTheme
import com.loooong.reader.EbookReaderSettings
import com.loooong.reader.EbookScreen
import com.loooong.reader.EpubTocEntry
import com.loooong.reader.EbookViewModel
import com.loooong.reader.TxtEbookDocument
import com.loooong.reader.normalizeHttpsEbookUrl
import com.loooong.reader.txtTableOfContents

@Composable
fun EbookApp(
    activity: EbookActivity,
    viewModel: EbookViewModel,
    initialUri: android.net.Uri?,
    restoreHistory: Boolean,
    onChooseFile: () -> Unit,
) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialUri, restoreHistory) {
        if (initialUri != null && viewModel.screen is EbookScreen.Library) {
            viewModel.openUri(activity, initialUri)
        } else if (initialUri == null && restoreHistory) {
            viewModel.restoreLastOpened(activity)
        }
    }

    LaunchedEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        if (fullscreen) {
            fullscreen = false
        } else {
            when (viewModel.screen) {
                EbookScreen.Library, is EbookScreen.Error -> activity.finish()
                is EbookScreen.Loading -> viewModel.showLibrary()
                is EbookScreen.Txt, is EbookScreen.Epub -> viewModel.showLibrary()
            }
        }
    }

    when (val screen = viewModel.screen) {
        EbookScreen.Library -> EbookHomeScreen(
            history = viewModel.history,
            onChooseFile = onChooseFile,
            onOpenHistory = { viewModel.openHistory(activity, it) },
            onRemoveHistory = { viewModel.removeHistory(it) },
            onOpenOnline = { viewModel.openOnline(activity, it) },
            onBack = { activity.finish() },
        )
        is EbookScreen.Loading -> EbookLoadingScreen(screen.message)
        is EbookScreen.Error -> EbookHomeScreen(
            history = viewModel.history,
            error = screen.message,
            onChooseFile = onChooseFile,
            onOpenHistory = { viewModel.openHistory(activity, it) },
            onRemoveHistory = { viewModel.removeHistory(it) },
            onOpenOnline = { viewModel.openOnline(activity, it) },
            onBack = { activity.finish() },
        )
        is EbookScreen.Txt -> TxtReaderScreen(
            document = screen.document,
            viewModel = viewModel,
            fullscreen = fullscreen,
            onToggleFullscreen = { fullscreen = !fullscreen },
            onBack = { fullscreen = false; viewModel.showLibrary() },
        )
        is EbookScreen.Epub -> EpubReaderScreen(
            document = screen.document,
            viewModel = viewModel,
            fullscreen = fullscreen,
            onToggleFullscreen = { fullscreen = !fullscreen },
            onBack = { fullscreen = false; viewModel.showLibrary() },
        )
    }
}

@Composable
private fun EbookLoadingScreen(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EbookHomeScreen(
    history: List<EbookHistoryEntry>,
    error: String? = null,
    onChooseFile: () -> Unit,
    onOpenHistory: (EbookHistoryEntry) -> Unit,
    onRemoveHistory: (EbookHistoryEntry) -> Unit,
    onOpenOnline: (String) -> Unit,
    onBack: () -> Unit,
) {
    var onlineUrl by remember { mutableStateOf("") }
    var showOnlineUrlError by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EbookHistoryEntry?>(null) }
    val normalizedOnlineUrl = remember(onlineUrl) { normalizeHttpsEbookUrl(onlineUrl) }

    fun submitOnlineUrl() {
        val normalized = normalizedOnlineUrl
        if (normalized == null) {
            showOnlineUrlError = true
        } else {
            showOnlineUrlError = false
            onOpenOnline(normalized)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电子书") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(TablerIcons.ChevronLeft, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 720.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                            .clickable(role = Role.Button, onClick = onChooseFile),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                TablerIcons.FileText,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("选择本地电子书", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "EPUB 或 TXT",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Icon(
                                TablerIcons.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                if (error != null) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                error,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = onlineUrl,
                        onValueChange = {
                            onlineUrl = it
                            if (it.isBlank()) showOnlineUrlError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("在线电子书地址") },
                        placeholder = { Text("https://example.com/book.epub") },
                        supportingText = {
                            Text(
                                if (showOnlineUrlError && normalizedOnlineUrl == null) {
                                    "请输入有效的 HTTPS 电子书直链"
                                } else {
                                    "支持 EPUB 和 TXT，不支持含账号信息的地址"
                                },
                            )
                        },
                        isError = showOnlineUrlError && normalizedOnlineUrl == null,
                        leadingIcon = { Icon(TablerIcons.CloudDownload, contentDescription = null) },
                        trailingIcon = {
                            IconButton(
                                onClick = ::submitOnlineUrl,
                                enabled = onlineUrl.isNotBlank(),
                            ) {
                                Icon(TablerIcons.ChevronRight, contentDescription = "打开在线电子书")
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submitOnlineUrl() }),
                    )
                }
                item {
                    Text("最近阅读", style = MaterialTheme.typography.titleMedium)
                }
                if (history.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                "暂无电子书阅读记录",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(history, key = { it.sourceId }) { entry ->
                        EbookHistoryRow(
                            entry = entry,
                            onClick = { onOpenHistory(entry) },
                            onDelete = { pendingDelete = entry },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除最近阅读") },
            text = { Text("将移除书架记录、阅读进度和本地缓存，不会删除原始文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onRemoveHistory(entry)
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EbookHistoryRow(
    entry: EbookHistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (entry.format == EbookFormat.EPUB) TablerIcons.Book else TablerIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val format = if (entry.format == EbookFormat.EPUB) "EPUB" else "TXT"
                    val progress = entry.progress?.let { " · ${(it * 100).roundToInt()}%" }.orEmpty()
                    Text(
                        "$format$progress",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    TablerIcons.Trash,
                    contentDescription = "移除${entry.title}",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(
    ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
private fun TxtReaderScreen(
    document: TxtEbookDocument,
    viewModel: EbookViewModel,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    val compactToolbar = LocalConfiguration.current.screenWidthDp < 360
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.currentTxtPosition(document).coerceIn(
            0,
            (document.paragraphs.size - 1).coerceAtLeast(0),
        ),
    )
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<TxtPanel?>(null) }
    var query by remember { mutableStateOf("") }
    var pendingProgress by remember(document.sourceId) { mutableStateOf<Float?>(null) }
    val tableOfContents = remember(document.sourceId) { txtTableOfContents(document.paragraphs) }
    val currentIndex by remember(document.sourceId) {
        derivedStateOf {
            listState.firstVisibleItemIndex.coerceIn(0, (document.paragraphs.size - 1).coerceAtLeast(0))
        }
    }
    val visibleParagraphCount by remember(document.sourceId) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
    }
    val latestPosition by rememberUpdatedState(currentIndex)
    val scrollPositionCount = readerScrollablePositionCount(
        document.paragraphs.size,
        visibleParagraphCount,
    )
    val progress = readerProgressForIndex(currentIndex, scrollPositionCount)
    val displayedProgress = pendingProgress ?: progress
    val displayedIndex = readerIndexForProgress(displayedProgress, scrollPositionCount)
    val displayedChapter = currentTxtChapterTitle(tableOfContents, displayedIndex) ?: "正文"

    LaunchedEffect(listState, document.sourceId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest {
                delay(POSITION_SAVE_DEBOUNCE_MS)
                viewModel.saveTxtPosition(document, it)
            }
    }
    DisposableEffect(document.sourceId) {
        onDispose {
            // long: 页面退出时立即补写最后可见段落，避免节流窗口内返回首页导致进度丢失。
            viewModel.saveTxtPosition(document, latestPosition)
        }
    }

    ReaderSurface(viewModel.settings) {
        val readerBackground = readerBackgroundColor(viewModel.settings)
        val readerForeground = readerContentColor(viewModel.settings)
        Scaffold(
            containerColor = readerBackground,
            contentColor = readerForeground,
            topBar = {
                if (!fullscreen) TopAppBar(
                    title = {
                        Column {
                            Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "TXT · ${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = readerForeground.copy(alpha = 0.68f),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(TablerIcons.ChevronLeft, contentDescription = "返回电子书首页")
                        }
                    },
                    actions = {
                        EbookReaderToolbarActions(
                            compact = compactToolbar,
                            onSearch = { panel = TxtPanel.SEARCH },
                            onToc = { panel = TxtPanel.TOC },
                            onSettings = { panel = TxtPanel.SETTINGS },
                            onToggleFullscreen = onToggleFullscreen,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = readerBackground,
                        scrolledContainerColor = readerBackground,
                        navigationIconContentColor = readerForeground,
                        titleContentColor = readerForeground,
                        actionIconContentColor = readerForeground,
                    ),
                )
            },
            bottomBar = {
                if (!fullscreen) Column(Modifier.background(readerBackground).navigationBarsPadding()) {
                    ReaderProgressSlider(
                        progress = displayedProgress,
                        enabled = document.paragraphs.size > 1,
                        state = "$displayedChapter，阅读进度 ${(displayedProgress * 100).roundToInt()}%，" +
                            "第 ${displayedIndex + 1} 段，共 ${document.paragraphs.size} 段",
                        foreground = readerForeground,
                        onProgressChange = { pendingProgress = it },
                        onProgressChangeFinished = {
                            val targetIndex = readerIndexForProgress(
                                pendingProgress ?: progress,
                                scrollPositionCount,
                            )
                            pendingProgress = null
                            scope.launch { listState.scrollToItem(targetIndex) }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            displayedChapter,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = readerForeground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "第 ${displayedIndex + 1} 段 / ${document.paragraphs.size} 段",
                            style = MaterialTheme.typography.labelMedium,
                            color = readerForeground.copy(alpha = 0.68f),
                        )
                    }
                }
            },
        ) { innerPadding ->
            // long: Android 12+ 默认拉伸滚动边界；阅读正文到底后应保持稳定，但仍保留正常滚动和惯性。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = viewModel.settings.horizontalPaddingDp.dp,
                            vertical = 22.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(document.paragraphs, key = { index, _ -> index }) { _, paragraph ->
                            SelectionContainer {
                                Text(
                                    text = paragraph,
                                    style = TextStyle(
                                        fontSize = viewModel.settings.fontSizeSp.sp,
                                        lineHeight = (viewModel.settings.fontSizeSp * viewModel.settings.lineHeight).sp,
                                        color = readerForeground,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                if (fullscreen) {
                    EbookFullscreenExitButton(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        onClick = onToggleFullscreen,
                    )
                }
            }
        }
    }

    when (panel) {
        TxtPanel.SEARCH -> EbookSearchSheet(
            query = query,
            onQueryChange = { query = it; viewModel.searchTxt(document, it) },
            searching = viewModel.searching,
            error = viewModel.searchError,
            txtResults = viewModel.txtSearchResults,
            epubResults = emptyList(),
            onSelectTxt = {
                panel = null
                scope.launch { listState.animateScrollToItem(it) }
            },
            onSelectEpub = {},
            onDismiss = { panel = null; query = ""; viewModel.clearSearch() },
        )
        TxtPanel.TOC -> TxtTocSheet(
            entries = tableOfContents,
            currentIndex = currentIndex,
            onSelect = {
                panel = null
                scope.launch { listState.animateScrollToItem(it) }
            },
            onDismiss = { panel = null },
        )
        TxtPanel.SETTINGS -> EbookSettingsSheet(
            settings = viewModel.settings,
            onSettingsChange = viewModel::updateSettings,
            onDismiss = { panel = null },
        )
        null -> Unit
    }
}

private enum class TxtPanel { SEARCH, TOC, SETTINGS }

@OptIn(
    ExperimentalReadiumApi::class,
    ExperimentalDecorator::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
private fun EpubReaderScreen(
    document: EpubEbookDocument,
    viewModel: EbookViewModel,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    val compactToolbar = LocalConfiguration.current.screenWidthDp < 360
    val activity = LocalContext.current as? FragmentActivity
    val containerId = remember { com.loooong.reader.R.id.epub_navigator_container }
    var navigator by remember(document.sourceId) { mutableStateOf<EpubNavigatorFragment?>(null) }
    var currentLocator by remember(document.sourceId) {
        mutableStateOf(viewModel.currentEpubLocator(document))
    }
    var panel by remember { mutableStateOf<EpubPanel?>(null) }
    var query by remember { mutableStateOf("") }
    var pendingProgress by remember(document.sourceId) { mutableStateOf<Float?>(null) }
    var seeking by remember(document.sourceId) { mutableStateOf(false) }
    var containerReady by remember(document.sourceId) { mutableStateOf(false) }
    var navigatorContentReady by remember(document.sourceId) { mutableStateOf(false) }
    var navigatorMountError by remember(document.sourceId) { mutableStateOf<String?>(null) }
    var mountAttempt by remember(document.sourceId) { mutableStateOf(0) }
    val systemDark = isSystemInDarkTheme()
    val inspectionMode = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val locatorService = remember(document.publication) {
        document.publication.findService(LocatorService::class)
    }
    val latestLocator by rememberUpdatedState(currentLocator)
    val progress = currentLocator?.locations?.totalProgression?.toFloat()?.coerceIn(0f, 1f) ?: 0f
    val displayedProgress = pendingProgress ?: progress
    val currentChapter = currentEpubTocEntry(document.tableOfContents, currentLocator)

    if (activity != null && containerReady && !inspectionMode) {
        DisposableEffect(document.sourceId, activity, containerReady, mountAttempt) {
            val fragmentManager = activity.supportFragmentManager
            val mountResult = runCatching {
                fragmentManager.fragmentFactory = document.navigatorFactory.createFragmentFactory(
                    initialLocator = currentLocator ?: document.initialLocator,
                    initialPreferences = viewModel.settings.toReadiumPreferences(systemDark),
                    listener = object : EpubNavigatorFragment.Listener {},
                    configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false),
                )
                val existing = fragmentManager.findFragmentByTag(EPUB_NAVIGATOR_TAG) as? EpubNavigatorFragment
                existing ?: run {
                    fragmentManager.commitNow {
                        add(containerId, EpubNavigatorFragment::class.java, Bundle(), EPUB_NAVIGATOR_TAG)
                    }
                    fragmentManager.findFragmentByTag(EPUB_NAVIGATOR_TAG) as? EpubNavigatorFragment
                }
            }
            val mountedNavigator = mountResult.getOrNull()
            val mountError = mountResult.exceptionOrNull()
            if (mountError != null) {
                // long: Readium 的异常细节保留在日志中便于定位，界面只呈现稳定的中文恢复入口。
                Log.e(EPUB_READER_LOG_TAG, "Readium 正文挂载失败", mountError)
            }
            navigator = mountedNavigator
            navigatorContentReady = false
            navigatorMountError = if (mountedNavigator == null) "正文加载失败，请重试" else null
            if (mountedNavigator == null) {
                // long: Readium 挂载失败时保留阅读页和显式重试入口，避免用户只看到无法恢复的空白区域。
                runCatching {
                    fragmentManager.findFragmentByTag(EPUB_NAVIGATOR_TAG)?.let { fragment ->
                        fragmentManager.commitNow { remove(fragment) }
                    }
                }
            }
            onDispose {
                latestLocator?.let { viewModel.saveEpubPosition(document, it) }
                navigator = null
                navigatorContentReady = false
                runCatching {
                    fragmentManager.findFragmentByTag(EPUB_NAVIGATOR_TAG)?.let { fragment ->
                        fragmentManager.commitNow { remove(fragment) }
                    }
                }
                if (viewModel.screen !is EbookScreen.Epub) {
                    viewModel.releasePublication(document.publication)
                }
            }
        }
    }

    LaunchedEffect(navigator, document.sourceId) {
        navigator?.currentLocator?.collectLatest {
            currentLocator = it
            delay(POSITION_SAVE_DEBOUNCE_MS)
            viewModel.saveEpubPosition(document, it)
        }
    }
    LaunchedEffect(navigator, viewModel.settings, systemDark) {
        navigator?.submitPreferences(viewModel.settings.toReadiumPreferences(systemDark))
    }
    LaunchedEffect(navigator, currentLocator?.href) {
        val activeNavigator = navigator ?: return@LaunchedEffect
        activeNavigator.view?.let { root ->
            root.disableReaderOverscroll()
            root.post { root.disableReaderOverscroll() }
        }
        // long: Readium 的页面 WebView 是异步加载的，延迟注入才能覆盖章节切换后新建的正文 DOM。
        delay(100)
        activeNavigator.view?.disableReaderOverscroll()
        activeNavigator.awaitDocumentReady()
        activeNavigator.disableUserPaging()
        activeNavigator.lockHorizontalViewport()
        if (navigator === activeNavigator) navigatorContentReady = true
    }

    ReaderSurface(viewModel.settings) {
        val readerBackground = readerBackgroundColor(viewModel.settings)
        val readerForeground = readerContentColor(viewModel.settings)
        Scaffold(
            containerColor = readerBackground,
            contentColor = readerForeground,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (!fullscreen) TopAppBar(
                    title = {
                        Column {
                            Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "EPUB · ${currentChapter?.title ?: "正文"} · ${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = readerForeground.copy(alpha = 0.68f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(TablerIcons.ChevronLeft, contentDescription = "返回电子书首页")
                        }
                    },
                    actions = {
                        EbookReaderToolbarActions(
                            compact = compactToolbar,
                            onSearch = { panel = EpubPanel.SEARCH },
                            onToc = { panel = EpubPanel.TOC },
                            onSettings = { panel = EpubPanel.SETTINGS },
                            onToggleFullscreen = onToggleFullscreen,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = readerBackground,
                        scrolledContainerColor = readerBackground,
                        navigationIconContentColor = readerForeground,
                        titleContentColor = readerForeground,
                        actionIconContentColor = readerForeground,
                    ),
                )
            },
            bottomBar = {
                if (!fullscreen) Column(Modifier.background(readerBackground).navigationBarsPadding()) {
                    ReaderProgressSlider(
                        progress = displayedProgress,
                        enabled = navigator != null && !seeking && locatorService != null,
                        state = "阅读进度 ${(displayedProgress * 100).roundToInt()}%",
                        foreground = readerForeground,
                        onProgressChange = { pendingProgress = it },
                        onProgressChangeFinished = {
                            val targetProgress = pendingProgress ?: progress
                            pendingProgress = null
                            val activeNavigator = navigator
                            if (activeNavigator != null && locatorService != null) {
                                scope.launch {
                                    seeking = true
                                    try {
                                        // long: 松手后由 Readium 解析完整资源位置，避免用缺少 href 的 Locator 跳错章节。
                                        val targetLocator = locatorService
                                            .locateProgression(targetProgress.toDouble())
                                            ?: error("Readium 未返回可用的阅读位置")
                                        activeNavigator.go(targetLocator)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Throwable) {
                                        snackbarHostState.showSnackbar("无法跳转到该进度，请使用目录定位")
                                    } finally {
                                        seeking = false
                                    }
                                }
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { navigator?.goBackward() },
                            enabled = navigator != null,
                            modifier = Modifier.size(48.dp),
                        ) { Icon(TablerIcons.ChevronLeft, contentDescription = "向前翻阅") }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (seeking) "正在定位…" else currentChapter?.title ?: "正文",
                                style = MaterialTheme.typography.labelMedium,
                                color = readerForeground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${(displayedProgress * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = readerForeground.copy(alpha = 0.68f),
                            )
                        }
                        IconButton(
                            onClick = { navigator?.goForward() },
                            enabled = navigator != null,
                            modifier = Modifier.size(48.dp),
                        ) { Icon(TablerIcons.ChevronRight, contentDescription = "向后翻阅") }
                    }
                }
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                AndroidView(
                    factory = {
                        FrameLayout(it).apply {
                            id = containerId
                            // long: Fragment 必须等容器进入 View 树后再提交，否则部分设备会找不到宿主 ID。
                            post { containerReady = true }
                        }
                    },
                    update = { it.disableReaderOverscroll() },
                    modifier = Modifier.fillMaxSize(),
                )
                when {
                    navigatorMountError != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(readerBackground)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(navigatorMountError.orEmpty(), color = readerForeground)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                navigatorMountError = null
                                mountAttempt += 1
                            },
                        ) {
                            Text("重新加载正文")
                        }
                    }
                    (navigator == null || !navigatorContentReady) && !inspectionMode -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(readerBackground),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(color = readerForeground)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "正在加载正文…",
                            color = readerForeground.copy(alpha = 0.72f),
                        )
                    }
                }
                if (fullscreen) {
                    EbookFullscreenExitButton(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        onClick = onToggleFullscreen,
                    )
                }
            }
        }
    }

    when (panel) {
        EpubPanel.SEARCH -> EbookSearchSheet(
            query = query,
            onQueryChange = { query = it; viewModel.searchEpub(document, it) },
            searching = viewModel.searching,
            error = viewModel.searchError,
            txtResults = emptyList(),
            epubResults = viewModel.epubSearchResults,
            onSelectTxt = {},
            onSelectEpub = {
                panel = null
                navigator?.go(it)
            },
            onDismiss = { panel = null; query = ""; viewModel.clearSearch() },
        )
        EpubPanel.TOC -> EpubTocSheet(
            entries = document.tableOfContents,
            currentLocator = currentLocator,
            onSelect = {
                panel = null
                navigator?.go(it)
            },
            onDismiss = { panel = null },
        )
        EpubPanel.SETTINGS -> EbookSettingsSheet(
            settings = viewModel.settings,
            onSettingsChange = viewModel::updateSettings,
            onDismiss = { panel = null },
        )
        null -> Unit
    }
}

private enum class EpubPanel { SEARCH, TOC, SETTINGS }

@Composable
private fun EbookReaderToolbarActions(
    compact: Boolean,
    onSearch: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    IconButton(onClick = onSearch) {
        Icon(TablerIcons.Search, contentDescription = "搜索正文")
    }
    if (compact) {
        var menuExpanded by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(TablerIcons.DotsVertical, contentDescription = "更多阅读操作")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("打开目录") },
                    leadingIcon = { Icon(TablerIcons.Bookmarks, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onToc()
                    },
                )
                DropdownMenuItem(
                    text = { Text("阅读设置") },
                    leadingIcon = { Icon(TablerIcons.Settings, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onSettings()
                    },
                )
                DropdownMenuItem(
                    text = { Text("进入全屏") },
                    leadingIcon = { Icon(TablerIcons.Maximize, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onToggleFullscreen()
                    },
                )
            }
        }
    } else {
        IconButton(onClick = onToc) {
            Icon(TablerIcons.Bookmarks, contentDescription = "打开目录")
        }
        IconButton(onClick = onSettings) {
            Icon(TablerIcons.Settings, contentDescription = "阅读设置")
        }
        IconButton(onClick = onToggleFullscreen) {
            Icon(TablerIcons.Maximize, contentDescription = "进入全屏")
        }
    }
}

@Composable
private fun EbookFullscreenExitButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        IconButton(onClick = onClick) {
            Icon(TablerIcons.Minimize, contentDescription = "退出全屏")
        }
    }
}

@Composable
private fun ReaderSurface(settings: EbookReaderSettings, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = readerBackgroundColor(settings),
        contentColor = readerContentColor(settings),
    ) { content() }
}

@Composable
private fun readerBackgroundColor(settings: EbookReaderSettings): Color = when (settings.pageTheme) {
    EbookPageTheme.LIGHT -> Color(0xFFFFFFFF)
    EbookPageTheme.DARK -> Color(0xFF101010)
    EbookPageTheme.SEPIA -> Color(0xFFFAF4E8)
    EbookPageTheme.SYSTEM -> MaterialTheme.colorScheme.background
}

@Composable
private fun readerContentColor(settings: EbookReaderSettings): Color = when (settings.pageTheme) {
    EbookPageTheme.LIGHT -> Color(0xFF181818)
    EbookPageTheme.DARK -> Color(0xFFF2F2F2)
    EbookPageTheme.SEPIA -> Color(0xFF29251E)
    EbookPageTheme.SYSTEM -> MaterialTheme.colorScheme.onBackground
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EbookSearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    error: String?,
    txtResults: List<com.loooong.reader.EbookSearchResult>,
    epubResults: List<Locator>,
    onSelectTxt: (Int) -> Unit,
    onSelectEpub: (Locator) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
        ) {
            EbookSheetHeader("搜索正文", onDismiss, horizontalPadding = 0.dp)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("输入关键词") },
                leadingIcon = { Icon(TablerIcons.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQueryChange(query.trim()) }),
            )
            Spacer(Modifier.height(12.dp))
            when {
                searching -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                error != null -> Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        error,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                query.isBlank() -> EbookSheetEmptyMessage("输入关键词后搜索正文")
                txtResults.isEmpty() && epubResults.isEmpty() && query.isNotBlank() ->
                    EbookSheetEmptyMessage("没有找到匹配内容")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp),
                ) {
                    items(txtResults, key = { it.paragraphIndex }) { result ->
                        ListItem(
                            headlineContent = { Text("第 ${result.paragraphIndex + 1} 段") },
                            supportingContent = { Text(result.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onSelectTxt(result.paragraphIndex) },
                            leadingContent = { Icon(TablerIcons.FileText, contentDescription = null) },
                            trailingContent = {
                                Icon(TablerIcons.ChevronRight, contentDescription = null)
                            },
                        )
                        HorizontalDivider()
                    }
                    items(epubResults) { result ->
                        val title = result.title ?: "正文匹配"
                        val snippet = listOfNotNull(result.text.before, result.text.highlight, result.text.after)
                            .joinToString(" ")
                        ListItem(
                            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(snippet.ifBlank { result.href }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onSelectEpub(result) },
                            leadingContent = { Icon(TablerIcons.FileText, contentDescription = null) },
                            trailingContent = {
                                Icon(TablerIcons.ChevronRight, contentDescription = null)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TxtTocSheet(
    entries: List<Pair<Int, String>>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentChapterIndex = entries.lastOrNull { it.first <= currentIndex }?.first
    val currentEntryIndex = entries.indexOfFirst { it.first == currentChapterIndex }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentEntryIndex)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            EbookSheetHeader("目录", onDismiss)
            if (entries.isEmpty()) {
                EbookSheetEmptyMessage("TXT 未提供章节目录")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                ) {
                    items(entries, key = { it.first }) { (index, title) ->
                        val isCurrent = index == currentChapterIndex
                        ListItem(
                            headlineContent = {
                                Text(
                                    title,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = { Text("第 ${index + 1} 段") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { selected = isCurrent }
                                .clickable(role = Role.Tab) { onSelect(index) },
                            leadingContent = { Icon(TablerIcons.Bookmarks, contentDescription = null) },
                            trailingContent = {
                                if (isCurrent) Text("当前", color = MaterialTheme.colorScheme.primary)
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isCurrent) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EpubTocSheet(
    entries: List<EpubTocEntry>,
    currentLocator: Locator?,
    onSelect: (Locator) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentEntry = currentEpubTocEntry(entries, currentLocator)
    val currentEntryIndex = entries.indexOfFirst {
        it.title == currentEntry?.title && it.locator.href == currentEntry?.locator?.href
    }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentEntryIndex)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            EbookSheetHeader("目录", onDismiss)
            if (entries.isEmpty()) {
                EbookSheetEmptyMessage("这本 EPUB 没有目录信息")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                ) {
                    items(entries) { entry ->
                        val isCurrent = entry.title == currentEntry?.title &&
                            entry.locator.href == currentEntry.locator.href
                        ListItem(
                            headlineContent = {
                                Text(
                                    entry.title,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (entry.depth * 18).dp)
                                .semantics { selected = isCurrent }
                                .clickable(role = Role.Tab) { onSelect(entry.locator) },
                            leadingContent = { Icon(TablerIcons.Bookmarks, contentDescription = null) },
                            trailingContent = {
                                if (isCurrent) Text("当前", color = MaterialTheme.colorScheme.primary)
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isCurrent) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ),
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EbookSettingsSheet(
    settings: EbookReaderSettings,
    onSettingsChange: (EbookReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EbookSheetHeader("阅读设置", onDismiss, horizontalPadding = 0.dp)
            Text("字号 ${settings.fontSizeSp.toInt()}sp", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = settings.fontSizeSp,
                onValueChange = { onSettingsChange(settings.copy(fontSizeSp = it)) },
                valueRange = 14f..32f,
                steps = 8,
                modifier = Modifier.semantics {
                    contentDescription = "字号"
                    stateDescription = "${settings.fontSizeSp.roundToInt()}sp"
                },
            )
            Text("行距 ${"%.2f".format(settings.lineHeight)}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = settings.lineHeight,
                onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
                valueRange = 1.2f..2.2f,
                steps = 9,
                modifier = Modifier.semantics {
                    contentDescription = "行距"
                    stateDescription = "${"%.2f".format(settings.lineHeight)} 倍"
                },
            )
            Text("页边距 ${settings.horizontalPaddingDp.toInt()}dp", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = settings.horizontalPaddingDp,
                onValueChange = { onSettingsChange(settings.copy(horizontalPaddingDp = it)) },
                valueRange = 8f..40f,
                steps = 7,
                modifier = Modifier.semantics {
                    contentDescription = "页边距"
                    stateDescription = "${settings.horizontalPaddingDp.roundToInt()}dp"
                },
            )
            Text("阅读主题", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EbookPageTheme.values().toList().chunked(2).forEach { rowThemes ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowThemes.forEach { theme ->
                            FilterChip(
                                selected = theme == settings.pageTheme,
                                onClick = { onSettingsChange(settings.copy(pageTheme = theme)) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(14.dp),
                                            shape = CircleShape,
                                            color = theme.previewColor(),
                                            border = BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                            ),
                                        ) {}
                                        Text(theme.displayName())
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun EbookSheetHeader(
    title: String,
    onDismiss: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 12.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding + 8.dp, end = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(TablerIcons.X, contentDescription = "关闭$title")
        }
    }
}

@Composable
private fun EbookSheetEmptyMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(160.dp).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EbookPageTheme.previewColor(): Color = when (this) {
    EbookPageTheme.SYSTEM -> MaterialTheme.colorScheme.background
    EbookPageTheme.LIGHT -> Color.White
    EbookPageTheme.DARK -> Color(0xFF101010)
    EbookPageTheme.SEPIA -> Color(0xFFFAF4E8)
}

private fun EbookPageTheme.displayName(): String = when (this) {
    EbookPageTheme.SYSTEM -> "跟随系统"
    EbookPageTheme.LIGHT -> "亮色"
    EbookPageTheme.DARK -> "深色"
    EbookPageTheme.SEPIA -> "纸张"
}

@OptIn(ExperimentalReadiumApi::class)
private fun EbookReaderSettings.toReadiumPreferences(systemDark: Boolean): EpubPreferences {
    val theme = when (pageTheme) {
        EbookPageTheme.DARK -> ReadiumTheme.DARK
        EbookPageTheme.SEPIA -> ReadiumTheme.SEPIA
        EbookPageTheme.SYSTEM -> if (systemDark) ReadiumTheme.DARK else ReadiumTheme.LIGHT
        EbookPageTheme.LIGHT -> ReadiumTheme.LIGHT
    }
    return EpubPreferences(
        scroll = true,
        fontSize = (fontSizeSp / 18f).toDouble(),
        lineHeight = lineHeight.toDouble(),
        pageMargins = (horizontalPaddingDp / 20f).toDouble(),
        publisherStyles = false,
        theme = theme,
        backgroundColor = ReadiumColor(theme.backgroundColor),
        textColor = ReadiumColor(theme.contentColor),
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReaderProgressSlider(
    progress: Float,
    enabled: Boolean,
    state: String,
    foreground: Color,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        thumbColor = foreground,
        activeTrackColor = foreground,
        inactiveTrackColor = foreground.copy(alpha = 0.18f),
    )
    Slider(
        value = progress.coerceIn(0f, 1f),
        onValueChange = onProgressChange,
        onValueChangeFinished = onProgressChangeFinished,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 12.dp)
            .semantics {
                contentDescription = "阅读进度"
                stateDescription = state
            },
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                modifier = Modifier.size(16.dp),
                colors = colors,
                enabled = enabled,
            )
        },
        track = { sliderState ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(foreground.copy(alpha = 0.18f), CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(sliderState.value.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(foreground, CircleShape),
                )
            }
        },
    )
}

internal fun readerProgressForIndex(index: Int, itemCount: Int): Float {
    if (itemCount <= 1) return 0f
    return index.coerceIn(0, itemCount - 1).toFloat() / (itemCount - 1).toFloat()
}

internal fun readerIndexForProgress(progress: Float, itemCount: Int): Int {
    if (itemCount <= 1) return 0
    return (progress.coerceIn(0f, 1f) * (itemCount - 1)).roundToInt()
}

internal fun readerScrollablePositionCount(itemCount: Int, visibleItemCount: Int): Int {
    if (itemCount <= 1) return 1
    val safeVisibleCount = visibleItemCount.coerceIn(1, itemCount)
    return itemCount - safeVisibleCount + 1
}

internal fun currentTxtChapterTitle(entries: List<Pair<Int, String>>, currentIndex: Int): String? =
    entries.lastOrNull { it.first <= currentIndex }?.second

private fun currentEpubTocEntry(entries: List<EpubTocEntry>, locator: Locator?): EpubTocEntry? {
    locator ?: return null
    val currentProgression = locator.locations.totalProgression
    val progressionMatch = currentProgression?.let { progress ->
        entries.lastOrNull { entry ->
            entry.locator.locations.totalProgression?.let { it <= progress } == true
        }
    }
    return progressionMatch ?: entries.lastOrNull {
        it.locator.href.substringBefore('#') == locator.href.substringBefore('#')
    }
}

private fun View.disableReaderOverscroll() {
    // long: Readium 会按章节动态挂载 WebView 和 ViewPager，逐层关闭边界效果才能覆盖当前及新建页面。
    overScrollMode = View.OVER_SCROLL_NEVER
    if (this is R2ViewPager) {
        setOnTouchListener { _, _ ->
            // long: 阅读页通过底部按钮翻章，直接消费 ViewPager 的触摸，防止横向拖动推进到下一资源。
            true
        }
    }
    if (this is R2BasicWebView) {
        setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // long: 正文接管整次触摸序列，避免父级 ViewPager 在横向拖动中途截走事件并停在半页位置。
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).disableReaderOverscroll()
        }
    }
}

@OptIn(ExperimentalReadiumApi::class)
private suspend fun EpubNavigatorFragment.awaitDocumentReady() {
    // long: Navigator 对象创建早于正文 HTML 提交，轮询 readyState 期间继续显示遮罩，避免透明 WebView 暴露成白屏。
    repeat(20) {
        val readyState = try {
            evaluateJavascript("document.readyState")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (readyState?.contains("complete") == true) return
        delay(50)
    }
}

private fun EpubNavigatorFragment.disableUserPaging() {
    // long: 直接处理 Readium 暴露的分页器实例，确保横向手势不会把正文切到下一资源。
    runCatching { resourcePager }
        .getOrNull()
        ?.setOnTouchListener { _, _ -> true }
}

@OptIn(ExperimentalReadiumApi::class)
private suspend fun EpubNavigatorFragment.lockHorizontalViewport() {
    try {
        evaluateJavascript(EPUB_HORIZONTAL_LOCK_SCRIPT)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Log.e(EPUB_READER_LOG_TAG, "锁定 EPUB 横向滚动失败", error)
    }
}

private const val EPUB_NAVIGATOR_TAG = "ebook_epub_navigator"
private const val EPUB_READER_LOG_TAG = "EbookReader"
private const val POSITION_SAVE_DEBOUNCE_MS = 700L

private const val EPUB_HORIZONTAL_LOCK_SCRIPT = """
    (() => {
        const root = document.scrollingElement;
        if (!root) return;

        // long: 横向拖动只应触发系统手势，正文滚动容器必须始终从左侧起排版。
        document.documentElement.style.setProperty("overflow-x", "hidden", "important");
        document.documentElement.style.setProperty("overscroll-behavior-x", "none", "important");
        document.documentElement.style.touchAction = "pan-y pinch-zoom";
        if (document.body) {
            document.body.style.setProperty("overflow-x", "hidden", "important");
            document.body.style.setProperty("overscroll-behavior-x", "none", "important");
            document.body.style.touchAction = "pan-y pinch-zoom";
        }

        // long: 出版物常把封面和插图写成固定像素尺寸，先缩放到设备视口内，避免用户靠横向拖动才能看全图。
        const imageStyleId = "__readerImageFit";
        if (!document.getElementById(imageStyleId)) {
            const imageStyle = document.createElement("style");
            imageStyle.id = imageStyleId;
            imageStyle.textContent = `
                .custom-cover, .readerChapterContent .frontCover {
                    width: 100% !important;
                    max-width: 100% !important;
                    box-sizing: border-box !important;
                    margin-left: 0 !important;
                    margin-right: 0 !important;
                }
                .custom-cover img, .readerChapterContent .frontCover img,
                .readerChapterContent img[data-w], .readerChapterContent img.qqreader-fullimg {
                    display: block !important;
                    max-width: 100% !important;
                    width: auto !important;
                    height: auto !important;
                    margin-left: auto !important;
                    margin-right: auto !important;
                    object-fit: contain !important;
                }
            `;
            (document.head || document.documentElement).appendChild(imageStyle);
        }

        const resetHorizontalOffset = () => {
            if (root.scrollLeft !== 0) root.scrollLeft = 0;
        };

        resetHorizontalOffset();
        window.removeEventListener("scroll", window.__readerHorizontalReset);
        window.__readerHorizontalReset = resetHorizontalOffset;
        window.addEventListener("scroll", resetHorizontalOffset, { passive: true });
    })();
"""
