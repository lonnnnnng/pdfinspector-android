package SVS.pdfinspector.ui

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commitNow
import compose.icons.TablerIcons
import compose.icons.tablericons.Bookmarks
import compose.icons.tablericons.ChevronLeft
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.CloudDownload
import compose.icons.tablericons.FileText
import compose.icons.tablericons.Search
import compose.icons.tablericons.Settings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.readium.r2.navigator.ExperimentalDecorator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import SVS.pdfinspector.EbookActivity
import SVS.pdfinspector.EpubEbookDocument
import SVS.pdfinspector.EbookFormat
import SVS.pdfinspector.EbookHistoryEntry
import SVS.pdfinspector.EbookPageTheme
import SVS.pdfinspector.EbookReaderSettings
import SVS.pdfinspector.EbookScreen
import SVS.pdfinspector.EpubTocEntry
import SVS.pdfinspector.EbookViewModel
import SVS.pdfinspector.TxtEbookDocument
import SVS.pdfinspector.txtTableOfContents

@Composable
fun EbookApp(
    activity: EbookActivity,
    viewModel: EbookViewModel,
    initialUri: android.net.Uri?,
    onChooseFile: () -> Unit,
) {
    LaunchedEffect(initialUri) {
        if (initialUri != null && viewModel.screen is EbookScreen.Library) {
            viewModel.openUri(activity, initialUri)
        }
    }

    BackHandler {
        when (viewModel.screen) {
            EbookScreen.Library, is EbookScreen.Error -> activity.finish()
            is EbookScreen.Loading -> viewModel.showLibrary()
            is EbookScreen.Txt, is EbookScreen.Epub -> viewModel.showLibrary()
        }
    }

    when (val screen = viewModel.screen) {
        EbookScreen.Library -> EbookHomeScreen(
            history = viewModel.history,
            onChooseFile = onChooseFile,
            onOpenHistory = { viewModel.openHistory(activity, it) },
            onOpenOnline = { viewModel.openOnline(activity, it) },
            onBack = { activity.finish() },
        )
        is EbookScreen.Loading -> EbookLoadingScreen(screen.message)
        is EbookScreen.Error -> EbookHomeScreen(
            history = viewModel.history,
            error = screen.message,
            onChooseFile = onChooseFile,
            onOpenHistory = { viewModel.openHistory(activity, it) },
            onOpenOnline = { viewModel.openOnline(activity, it) },
            onBack = { activity.finish() },
        )
        is EbookScreen.Txt -> TxtReaderScreen(
            document = screen.document,
            viewModel = viewModel,
            onBack = viewModel::showLibrary,
        )
        is EbookScreen.Epub -> EpubReaderScreen(
            document = screen.document,
            viewModel = viewModel,
            onBack = viewModel::showLibrary,
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
    onOpenOnline: (String) -> Unit,
    onBack: () -> Unit,
) {
    var onlineUrl by remember { mutableStateOf("") }
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            TablerIcons.FileText,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("打开 EPUB 或 TXT", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "支持本地文件、系统文件提供商和 HTTPS 地址",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
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
                Button(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(TablerIcons.FileText, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择本地电子书")
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = onlineUrl,
                        onValueChange = { onlineUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("HTTPS 在线地址") },
                        placeholder = { Text("https://example.com/book.epub") },
                        leadingIcon = { Icon(TablerIcons.CloudDownload, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onOpenOnline(onlineUrl) }),
                    )
                    OutlinedButton(
                        onClick = { onOpenOnline(onlineUrl) },
                        enabled = onlineUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("打开在线电子书")
                    }
                }
            }
            if (history.isNotEmpty()) {
                item {
                    Text("最近阅读", style = MaterialTheme.typography.titleMedium)
                }
                items(history, key = { it.sourceId }) { entry ->
                    EbookHistoryRow(entry = entry, onClick = { onOpenHistory(entry) })
                }
            }
        }
    }
}

@Composable
private fun EbookHistoryRow(entry: EbookHistoryEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
    ) {
        ListItem(
            leadingContent = { Icon(TablerIcons.Bookmarks, contentDescription = null) },
            headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(if (entry.format == EbookFormat.EPUB) "EPUB · 最近阅读" else "TXT · 最近阅读")
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TxtReaderScreen(
    document: TxtEbookDocument,
    viewModel: EbookViewModel,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = document.initialParagraphIndex.coerceIn(
            0,
            (document.paragraphs.size - 1).coerceAtLeast(0),
        ),
    )
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<TxtPanel?>(null) }
    var query by remember { mutableStateOf("") }
    val currentIndex by remember(document.sourceId) {
        derivedStateOf {
            listState.firstVisibleItemIndex.coerceIn(0, (document.paragraphs.size - 1).coerceAtLeast(0))
        }
    }
    val progress = ((currentIndex + 1).toFloat() / document.paragraphs.size.coerceAtLeast(1)).coerceIn(0f, 1f)

    LaunchedEffect(listState, document.sourceId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { viewModel.saveTxtPosition(document, it) }
    }

    ReaderSurface(viewModel.settings) {
        val readerBackground = readerBackgroundColor(viewModel.settings)
        val readerForeground = readerContentColor(viewModel.settings)
        Scaffold(
            containerColor = readerBackground,
            contentColor = readerForeground,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "TXT · ${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(TablerIcons.ChevronLeft, contentDescription = "返回电子书首页")
                        }
                    },
                    actions = {
                        IconButton(onClick = { panel = TxtPanel.SEARCH }) {
                            Icon(TablerIcons.Search, contentDescription = "搜索正文")
                        }
                        IconButton(onClick = { panel = TxtPanel.TOC }) {
                            Icon(TablerIcons.Bookmarks, contentDescription = "打开目录")
                        }
                        IconButton(onClick = { panel = TxtPanel.SETTINGS }) {
                            Icon(TablerIcons.Settings, contentDescription = "阅读设置")
                        }
                    },
                )
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("第 ${currentIndex + 1} 段 / ${document.paragraphs.size} 段", style = MaterialTheme.typography.labelMedium)
                        Text("可选择复制正文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
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
    }

    when (panel) {
        TxtPanel.SEARCH -> EbookSearchSheet(
            query = query,
            onQueryChange = { query = it; viewModel.searchTxt(document, it) },
            searching = false,
            error = null,
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
            document = document,
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
    onBack: () -> Unit,
) {
    val activity = LocalContext.current as? FragmentActivity
    val containerId = remember { SVS.pdfinspector.R.id.epub_navigator_container }
    var navigator by remember(document.sourceId) { mutableStateOf<EpubNavigatorFragment?>(null) }
    var currentLocator by remember(document.sourceId) { mutableStateOf(document.initialLocator) }
    var panel by remember { mutableStateOf<EpubPanel?>(null) }
    var query by remember { mutableStateOf("") }
    var containerReady by remember(document.sourceId) { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
    val progress = currentLocator?.locations?.totalProgression?.toFloat()?.coerceIn(0f, 1f) ?: 0f

    if (activity != null && containerReady && !LocalInspectionMode.current) {
        DisposableEffect(document.sourceId, activity, containerReady) {
            val fragmentManager = activity.supportFragmentManager
            fragmentManager.fragmentFactory = document.navigatorFactory.createFragmentFactory(
                initialLocator = document.initialLocator,
                initialPreferences = viewModel.settings.toReadiumPreferences(systemDark),
                listener = object : EpubNavigatorFragment.Listener {},
                configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false),
            )
            val existing = fragmentManager.findFragmentByTag(EPUB_NAVIGATOR_TAG) as? EpubNavigatorFragment
            if (existing != null) {
                navigator = existing
            } else {
                fragmentManager.commitNow {
                    add(containerId, EpubNavigatorFragment::class.java, Bundle(), EPUB_NAVIGATOR_TAG)
                }
                navigator = fragmentManager.findFragmentByTag(EPUB_NAVIGATOR_TAG) as? EpubNavigatorFragment
            }
            onDispose {
                navigator = null
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
        navigator?.currentLocator?.collect {
            currentLocator = it
            viewModel.saveEpubPosition(document, it)
        }
    }
    LaunchedEffect(navigator, viewModel.settings, systemDark) {
        navigator?.submitPreferences(viewModel.settings.toReadiumPreferences(systemDark))
    }

    ReaderSurface(viewModel.settings) {
        val readerBackground = readerBackgroundColor(viewModel.settings)
        val readerForeground = readerContentColor(viewModel.settings)
        Scaffold(
            containerColor = readerBackground,
            contentColor = readerForeground,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "EPUB · ${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(TablerIcons.ChevronLeft, contentDescription = "返回电子书首页")
                        }
                    },
                    actions = {
                        IconButton(onClick = { panel = EpubPanel.SEARCH }) {
                            Icon(TablerIcons.Search, contentDescription = "搜索正文")
                        }
                        IconButton(onClick = { panel = EpubPanel.TOC }) {
                            Icon(TablerIcons.Bookmarks, contentDescription = "打开目录")
                        }
                        IconButton(onClick = { panel = EpubPanel.SETTINGS }) {
                            Icon(TablerIcons.Settings, contentDescription = "阅读设置")
                        }
                    },
                )
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            onClick = { navigator?.goBackward() },
                            enabled = navigator != null,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(TablerIcons.ChevronLeft, contentDescription = "上一页") }
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        IconButton(
                            onClick = { navigator?.goForward() },
                            enabled = navigator != null,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(TablerIcons.ChevronRight, contentDescription = "下一页") }
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
                    modifier = Modifier.fillMaxSize(),
                )
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
    txtResults: List<SVS.pdfinspector.EbookSearchResult>,
    epubResults: List<Locator>,
    onSelectTxt: (Int) -> Unit,
    onSelectEpub: (Locator) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
        ) {
            Text("搜索正文", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("输入关键词") },
                leadingIcon = { Icon(TablerIcons.Search, contentDescription = null) },
            )
            Spacer(Modifier.height(8.dp))
            when {
                searching -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                txtResults.isEmpty() && epubResults.isEmpty() && query.isNotBlank() ->
                    Text("没有找到匹配内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                ) {
                    items(txtResults, key = { it.paragraphIndex }) { result ->
                        ListItem(
                            headlineContent = { Text("第 ${result.paragraphIndex + 1} 段") },
                            supportingContent = { Text(result.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth(),
                            leadingContent = { Icon(TablerIcons.FileText, contentDescription = null) },
                            trailingContent = { IconButton(onClick = { onSelectTxt(result.paragraphIndex) }) {
                                Icon(TablerIcons.ChevronRight, contentDescription = "跳转")
                            } },
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
                            modifier = Modifier.fillMaxWidth(),
                            leadingContent = { Icon(TablerIcons.FileText, contentDescription = null) },
                            trailingContent = { IconButton(onClick = { onSelectEpub(result) }) {
                                Icon(TablerIcons.ChevronRight, contentDescription = "跳转")
                            } },
                        )
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
    document: TxtEbookDocument,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = remember(document.sourceId) { txtTableOfContents(document.paragraphs) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            if (entries.isEmpty()) {
                Text("TXT 未提供章节目录", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(400.dp)) {
                    items(entries, key = { it.first }) { (index, title) ->
                        ListItem(
                            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("第 ${index + 1} 段") },
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(index) },
                            leadingContent = { Icon(TablerIcons.Bookmarks, contentDescription = null) },
                        )
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
    onSelect: (Locator) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("目录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            if (entries.isEmpty()) {
                Text("这本 EPUB 没有目录信息", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                    items(entries) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (entry.depth * 18).dp)
                                .clickable { onSelect(entry.locator) },
                            leadingContent = { Icon(TablerIcons.Bookmarks, contentDescription = null) },
                        )
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
            Text("阅读设置", style = MaterialTheme.typography.titleLarge)
            Text("字号 ${settings.fontSizeSp.toInt()}sp", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.fontSizeSp,
                onValueChange = { onSettingsChange(settings.copy(fontSizeSp = it)) },
                valueRange = 14f..32f,
                steps = 8,
            )
            Text("行距 ${"%.2f".format(settings.lineHeight)}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.lineHeight,
                onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
                valueRange = 1.2f..2.2f,
                steps = 9,
            )
            Text("页边距 ${settings.horizontalPaddingDp.toInt()}dp", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.horizontalPaddingDp,
                onValueChange = { onSettingsChange(settings.copy(horizontalPaddingDp = it)) },
                valueRange = 8f..40f,
                steps = 7,
            )
            Text("阅读主题", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EbookPageTheme.values().toList().chunked(2).forEach { rowThemes ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowThemes.forEach { theme ->
                            TextButton(
                                onClick = { onSettingsChange(settings.copy(pageTheme = theme)) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    when (theme) {
                                        EbookPageTheme.SYSTEM -> "跟随系统"
                                        EbookPageTheme.LIGHT -> "亮色"
                                        EbookPageTheme.DARK -> "深色"
                                        EbookPageTheme.SEPIA -> "纸张"
                                    },
                                    fontWeight = if (theme == settings.pageTheme) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
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

private const val EPUB_NAVIGATOR_TAG = "ebook_epub_navigator"
