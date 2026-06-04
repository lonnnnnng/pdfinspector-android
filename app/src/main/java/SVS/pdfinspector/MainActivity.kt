package SVS.pdfinspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
                state.hasDocument -> PageViewer(state, onPage = viewModel::showPage)
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
private fun PageViewer(state: PdfUiState, onPage: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        val bmp = state.bitmap
        if (bmp != null) {
            PdfCanvas(
                bitmap = bmp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        PageBar(state, onPage)
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
        Text("Page ${state.pageIndex + 1} / ${state.pageCount}")
        TextButton(
            onClick = { onPage(state.pageIndex + 1) },
            enabled = state.pageIndex < state.pageCount - 1,
        ) { Text("Next") }
    }
}
