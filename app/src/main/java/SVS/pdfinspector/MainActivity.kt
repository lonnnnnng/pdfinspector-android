package SVS.pdfinspector

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import compose.icons.TablerIcons
import compose.icons.tablericons.FileText
import compose.icons.tablericons.Folder
import compose.icons.tablericons.Settings
import SVS.pdfinspector.engine.NodeKind
import SVS.pdfinspector.engine.findNode
import SVS.pdfinspector.ui.Dock
import SVS.pdfinspector.ui.ElementEditSheet
import SVS.pdfinspector.ui.FitMode
import SVS.pdfinspector.ui.InspectorDock
import SVS.pdfinspector.ui.InspectorPane
import SVS.pdfinspector.ui.InspectorToolbar
import SVS.pdfinspector.ui.LeafRect
import SVS.pdfinspector.ui.PdfCanvas
import SVS.pdfinspector.ui.ThemeSettingsSheet
import SVS.pdfinspector.ui.theme.InspectorTheme
import SVS.pdfinspector.ui.theme.ThemeState
import SVS.pdfinspector.ui.theme.rememberThemeState

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
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(initialUri) {
        if (initialUri != null) viewModel.open(context, initialUri)
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.open(context, it) } }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> uri?.let { viewModel.saveCopy(context, it) } }

    fun pickPdf() = openLauncher.launch(arrayOf("application/pdf"))

    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    var dock by remember(landscape) { mutableStateOf(if (landscape) Dock.SIDE else Dock.BOTTOM) }
    var transparent by rememberSaveable { mutableStateOf(false) }
    var bottomHeight by remember { mutableStateOf(300.dp) }
    var sideWidth by remember { mutableStateOf(340.dp) }
    val density = LocalDensity.current
    val sizeDp = if (dock == Dock.BOTTOM) bottomHeight else sideWidth
    val onResize: (Float) -> Unit = { delta ->
        if (dock == Dock.BOTTOM) {
            bottomHeight = with(density) { (bottomHeight.toPx() - delta).toDp() }.coerceIn(140.dp, 600.dp)
        } else {
            sideWidth = with(density) { (sideWidth.toPx() - delta).toDp() }.coerceIn(240.dp, 600.dp)
        }
    }

    var fullscreen by remember { mutableStateOf(false) }
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

    BackHandler(enabled = state.hasDocument) {
        fullscreen = false
        viewModel.closeDocument()
    }

    val copyText = state.page
        ?.let { findNode(it.root, state.selectedId) }
        ?.takeIf { it.kind == NodeKind.TEXT }
        ?.text

    if (state.hasDocument) {
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
                    copyText = copyText,
                    onCopyText = { viewModel.copySelectedText(context) },
                    onFitWidth = { fitMode = FitMode.WIDTH },
                    onFitHeight = { fitMode = FitMode.HEIGHT },
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onPrev = { viewModel.showPage(state.pageIndex - 1) },
                    onNext = { viewModel.showPage(state.pageIndex + 1) },
                    onUndo = { viewModel.undo() },
                    onRedo = { viewModel.redo() },
                    onOpen = { pickPdf() },
                    onSave = { saveLauncher.launch("inspected.pdf") },
                    onSettings = { showSettings = true },
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
                    onResize = onResize,
                    fitMode = fitMode,
                    onUserTransform = { fitMode = FitMode.NONE },
                    onToggleDock = { dock = if (dock == Dock.BOTTOM) Dock.SIDE else Dock.BOTTOM },
                    onToggleTransparent = { transparent = !transparent },
                )
                state.busy?.let { BusyOverlay(it) }
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator()
                    state.error != null -> Text("Error: ${state.error}")
                    else -> EmptyState(onOpen = ::pickPdf)
                }
            }
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(8.dp),
            ) {
                Icon(TablerIcons.Settings, contentDescription = "Settings", modifier = Modifier.size(22.dp))
            }
        }
    }

    if (showSettings) {
        ThemeSettingsSheet(theme = themeState, onDismiss = { showSettings = false })
    }

    if (state.editingId != null) {
        val target = remember(state.editingId, state.page) { viewModel.editTarget() }
        if (target != null) {
            ElementEditSheet(
                target = target,
                onApply = { req -> viewModel.applyEdit(context, req) },
                onDismiss = { viewModel.cancelEdit() },
            )
        }
    }
}

@Composable
private fun Workspace(
    viewModel: PdfDocumentViewModel,
    state: PdfUiState,
    dock: Dock,
    transparent: Boolean,
    sizeDp: Dp,
    onResize: (Float) -> Unit,
    fitMode: FitMode,
    onUserTransform: () -> Unit,
    onToggleDock: () -> Unit,
    onToggleTransparent: () -> Unit,
) {
    val page = state.page ?: return
    val transform = state.pageTransform

    val leafRects = remember(page, transform) {
        if (transform != null) {
            page.leaves.mapNotNull { n -> n.bounds?.let { LeafRect(n.id, transform.toRect(it)) } }
        } else {
            emptyList()
        }
    }
    val selectedRect = remember(page, transform, state.selectedId) {
        val node = findNode(page.root, state.selectedId)
        val bounds = node?.bounds
        if (bounds != null && transform != null) transform.toRect(bounds) else null
    }

    val highlight = MaterialTheme.colorScheme.primary
    val backdrop = MaterialTheme.colorScheme.surfaceVariant
    val bmp = state.bitmap

    val canvas: @Composable (Modifier) -> Unit = { mod ->
        if (bmp != null) {
            PdfCanvas(
                bitmap = bmp,
                leaves = leafRects,
                selectedRect = selectedRect,
                highlightColor = highlight,
                backdropColor = backdrop,
                fitMode = fitMode,
                onUserTransform = onUserTransform,
                onSelect = { id -> viewModel.select(id, reveal = true) },
                modifier = mod,
            )
        } else {
            Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
    val pane: @Composable () -> Unit = {
        InspectorPane(
            page = page,
            expanded = state.expanded,
            selectedId = state.selectedId,
            swatchColors = state.swatchColors,
            revealTick = state.revealTick,
            showRaw = state.showRaw,
            canDelete = state.selectedId != null,
            dock = dock,
            transparent = transparent,
            onSelect = { id -> viewModel.select(id) },
            onToggleExpand = { id -> viewModel.toggleExpand(id) },
            onToggleRaw = { viewModel.toggleRaw() },
            onToggleDock = onToggleDock,
            onToggleTransparent = onToggleTransparent,
            onDelete = { viewModel.deleteSelected() },
            onEdit = { id -> viewModel.beginEdit(id) },
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

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Icon(
            imageVector = TablerIcons.FileText,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Inspect any PDF", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Open a document to explore its elements, select them on the page or in the tree, and delete what you don't need.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onOpen) {
            Icon(TablerIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open a PDF")
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
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
