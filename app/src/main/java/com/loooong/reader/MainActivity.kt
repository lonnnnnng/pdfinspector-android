package com.loooong.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.TablerIcons
import compose.icons.tablericons.FileText
import compose.icons.tablericons.Book
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.Edit
import compose.icons.tablericons.History
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Trash
import com.loooong.reader.engine.NodeKind
import com.loooong.reader.engine.findNode
import com.loooong.reader.ui.Dock
import com.loooong.reader.ui.ElementEditSheet
import com.loooong.reader.ui.FitMode
import com.loooong.reader.ui.InspectorDock
import com.loooong.reader.ui.InspectorPane
import com.loooong.reader.ui.InspectorToolbar
import com.loooong.reader.ui.LeafRect
import com.loooong.reader.ui.ObjectTransformOverlay
import com.loooong.reader.ui.PdfCanvas
import com.loooong.reader.ui.ReaderScreen
import com.loooong.reader.ui.SettingsScreen
import com.loooong.reader.ui.theme.InspectorTheme
import com.loooong.reader.ui.theme.ThemeState
import com.loooong.reader.ui.theme.rememberThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUri: Uri? = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null
        setContent {
            val themeState = rememberThemeState()
            InspectorTheme(
                mode = themeState.mode,
                dynamicColor = themeState.dynamic,
                accent = themeState.accent,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    InspectorScreen(initialUri = initialUri, themeState = themeState)
                }
            }
        }
    }
}

@Composable
fun InspectorScreen(
    initialUri: Uri? = null,
    themeState: ThemeState,
    viewModel: PdfDocumentViewModel = viewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state = viewModel.state
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var pendingMode by rememberSaveable { mutableStateOf(AppMode.EDIT) }
    var pendingLeaveTarget by rememberSaveable { mutableStateOf<PdfLeaveTarget?>(null) }
    var saveLeaveTarget by rememberSaveable { mutableStateOf<PdfLeaveTarget?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showInsertTextDialog by rememberSaveable { mutableStateOf(false) }
    var insertTextValue by rememberSaveable { mutableStateOf("") }

    fun openEbookReader() {
        context.startActivity(Intent(context, EbookActivity::class.java))
    }

    LaunchedEffect(initialUri) {
        viewModel.loadReaderLibrary(context)
        if (initialUri != null) viewModel.open(context, initialUri, AppMode.READ)
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.open(context, it, pendingMode) } }

    fun executeLeave(target: PdfLeaveTarget) {
        when (target) {
            PdfLeaveTarget.CLOSE_DOCUMENT -> {
                fullscreen = false
                viewModel.closeDocument()
            }
            PdfLeaveTarget.OPEN_EDIT_DOCUMENT -> {
                pendingMode = AppMode.EDIT
                openLauncher.launch(arrayOf("application/pdf"))
            }
            PdfLeaveTarget.OPEN_READ_DOCUMENT -> {
                pendingMode = AppMode.READ
                openLauncher.launch(arrayOf("application/pdf"))
            }
        }
    }

    fun requestLeave(target: PdfLeaveTarget) {
        if (state.busy != null) return
        when (val decision = decidePdfLeave(state.dirty, target)) {
            is PdfLeaveDecision.Proceed -> executeLeave(decision.target)
            is PdfLeaveDecision.Confirm -> pendingLeaveTarget = decision.target
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val target = saveLeaveTarget
        saveLeaveTarget = null
        if (uri != null) {
            viewModel.saveCopy(context, uri) { saved ->
                if (target != null) {
                    when (val decision = resolvePdfLeaveSave(target, saved)) {
                        is PdfLeaveDecision.Proceed -> {
                            pendingLeaveTarget = null
                            executeLeave(decision.target)
                        }
                        is PdfLeaveDecision.Confirm -> pendingLeaveTarget = decision.target
                    }
                }
            }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.insertImage(context, it) } }

    fun pickPdf(mode: AppMode) {
        requestLeave(
            if (mode == AppMode.EDIT) {
                PdfLeaveTarget.OPEN_EDIT_DOCUMENT
            } else {
                PdfLeaveTarget.OPEN_READ_DOCUMENT
            },
        )
    }

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val sideDockSupported = configuration.screenWidthDp >= 600
    var dock by remember(landscape, sideDockSupported) {
        mutableStateOf(if (landscape && sideDockSupported) Dock.SIDE else Dock.BOTTOM)
    }
    var transparent by rememberSaveable { mutableStateOf(false) }
    // long: 手机横屏改用更紧凑的底部面板范围，为画布和工具栏保留基本可操作高度。
    val minBottomHeight = if (landscape) 96.dp else 180.dp
    val maxBottomHeight = (
        configuration.screenHeightDp * if (landscape) 0.45f else 0.55f
    ).dp.coerceAtLeast(minBottomHeight)
    val minSideWidth = 220.dp
    val maxSideWidth = minOf(
        (configuration.screenWidthDp * 0.50f).dp,
        configuration.screenWidthDp.dp - 280.dp - 40.dp,
    ).coerceAtLeast(minSideWidth)
    var bottomHeight by remember(configuration.screenHeightDp, landscape) {
        mutableStateOf((configuration.screenHeightDp * 0.34f).dp.coerceIn(minBottomHeight, maxBottomHeight))
    }
    var sideWidth by remember(configuration.screenWidthDp) {
        mutableStateOf((configuration.screenWidthDp * 0.36f).dp.coerceIn(minSideWidth, maxSideWidth))
    }
    val density = LocalDensity.current
    val sizeDp = if (dock == Dock.BOTTOM) bottomHeight else sideWidth
    val onResize: (Float) -> Unit = { delta ->
        if (dock == Dock.BOTTOM) {
            bottomHeight = with(density) { (bottomHeight.toPx() - delta).toDp() }
                .coerceIn(minBottomHeight, maxBottomHeight)
        } else {
            sideWidth = with(density) { (sideWidth.toPx() - delta).toDp() }
                .coerceIn(minSideWidth, maxSideWidth)
        }
    }

    var fitMode by remember { mutableStateOf(FitMode.WIDTH) }
    LaunchedEffect(state.documentToken) { fitMode = FitMode.WIDTH }

    LaunchedEffect(fullscreen) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
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
            // long: 宿主组合销毁时主动恢复系统栏，避免下次进入页面继承残留的沉浸状态。
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    BackHandler(enabled = !showSettings && state.hasDocument) {
        if (state.busy != null) return@BackHandler
        if (fullscreen) {
            fullscreen = false
        } else {
            requestLeave(PdfLeaveTarget.CLOSE_DOCUMENT)
        }
    }

    // 首页已经没有更上一层页面，系统返回手势在真正结束 Activity 前必须再次确认。
    BackHandler(enabled = !showSettings && !state.hasDocument) {
        showExitConfirmation = true
    }

    if (showSettings) {
        SettingsScreen(
            theme = themeState,
            onBack = { showSettings = false },
        )
    } else if (state.hasDocument && state.mode == AppMode.READ) {
        key(state.documentToken) {
            ReaderScreen(
                viewModel = viewModel,
                state = state,
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                onClose = {
                    fullscreen = false
                    requestLeave(PdfLeaveTarget.CLOSE_DOCUMENT)
                },
                onOpen = { pickPdf(AppMode.READ) },
                onSettings = {
                    fullscreen = false
                    showSettings = true
                },
            )
        }
        state.busy?.let { BusyOverlay(it) }
    } else if (state.hasDocument) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    InspectorToolbar(
                        fileName = state.fileName,
                        fullscreen = fullscreen,
                        pageIndex = state.pageIndex,
                        pageCount = state.pageCount,
                        dirty = state.dirty,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        canCopy = state.selectedIds.size == 1,
                        onCopyElement = { viewModel.copySelectedElement(context) },
                        canPasteElement = state.hasElementClipboard,
                        onPasteElement = { viewModel.pasteElement(context) },
                        onPasteText = { viewModel.pasteText(context) },
                        onInsertText = {
                            insertTextValue = ""
                            showInsertTextDialog = true
                        },
                        onInsertImage = { imageLauncher.launch(arrayOf("image/*")) },
                        onFitWidth = { fitMode = FitMode.WIDTH },
                        onFitHeight = { fitMode = FitMode.HEIGHT },
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        onPrev = { viewModel.showPage(state.pageIndex - 1) },
                        onNext = { viewModel.showPage(state.pageIndex + 1) },
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                        onOpen = { pickPdf(AppMode.EDIT) },
                        onSave = { saveLauncher.launch("已编辑.pdf") },
                        onSettings = {
                            fullscreen = false
                            showSettings = true
                        },
                    )
                },
            ) { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                ) {
                    Workspace(
                        viewModel = viewModel,
                        state = state,
                        dock = dock,
                        transparent = transparent,
                        sizeDp = sizeDp,
                        canDockSide = sideDockSupported,
                        onResize = onResize,
                        fitMode = fitMode,
                        onUserTransform = { fitMode = FitMode.NONE },
                        onToggleDock = {
                            dock = if (dock == Dock.BOTTOM && sideDockSupported) Dock.SIDE else Dock.BOTTOM
                        },
                        onToggleTransparent = {
                            transparent = !transparent
                            fitMode = FitMode.NONE
                        },
                        onDelete = { showDeleteConfirmation = true },
                    )
                }
            }
            state.busy?.let { BusyOverlay(it) }
        }
    } else {
        HomeScreen(
            loading = state.loading,
            error = state.error,
            history = viewModel.readerHistory,
            onOpenEdit = { pickPdf(AppMode.EDIT) },
            onOpenRead = { pickPdf(AppMode.READ) },
            onOpenEbook = ::openEbookReader,
            onOpenHistory = { viewModel.openHistory(context, it) },
            onRemoveHistory = { viewModel.removeReaderHistory(context, it) },
            onSettings = { showSettings = true },
        )
    }

    if (!showSettings && state.mode == AppMode.EDIT && state.editingId != null) {
        val target = remember(state.editingId, state.page, state.fontCatalogTick) {
            viewModel.editTarget()
        }
        if (target != null) {
            ElementEditSheet(
                target = target,
                onApply = { req -> viewModel.applyEdit(context, req) },
                onImportFont = { uri -> viewModel.importFont(context, uri) },
                onDismiss = { viewModel.cancelEdit() },
            )
        }
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("退出阅读？") },
            text = { Text("确定要退出应用吗？") },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmation = false
                        (context as? Activity)?.finish()
                    },
                ) {
                    Text("退出")
                }
            },
        )
    }

    pendingLeaveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingLeaveTarget = null },
            title = { Text("保存当前修改？") },
            text = {
                Text(
                    if (target == PdfLeaveTarget.CLOSE_DOCUMENT) {
                        "当前 PDF 有尚未保存的修改，关闭前可以先保存副本。"
                    } else {
                        "当前 PDF 有尚未保存的修改，打开其他文件前可以先保存副本。"
                    },
                )
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { pendingLeaveTarget = null }) {
                        Text("取消")
                    }
                    TextButton(
                        onClick = {
                            pendingLeaveTarget = null
                            executeLeave(target)
                        },
                    ) {
                        Text("不保存")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveLeaveTarget = target
                        saveLauncher.launch("已编辑.pdf")
                    },
                ) {
                    Text("保存副本")
                }
            },
        )
    }

    if (showDeleteConfirmation) {
        val selectedLabel = if (state.selectedIds.size > 1) {
            "${state.selectedIds.size} 个元素"
        } else {
            state.page
                ?.let { findNode(it.root, state.selectedId) }
                ?.label
                ?.takeIf { it.isNotBlank() }
                ?: "当前元素"
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除元素？") },
            text = { Text("将删除“$selectedLabel”，此操作可以使用撤销恢复。") },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteSelected(context)
                    },
                ) { Text("删除") }
            },
        )
    }

    if (showInsertTextDialog) {
        AlertDialog(
            onDismissRequest = { showInsertTextDialog = false },
            title = { Text("插入文本") },
            text = {
                OutlinedTextField(
                    value = insertTextValue,
                    onValueChange = { insertTextValue = it },
                    label = { Text("文本内容") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissButton = {
                TextButton(onClick = { showInsertTextDialog = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = insertTextValue.isNotBlank(),
                    onClick = {
                        val text = insertTextValue
                        showInsertTextDialog = false
                        viewModel.insertTextAtCenter(context, text)
                    },
                ) { Text("插入") }
            },
        )
    }
}

@Composable
private fun Workspace(
    viewModel: PdfDocumentViewModel,
    state: PdfUiState,
    dock: Dock,
    transparent: Boolean,
    sizeDp: Dp,
    canDockSide: Boolean,
    onResize: (Float) -> Unit,
    fitMode: FitMode,
    onUserTransform: () -> Unit,
    onToggleDock: () -> Unit,
    onToggleTransparent: () -> Unit,
    onDelete: () -> Unit,
) {
    val page = state.page ?: return
    val transform = state.pageTransform

    val context = LocalContext.current
    val leafRects = remember(page, transform) {
        if (transform != null) {
            page.leaves.mapNotNull { n -> n.bounds?.let { LeafRect(n.id, transform.toRect(it)) } }
        } else {
            emptyList()
        }
    }
    // Every Tj/TJ run inside a BT..ET, boxed so it can be tapped and retyped.
    val runBoxes = remember(page, transform) {
        if (transform == null) {
            emptyList()
        } else {
            page.leaves
                .asSequence()
                .filter { it.kind == NodeKind.TEXT }
                .flatMap { it.children.asSequence() }
                .filter { it.kind == NodeKind.TEXT && !it.text.isNullOrBlank() && it.bounds != null }
                .map { LeafRect(it.id, transform.toRect(it.bounds!!)) }
                .toList()
        }
    }
    val selectedRects = remember(page, transform, state.selectedIds) {
        if (transform == null) emptyList() else state.selectedIds.mapNotNull { id ->
            findNode(page.root, id)?.bounds?.let(transform::toRect)
        }
    }
    val selectedRect = selectedRects.singleOrNull()
    val selectionOverlayRect = selectedRects.takeIf { it.isNotEmpty() }?.let { rects ->
        Rect(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom },
        )
    }
    val selectedCanTransform = remember(page, state.selectedIds) {
        if (state.selectedIds.size == 1) {
            state.selectedId?.let(viewModel::canTransform) == true
        } else {
            viewModel.canTransformSelection()
        }
    }

    val highlight = MaterialTheme.colorScheme.primary
    val backdrop = MaterialTheme.colorScheme.surfaceVariant
    val runBoxColor = MaterialTheme.colorScheme.outline
    var editingRunId by remember(page) { mutableStateOf<Int?>(null) }
    val bmp = state.bitmap

    // Held here, not inside PdfCanvas, so zoom/pan persist when the canvas moves
    // between the docked and transparent layouts.
    val scaleState = remember { mutableStateOf(1f) }
    val offsetState = remember { mutableStateOf(Offset.Zero) }

    // Last font-match decision, kept for the debug overlay; refreshed on each tap.
    var fontDebug by remember(page) { mutableStateOf<FontCatalog.MatchExplain?>(null) }
    LaunchedEffect(editingRunId, state.fontCatalogTick) {
        editingRunId?.let { fontDebug = viewModel.fontDecisionFor(it) }
    }
    val fontDebugRows: List<Pair<String, String>> = fontDebug?.let { e ->
        buildList {
            val matchStep = when (e.step) {
                "tex" -> "TeX 字体"
                "alias" -> "字体别名"
                "system" -> "系统字体"
                "width" -> "字宽特征"
                "panose" -> "PANOSE 特征"
                "none" -> "未匹配"
                else -> e.step
            }
            add("字体" to if (e.original == "(none)") "无" else e.original)
            add("匹配" to matchStep + if (e.confident) "" else "（低置信度）")
            add("选择" to (e.match ?: "-"))
            e.detail?.let { add("宽度" to it) }
        }
    } ?: emptyList()

    val canvas: @Composable (Modifier) -> Unit = { mod ->
        if (bmp != null) {
            Box(mod) {
                PdfCanvas(
                    bitmap = bmp,
                    pageIndex = state.pageIndex,
                    scaleState = scaleState,
                    offsetState = offsetState,
                    leaves = leafRects,
                    selectedRects = if (selectedCanTransform && editingRunId == null) emptyList() else selectedRects,
                    highlightColor = highlight,
                    backdropColor = backdrop,
                    runBoxes = runBoxes,
                    editingRunId = editingRunId,
                    textBoxColor = runBoxColor,
                    onEditRun = { id -> editingRunId = id },
                    debugRows = fontDebugRows,
                    fitMode = fitMode,
                    onUserTransform = onUserTransform,
                    onSelect = { id -> viewModel.select(id, reveal = true) },
                    onToggleSelect = { id -> viewModel.toggleSelection(id, reveal = true) },
                    renderTile = { idx, src, outW, outH ->
                        viewModel.renderRegion(idx, src.left, src.top, src.right, src.bottom, outW, outH)
                            ?.asImageBitmap()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                val selectedId = state.selectedId
                if (
                    selectedId != null && selectionOverlayRect != null && transform != null &&
                    selectedCanTransform && editingRunId == null
                ) {
                    ObjectTransformOverlay(
                        objectId = state.selectedIds.hashCode(),
                        rect = selectionOverlayRect,
                        canvasScale = scaleState.value,
                        canvasOffset = offsetState.value,
                        color = highlight,
                        enabled = state.busy == null,
                        onCommit = { translationBitmap, scale, screenRotation ->
                            val userDelta = transform.toUserDelta(translationBitmap)
                            if (state.selectedIds.size == 1) {
                                viewModel.applyCanvasTransform(
                                    context = context,
                                    id = selectedId,
                                    dx = userDelta.x,
                                    dy = userDelta.y,
                                    scale = scale,
                                    rotationDegrees = transform.toUserRotation(screenRotation),
                                )
                            } else {
                                viewModel.applyCanvasTransformSelection(
                                    context = context,
                                    dx = userDelta.x,
                                    dy = userDelta.y,
                                    scale = scale,
                                    rotationDegrees = transform.toUserRotation(screenRotation),
                                )
                            }
                        },
                    )
                }
                val er = editingRunId
                val run = if (er != null && transform != null) findNode(page.root, er) else null
                val rect = run?.bounds?.let { transform?.toRect(it) }
                if (er != null && run != null && rect != null) {
                    val fontFamily = remember(er) {
                        when (val face = viewModel.inlineFontFace(er)) {
                            is FontCatalog.FaceSource.Asset ->
                                FontFamily(Font(path = face.path, assetManager = context.assets))
                            is FontCatalog.FaceSource.FileFace -> FontFamily(Font(file = face.file))
                            null -> null
                        }
                    }
                    val fontScale = remember(er, state.fontCatalogTick) {
                        viewModel.inlineFontScale(er)
                    }
                    InlineTextEditor(
                        runId = er,
                        rect = rect,
                        scale = scaleState.value,
                        offset = offsetState.value,
                        initial = run.text ?: "",
                        textColor = run.colorArgb?.let { Color(it) } ?: Color.Black,
                        fontFamily = fontFamily,
                        fontScale = fontScale,
                        onCommit = { newText ->
                            editingRunId = null
                            viewModel.applyInlineText(context, er, newText)
                        },
                    )
                }
            }
        } else {
            Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
    val pane: @Composable () -> Unit = {
        InspectorPane(
            page = page,
            expanded = state.expanded,
            selectedId = state.selectedId,
            selectedIds = state.selectedIds,
            swatchColors = state.swatchColors,
            revealTick = state.revealTick,
            showRaw = state.showRaw,
            canDelete = state.selectedIds.isNotEmpty(),
            dock = dock,
            transparent = transparent,
            canDockSide = canDockSide,
            onSelect = { id -> viewModel.select(id) },
            onToggleSelect = { id -> viewModel.toggleSelection(id, reveal = true) },
            onToggleExpand = { id -> viewModel.toggleExpand(id) },
            onToggleRaw = { viewModel.toggleRaw() },
            onToggleDock = onToggleDock,
            onToggleTransparent = onToggleTransparent,
            onDelete = onDelete,
            onEdit = { id -> viewModel.beginEdit(id) },
            onAlign = { action -> viewModel.alignSelected(context, action) },
            onReorder = { action -> viewModel.reorderSelected(context, action) },
        )
    }

    if (transparent) {
        Box(Modifier.fillMaxSize()) {
            canvas(Modifier.fillMaxSize())
            InspectorDock(
                dock = dock,
                transparent = true,
                sizeDp = sizeDp,
                onResizePx = onResize,
                modifier = Modifier
                    .align(if (dock == Dock.SIDE) Alignment.CenterEnd else Alignment.BottomCenter)
                    .then(if (dock == Dock.SIDE) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()),
            ) { pane() }
        }
    } else if (dock == Dock.SIDE) {
        Row(Modifier.fillMaxSize()) {
            canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            InspectorDock(dock, false, sizeDp, onResize, Modifier.fillMaxHeight()) { pane() }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            canvas(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            InspectorDock(dock, false, sizeDp, onResize, Modifier.fillMaxWidth()) { pane() }
        }
    }
}

// A text field pinned over the tapped run. Position and size follow the live
// zoom/pan so it sits exactly on the glyphs; committing hands the new string to
// the same run rewrite the edit sheet uses. The scrim commits on a tap away.
@Composable
private fun InlineTextEditor(
    runId: Int,
    rect: Rect,
    scale: Float,
    offset: Offset,
    initial: String,
    textColor: Color,
    fontFamily: FontFamily?,
    fontScale: Float,
    onCommit: (String) -> Unit,
) {
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    var value by remember(runId) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val left = rect.left * scale + offset.x
    val top = rect.top * scale + offset.y
    val widthPx = (rect.width * scale).coerceAtLeast(32f)
    val heightPx = (rect.height * scale).coerceAtLeast(22f)
    // The run box is the text's em (ascent 0.75 to descent -0.25), so the font
    // fills it and the baseline rests 75% down. Bottom aligning a naturally
    // sized field lands the glyphs on that line with no top padding or clipping.
    val fontPx = (rect.height * scale * fontScale).coerceAtLeast(14f)
    val fontSp = with(density) { fontPx.toSp() }
    val surfaceIsLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val textIsDark = textColor.luminance() < 0.5f
    // long: 编辑浮层必须同时适配 PDF 原文字色和应用主题，避免暗色模式下黑字落在深底或白字落在浅底。
    val editorBackground = if (surfaceIsLight == textIsDark) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.inverseSurface
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(runId) { detectTapGestures { onCommit(value.text) } },
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                .background(editorBackground)
                .border(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = fontSp,
                    fontFamily = fontFamily,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                cursorBrush = SolidColor(textColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit(value.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                    .focusRequester(focusRequester),
            )
        }
    }
    LaunchedEffect(runId) { focusRequester.requestFocus() }
}

@Composable
private fun HomeScreen(
    loading: Boolean,
    error: String?,
    history: List<ReaderHistoryEntry>,
    onOpenEdit: () -> Unit,
    onOpenRead: () -> Unit,
    onOpenEbook: () -> Unit,
    onOpenHistory: (ReaderHistoryEntry) -> Unit,
    onRemoveHistory: (ReaderHistoryEntry) -> Unit,
    onSettings: () -> Unit,
) {
    var pendingHistoryRemoval by remember { mutableStateOf<ReaderHistoryEntry?>(null) }
    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(64.dp)
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSettings) {
                    Icon(TablerIcons.Settings, contentDescription = "设置", modifier = Modifier.size(22.dp))
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "正在准备文档…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 20.dp,
                        vertical = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Column {
                            Text("打开文档", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "继续阅读，或从设备中选择文件",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (error != null) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        error,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = onOpenRead) {
                                        Text("选择文件")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        PrimaryModeCard(
                            icon = { Icon(TablerIcons.Book, contentDescription = null) },
                            title = "阅读 PDF",
                            description = "连续浏览、搜索、目录与书签",
                            onClick = onOpenRead,
                        )
                    }
                    item {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            // long: 窄屏和大字号环境下双列入口容易挤压标题，改为单列后仍保留完整触控区域和信息层级。
                            if (maxWidth < 320.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CompactModeCard(
                                        icon = { Icon(TablerIcons.Edit, contentDescription = null) },
                                        title = "编辑 PDF",
                                        detail = "编辑文档内容",
                                        onClick = onOpenEdit,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    CompactModeCard(
                                        icon = { Icon(TablerIcons.FileText, contentDescription = null) },
                                        title = "电子书",
                                        detail = "EPUB · TXT",
                                        onClick = onOpenEbook,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CompactModeCard(
                                        icon = { Icon(TablerIcons.Edit, contentDescription = null) },
                                        title = "编辑 PDF",
                                        detail = "编辑文档内容",
                                        onClick = onOpenEdit,
                                        modifier = Modifier.weight(1f),
                                    )
                                    CompactModeCard(
                                        icon = { Icon(TablerIcons.FileText, contentDescription = null) },
                                        title = "电子书",
                                        detail = "EPUB · TXT",
                                        onClick = onOpenEbook,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(
                                TablerIcons.History,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("阅读历史", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (history.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Text(
                                    "暂无 PDF 阅读记录",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(history, key = { it.uri }) { entry ->
                            HistoryItem(
                                entry = entry,
                                onClick = { onOpenHistory(entry) },
                                onDelete = { pendingHistoryRemoval = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingHistoryRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingHistoryRemoval = null },
            title = { Text("移除阅读历史") },
            text = { Text("将移除该 PDF 的阅读记录和书签，不会删除原始文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingHistoryRemoval = null
                        onRemoveHistory(entry)
                    },
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingHistoryRemoval = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PrimaryModeCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
            }
            Icon(TablerIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun CompactModeCard(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 120.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryItem(
    entry: ReaderHistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
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
                    TablerIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(
                        "上次阅读到第 ${entry.pageIndex + 1} 页",
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

@Composable
private fun BusyOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = message
                    liveRegion = LiveRegionMode.Polite
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
