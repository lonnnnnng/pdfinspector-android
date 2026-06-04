package SVS.pdfinspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import SVS.pdfinspector.engine.findNode
import SVS.pdfinspector.ui.InspectorPane
import SVS.pdfinspector.ui.LeafRect
import SVS.pdfinspector.ui.PdfCanvas
import SVS.pdfinspector.ui.theme.InspectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InspectorTheme {
                InspectorScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorScreen(viewModel: PdfDocumentViewModel = viewModel()) {
    val context = LocalContext.current
    val state = viewModel.state

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.open(context, it) } }

    fun pickPdf() = openLauncher.launch(arrayOf("application/pdf"))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Inspector") },
                actions = { TextButton(onClick = ::pickPdf) { Text("Open") } },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.loading && state.bitmap == null -> CircularProgressIndicator()
                state.error != null -> Text("Error: ${state.error}")
                state.hasDocument -> PageViewer(viewModel)
                else -> EmptyState(onOpen = ::pickPdf)
            }
        }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No PDF open", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open a document to inspect its contents.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpen, modifier = Modifier.padding(top = 16.dp)) { Text("Open a PDF") }
    }
}

@Composable
private fun PageViewer(viewModel: PdfDocumentViewModel) {
    val state = viewModel.state
    val page = state.page
    val transform = state.pageTransform

    val leafRects = remember(page, transform) {
        if (page != null && transform != null) {
            page.leaves.mapNotNull { n -> n.bounds?.let { LeafRect(n.id, transform.toRect(it)) } }
        } else {
            emptyList()
        }
    }
    val selectedRect = remember(page, transform, state.selectedId) {
        val node = page?.let { findNode(it.root, state.selectedId) }
        val bounds = node?.bounds
        if (bounds != null && transform != null) transform.toRect(bounds) else null
    }

    val density = LocalDensity.current
    var inspectorHeight by remember { mutableStateOf(300.dp) }

    Column(Modifier.fillMaxSize()) {
        val bmp = state.bitmap
        if (bmp != null) {
            PdfCanvas(
                bitmap = bmp,
                leaves = leafRects,
                selectedRect = selectedRect,
                onSelect = { id -> viewModel.select(id) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }

        if (page != null) {
            DragHandle(onDrag = { dyPx ->
                inspectorHeight = with(density) { (inspectorHeight.toPx() - dyPx).toDp() }
                    .coerceIn(140.dp, 520.dp)
            })
            InspectorPane(
                page = page,
                expanded = state.expanded,
                selectedId = state.selectedId,
                showRaw = state.showRaw,
                canDelete = false,
                onSelect = { id -> viewModel.select(id) },
                onToggleExpand = { id -> viewModel.toggleExpand(id) },
                onToggleRaw = { viewModel.toggleRaw() },
                onDelete = {},
                modifier = Modifier.height(inspectorHeight),
            )
        }
        PageBar(state, onPage = { index -> viewModel.showPage(index) })
    }
}

@Composable
private fun DragHandle(onDrag: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

@Composable
private fun PageBar(state: PdfUiState, onPage: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onPage(state.pageIndex - 1) },
            enabled = state.pageIndex > 0,
        ) { Text("Prev") }
        Text("Page ${state.pageIndex + 1}/${state.pageCount}  ·  ${state.elementCount} elements")
        TextButton(
            onClick = { onPage(state.pageIndex + 1) },
            enabled = state.pageIndex < state.pageCount - 1,
        ) { Text("Next") }
    }
}
