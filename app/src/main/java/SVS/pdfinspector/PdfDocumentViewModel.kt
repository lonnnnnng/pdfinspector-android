package SVS.pdfinspector

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import SVS.pdfinspector.engine.ContentStreamEngine
import SVS.pdfinspector.engine.ParsedPage
import SVS.pdfinspector.engine.collectGroupIds
import SVS.pdfinspector.ui.PageTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfDocumentViewModel : ViewModel() {

    var state by mutableStateOf(PdfUiState())
        private set

    private var document: PDDocument? = null
    var parsed: ParsedPage? = null
        private set

    fun open(context: Context, uri: Uri) {
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Cannot open the selected file" }
                        PDDocument.load(input)
                    }
                }
                document?.close()
                document = doc
                renderPage(0)
                state = state.copy(
                    loading = false,
                    hasDocument = true,
                    pageCount = doc.numberOfPages,
                    pageIndex = 0,
                )
            } catch (t: Throwable) {
                state = state.copy(loading = false, error = t.message ?: "Failed to open PDF")
            }
        }
    }

    fun showPage(index: Int) {
        val doc = document ?: return
        if (index < 0 || index >= doc.numberOfPages || index == state.pageIndex) return
        viewModelScope.launch {
            state = state.copy(loading = true)
            renderPage(index)
            state = state.copy(loading = false, pageIndex = index)
        }
    }

    fun select(id: Int?) {
        state = state.copy(selectedId = id)
    }

    fun toggleExpand(id: Int) {
        val e = state.expanded
        state = state.copy(expanded = if (id in e) e - id else e + id)
    }

    fun toggleRaw() {
        state = state.copy(showRaw = !state.showRaw)
    }

    private suspend fun renderPage(index: Int) {
        val doc = document ?: return
        val result = withContext(Dispatchers.IO) {
            val page = doc.getPage(index)
            val bmp = PDFRenderer(doc).renderImageWithDPI(index, RENDER_DPI)
            val parsedPage = ContentStreamEngine.parse(page)
            val crop = page.cropBox
            val transform = PageTransform(
                crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height,
                page.rotation, RENDER_DPI / 72f,
            )
            Triple(bmp, parsedPage, transform)
        }
        parsed = result.second
        state = state.copy(
            bitmap = result.first.asImageBitmap(),
            elementCount = result.second.leaves.size,
            page = result.second,
            pageTransform = result.third,
            selectedId = null,
            expanded = collectGroupIds(result.second.root),
        )
    }

    override fun onCleared() {
        document?.close()
        document = null
    }

    companion object {
        const val RENDER_DPI = 144f
    }
}

data class PdfUiState(
    val loading: Boolean = false,
    val hasDocument: Boolean = false,
    val bitmap: ImageBitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val elementCount: Int = 0,
    val page: ParsedPage? = null,
    val pageTransform: PageTransform? = null,
    val selectedId: Int? = null,
    val expanded: Set<Int> = emptySet(),
    val showRaw: Boolean = false,
    val error: String? = null,
)
