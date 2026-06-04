package SVS.pdfinspector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfDocumentViewModel : ViewModel() {

    var state by mutableStateOf(PdfUiState())
        private set

    private var document: PDDocument? = null
    var parsed: ParsedPage? = null
        private set

    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var cacheFile: File? = null
    private val renderMutex = Mutex()

    fun open(context: Context, uri: Uri) {
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Cannot open the selected file" }.readBytes()
                    }
                    val file = File(context.cacheDir, "working.pdf")
                    file.writeBytes(bytes)
                    PDDocument.load(bytes) to file
                }
                document?.close()
                closeRenderer()
                document = loaded.first
                cacheFile = loaded.second
                openRenderer(loaded.second)
                renderPage(0)
                state = state.copy(
                    loading = false,
                    hasDocument = true,
                    pageCount = loaded.first.numberOfPages,
                    pageIndex = 0,
                    fileName = displayName(context, uri),
                    documentToken = state.documentToken + 1,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
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
            resyncCacheAndReopen()
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
        try {
            val result = withContext(Dispatchers.IO) {
                val page = doc.getPage(index)
                val crop = page.cropBox
                val scale = RENDER_DPI / 72f
                val rot = ((page.rotation % 360) + 360) % 360
                val baseW = Math.round(crop.width * scale)
                val baseH = Math.round(crop.height * scale)
                val pxW = if (rot == 90 || rot == 270) baseH else baseW
                val pxH = if (rot == 90 || rot == 270) baseW else baseH
                val bmp = renderPageBitmap(index, pxW, pxH)
                val parsedPage = ContentStreamEngine.parse(page)
                val transform = PageTransform(
                    crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height,
                    page.rotation, scale,
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
        } catch (t: Throwable) {
            Log.e(TAG, "render failed page=$index", t)
            state = state.copy(error = t.message ?: "Failed to render page")
        }
    }

    private suspend fun renderPageBitmap(index: Int, pxW: Int, pxH: Int): Bitmap =
        try {
            renderWithPdfium(index, pxW, pxH)
        } catch (t: Throwable) {
            Log.e(TAG, "pdfium render failed page=$index, falling back to pdfbox", t)
            PDFRenderer(requireNotNull(document)).renderImageWithDPI(index, RENDER_DPI)
        }

    // pdfium leaves empty pixels transparent and PDFs assume white paper, so
    // prefill white. It is single-page and not thread-safe, hence the mutex.
    private suspend fun renderWithPdfium(index: Int, pxW: Int, pxH: Int): Bitmap =
        renderMutex.withLock {
            val renderer = requireNotNull(pdfRenderer) { "renderer not open" }
            val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val page = renderer.openPage(index)
            try {
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            bmp
        }

    private fun openRenderer(file: File) {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd
        pdfRenderer = PdfRenderer(fd)
    }

    private fun closeRenderer() {
        runCatching { pdfRenderer?.close() }
        runCatching { pfd?.close() }
        pdfRenderer = null
        pfd = null
    }

    // After an in-memory edit the cache file is stale; rewrite it from the doc
    // and reopen pdfium on the fresh bytes.
    private suspend fun resyncCacheAndReopen() {
        val doc = document ?: return
        val file = cacheFile ?: return
        renderMutex.withLock {
            closeRenderer()
            withContext(Dispatchers.IO) { file.outputStream().use { doc.save(it) } }
            openRenderer(file)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    override fun onCleared() {
        closeRenderer()
        document?.close()
        document = null
        runCatching { cacheFile?.delete() }
        cacheFile = null
    }

    companion object {
        const val RENDER_DPI = 144f
        private const val TAG = "PdfInspector"
    }
}

data class PdfUiState(
    val loading: Boolean = false,
    val hasDocument: Boolean = false,
    val bitmap: ImageBitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val elementCount: Int = 0,
    val fileName: String = "",
    val page: ParsedPage? = null,
    val pageTransform: PageTransform? = null,
    val selectedId: Int? = null,
    val expanded: Set<Int> = emptySet(),
    val showRaw: Boolean = false,
    val dirty: Boolean = false,
    val documentToken: Int = 0,
    val error: String? = null,
)
