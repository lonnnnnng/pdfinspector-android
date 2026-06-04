package SVS.pdfinspector

import android.content.Context
import android.net.Uri
import android.widget.Toast
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
import SVS.pdfinspector.engine.ElementEditor
import SVS.pdfinspector.engine.ParsedPage
import SVS.pdfinspector.engine.collectGroupIds
import SVS.pdfinspector.engine.findNode
import SVS.pdfinspector.ui.PageTransform
import SVS.pdfinspector.ui.Tool
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

    fun setTool(tool: Tool) {
        state = state.copy(tool = tool)
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

    fun deleteSelected() {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val node = findNode(parsedPage.root, state.selectedId) ?: return
        viewModelScope.launch {
            state = state.copy(loading = true)
            withContext(Dispatchers.IO) {
                ElementEditor.deleteRange(
                    doc, doc.getPage(state.pageIndex), parsedPage.tokens,
                    node.startIndex, node.endIndex,
                )
            }
            renderPage(state.pageIndex)
            state = state.copy(loading = false, dirty = true)
        }
    }

    fun saveCopy(context: Context, uri: Uri) {
        val doc = document ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { doc.save(it) }
                }
                state = state.copy(dirty = false)
                Toast.makeText(context, "Saved a copy", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                state = state.copy(error = t.message ?: "Failed to save")
            }
        }
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
    val dirty: Boolean = false,
    val tool: Tool = Tool.SELECT,
    val error: String? = null,
)
