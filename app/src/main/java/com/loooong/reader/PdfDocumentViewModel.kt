package com.loooong.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.loooong.reader.engine.ContentStreamEngine
import com.loooong.reader.engine.DrawNode
import com.loooong.reader.engine.EditCaps
import com.loooong.reader.engine.EditRequest
import com.loooong.reader.engine.EditResult
import com.loooong.reader.engine.ElementEditor
import com.loooong.reader.engine.NodeKind
import com.loooong.reader.engine.PageEditSnapshot
import com.loooong.reader.engine.ParsedPage
import com.loooong.reader.engine.StreamOwner
import com.loooong.reader.engine.collectGroupIds
import com.loooong.reader.engine.findNode
import com.loooong.reader.ui.PageTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

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
    private val readerDataMutex = Mutex()
    private var documentGeneration = 0
    private var searchJob: Job? = null

    val readerPages = mutableStateMapOf<Int, ReaderPageState>()
    val readerThumbnails = mutableStateMapOf<Int, ReaderPageState>()
    var readerState by mutableStateOf(ReaderUiState())
        private set
    var readerHistory by mutableStateOf<List<ReaderHistoryEntry>>(emptyList())
        private set
    private val readerPageLru = LinkedHashSet<Int>()
    private val readerThumbnailLru = LinkedHashSet<Int>()
    private val readerTextCache = HashMap<Int, String>()

    private var fontCatalog: FontCatalog? = null
    private var embeddedFonts = false

    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private var historyBytes = 0L

    fun open(
        context: Context,
        uri: Uri,
        mode: AppMode = AppMode.EDIT,
        startPage: Int = 0,
    ) {
        val generation = ++documentGeneration
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    val bytes = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法打开所选文件" }.readBytes()
                    }
                    val file = File(context.cacheDir, "working-$generation.pdf")
                    file.writeBytes(bytes)
                    val doc = PDDocument.load(bytes)
                    LoadedDocument(
                        document = doc,
                        file = file,
                        title = displayName(context, uri),
                        pageInfos = collectReaderPageInfos(doc),
                        outline = collectReaderOutline(doc),
                    )
                }
                if (generation != documentGeneration) {
                    loaded.document.close()
                    loaded.file.delete()
                    return@launch
                }
                val previousCacheFile = cacheFile
                readerDataMutex.withLock {
                    renderMutex.withLock {
                        closeRenderer()
                        document?.close()
                        document = loaded.document
                        cacheFile = loaded.file
                        openRenderer(loaded.file)
                    }
                }
                runCatching { previousCacheFile?.delete() }
                clearHistory()
                clearReaderCaches()
                fontCatalog = FontCatalog(context.applicationContext)
                embeddedFonts = false
                val initialPage = startPage.coerceIn(0, (loaded.document.numberOfPages - 1).coerceAtLeast(0))
                if (mode == AppMode.EDIT) renderPage(initialPage)
                val sourceUri = uri.toString()
                val preferences = ReaderPreferences(context)
                val bookmarks = preferences.loadBookmarks(sourceUri)
                readerHistory = preferences.loadHistory()
                readerState = ReaderUiState(
                    pageInfos = loaded.pageInfos,
                    outline = loaded.outline,
                    bookmarks = bookmarks,
                )
                state = state.copy(
                    loading = false,
                    hasDocument = true,
                    mode = mode,
                    pageCount = loaded.document.numberOfPages,
                    pageIndex = initialPage,
                    fileName = loaded.title,
                    sourceUri = sourceUri,
                    documentToken = state.documentToken + 1,
                    canUndo = false,
                    canRedo = false,
                )
                if (mode == AppMode.READ) {
                    preferences.record(
                        ReaderHistoryEntry(sourceUri, loaded.title, initialPage, System.currentTimeMillis()),
                    )
                    readerHistory = preferences.loadHistory()
                    ensureReaderPage(initialPage, DEFAULT_READER_WIDTH_PX)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "open failed", t)
                if (generation == documentGeneration) {
                    state = state.copy(loading = false, error = "无法打开 PDF 文件")
                }
            }
        }
    }

    fun showPage(index: Int) {
        val doc = document ?: return
        if (index < 0 || index >= doc.numberOfPages || index == state.pageIndex) return
        viewModelScope.launch {
            state = state.copy(busy = "正在加载页面")
            renderPage(index)
            state = state.copy(busy = null, pageIndex = index)
        }
    }

    fun switchMode(mode: AppMode) {
        if (!state.hasDocument || state.mode == mode) return
        if (mode == AppMode.READ) {
            state = state.copy(mode = AppMode.READ, selectedId = null, editingId = null)
            ensureReaderPage(state.pageIndex, DEFAULT_READER_WIDTH_PX)
            return
        }
        viewModelScope.launch {
            searchJob?.cancelAndJoin()
            state = state.copy(busy = "正在进入编辑模式")
            renderPage(state.pageIndex)
            state = state.copy(mode = AppMode.EDIT, busy = null)
        }
    }

    fun loadReaderLibrary(context: Context) {
        readerHistory = ReaderPreferences(context).loadHistory()
    }

    fun openHistory(context: Context, entry: ReaderHistoryEntry) {
        open(context, entry.uri.toUri(), AppMode.READ, entry.pageIndex)
    }

    fun updateReaderPosition(context: Context, pageIndex: Int) {
        if (state.mode != AppMode.READ || pageIndex !in 0 until state.pageCount) return
        if (state.pageIndex == pageIndex) return
        state = state.copy(pageIndex = pageIndex)
        val sourceUri = state.sourceUri ?: return
        val preferences = ReaderPreferences(context)
        preferences.record(
            ReaderHistoryEntry(sourceUri, state.fileName, pageIndex, System.currentTimeMillis()),
        )
        readerHistory = preferences.loadHistory()
    }

    fun toggleReaderBookmark(context: Context, pageIndex: Int) {
        val sourceUri = state.sourceUri ?: return
        val updated = togglePageBookmark(readerState.bookmarks, pageIndex)
        ReaderPreferences(context).saveBookmarks(sourceUri, updated)
        readerState = readerState.copy(bookmarks = updated)
    }

    fun ensureReaderPage(pageIndex: Int, targetWidthPx: Int) {
        ensureReaderImage(pageIndex, targetWidthPx, thumbnail = false)
    }

    fun ensureReaderThumbnail(pageIndex: Int) {
        ensureReaderImage(pageIndex, THUMBNAIL_WIDTH_PX, thumbnail = true)
    }

    private fun ensureReaderImage(pageIndex: Int, rawWidthPx: Int, thumbnail: Boolean) {
        val info = readerState.pageInfos.getOrNull(pageIndex) ?: return
        val widthPx = rawWidthPx.coerceIn(
            if (thumbnail) THUMBNAIL_WIDTH_PX else MIN_READER_WIDTH_PX,
            if (thumbnail) THUMBNAIL_WIDTH_PX else MAX_READER_WIDTH_PX,
        )
        val target = if (thumbnail) readerThumbnails else readerPages
        val current = target[pageIndex]
        if (current?.loading == true || current?.bitmap != null && current.widthPx == widthPx) {
            touchReaderCache(pageIndex, thumbnail)
            return
        }
        val generation = documentGeneration
        target[pageIndex] = ReaderPageState(info = info, widthPx = widthPx, loading = true)
        viewModelScope.launch {
            try {
                val bitmap = renderReaderBitmap(pageIndex, info, widthPx, generation)
                if (generation != documentGeneration) return@launch
                target[pageIndex] = ReaderPageState(
                    info = info,
                    widthPx = widthPx,
                    bitmap = bitmap.asImageBitmap(),
                )
                touchReaderCache(pageIndex, thumbnail)
            } catch (t: Throwable) {
                Log.e(TAG, "reader render failed page=$pageIndex", t)
                if (generation == documentGeneration) {
                    target[pageIndex] = ReaderPageState(
                        info = info,
                        widthPx = widthPx,
                        error = "页面渲染失败",
                    )
                }
            }
        }
    }

    private suspend fun renderReaderBitmap(
        pageIndex: Int,
        info: ReaderPageInfo,
        widthPx: Int,
        generation: Int,
    ): Bitmap {
        val heightPx = (widthPx * info.heightPoints / info.widthPoints)
            .roundToInt()
            .coerceAtLeast(1)
        return try {
            renderWithPdfium(pageIndex, widthPx, heightPx, generation)
        } catch (t: Throwable) {
            Log.w(TAG, "reader pdfium render failed page=$pageIndex", t)
            withContext(Dispatchers.IO) {
                readerDataMutex.withLock {
                    PDFRenderer(requireNotNull(document)).renderImage(
                        pageIndex,
                        widthPx / info.widthPoints,
                    )
                }
            }
        }
    }

    private fun touchReaderCache(pageIndex: Int, thumbnail: Boolean) {
        val lru = if (thumbnail) readerThumbnailLru else readerPageLru
        val target = if (thumbnail) readerThumbnails else readerPages
        val limit = if (thumbnail) MAX_THUMBNAIL_CACHE else MAX_READER_PAGE_CACHE
        lru.remove(pageIndex)
        lru.add(pageIndex)
        while (lru.size > limit) {
            val oldest = lru.first()
            lru.remove(oldest)
            target.remove(oldest)
        }
    }

    suspend fun readerPageText(pageIndex: Int): String {
        if (pageIndex !in 0 until state.pageCount) return ""
        val generation = documentGeneration
        return withContext(Dispatchers.IO) {
            readerDataMutex.withLock {
                readerTextCache[pageIndex]?.let { return@withLock it }
                val doc = document ?: return@withLock ""
                val text = PDFTextStripper().apply {
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                    sortByPosition = true
                }.getText(doc).trim()
                if (generation == documentGeneration) readerTextCache[pageIndex] = text
                text
            }
        }
    }

    fun searchReader(rawQuery: String) {
        val query = rawQuery.trim()
        searchJob?.cancel()
        if (query.isEmpty()) {
            readerState = readerState.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searching = false,
                searchProgress = 0,
                searchError = null,
            )
            return
        }
        val generation = documentGeneration
        readerState = readerState.copy(
            searchQuery = query,
            searchResults = emptyList(),
            searching = true,
            searchProgress = 0,
            searchError = null,
        )
        searchJob = viewModelScope.launch {
            try {
                val texts = ArrayList<String>(state.pageCount)
                for (pageIndex in 0 until state.pageCount) {
                    if (generation != documentGeneration) return@launch
                    texts += readerPageText(pageIndex)
                    readerState = readerState.copy(searchProgress = pageIndex + 1)
                }
                if (generation == documentGeneration) {
                    readerState = readerState.copy(
                        searchResults = searchPageTexts(texts, query),
                        searching = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                // long: 用户改用新关键词或关闭搜索面板时会主动取消旧任务，取消属于正常控制流，不能显示成搜索失败。
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "reader search failed", t)
                if (generation == documentGeneration) {
                    readerState = readerState.copy(searching = false, searchError = "全文搜索失败")
                }
            }
        }
    }

    fun clearReaderSearch() {
        searchJob?.cancel()
        readerState = readerState.copy(
            searchQuery = "",
            searchResults = emptyList(),
            searching = false,
            searchProgress = 0,
            searchError = null,
        )
    }

    fun select(id: Int?, reveal: Boolean = false) {
        state = state.copy(
            selectedId = id,
            revealTick = if (reveal) state.revealTick + 1 else state.revealTick,
        )
    }

    fun copySelectedText(context: Context) {
        val node = findNode(parsed?.root ?: return, state.selectedId) ?: return
        val text = node.text ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF 文本", text))
        Toast.makeText(context, "已复制文本", Toast.LENGTH_SHORT).show()
    }

    fun toggleExpand(id: Int) {
        val e = state.expanded
        state = state.copy(expanded = if (id in e) e - id else e + id)
    }

    fun toggleRaw() {
        state = state.copy(showRaw = !state.showRaw)
    }

    fun deleteSelected(context: Context) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val node = findNode(parsedPage.root, state.selectedId) ?: return
        val pageIndex = state.pageIndex
        val editsSharedForm = node.stream?.owner is StreamOwner.Form
        viewModelScope.launch {
            state = state.copy(busy = "正在删除")
            var mutationApplied = false
            try {
                val before = withContext(Dispatchers.IO) {
                    val page = doc.getPage(pageIndex)
                    val snapshot = ElementEditor.snapshot(
                        requireNotNull(node.stream) { "Element has no content-stream owner" },
                    )
                    ElementEditor.deleteNode(doc, page, node)
                    snapshot
                }
                // 只有删除成功后才登记撤销记录，避免失败操作污染历史栈。
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
                if (editsSharedForm) {
                    Toast.makeText(
                        context,
                        "已更新此表单对象的所有引用位置",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "delete failed", t)
                state = state.copy(
                    error = "删除元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun beginEdit(id: Int) {
        state = state.copy(selectedId = id, editingId = id)
    }

    fun cancelEdit() {
        state = state.copy(editingId = null)
    }

    fun editTarget(): EditTarget? {
        val parsedPage = parsed ?: return null
        val node = findNode(parsedPage.root, state.editingId ?: return null) ?: return null
        val tokens = node.stream?.tokens ?: parsedPage.tokens
        val caps = ElementEditor.capabilities(tokens, node)
        val b = node.bounds
        return EditTarget(
            node = node,
            caps = caps,
            x = b?.minX ?: 0f,
            y = b?.minY ?: 0f,
            w = b?.width ?: 0f,
            h = b?.height ?: 0f,
            fillArgb = if (caps.canFill) state.swatchColors[node.id] ?: node.colorArgb else null,
            strokeArgb = if (caps.canStroke) node.colorArgb else null,
            text = if (caps.canText) node.text else null,
            colorSpace = node.colorSpace,
            fontOptions = if (caps.canText) fontCatalog?.options() ?: emptyList() else emptyList(),
            editsSharedForm = node.stream?.owner is StreamOwner.Form,
        )
    }

    fun applyEdit(context: Context, request: EditRequest) {
        val node = findNode(parsed?.root ?: return, state.editingId) ?: return
        applyEditInternal(context, node, request)
    }

    // Inline canvas editing: retype one text run in place. Always routes through
    // the auto fallback so the new characters are guaranteed to encode, matching
    // the edit sheet's default.
    fun applyInlineText(context: Context, id: Int, newText: String) {
        val node = findNode(parsed?.root ?: return, id) ?: return
        if (node.kind != NodeKind.TEXT) return
        if (newText == (node.text ?: "")) return
        if (node.stream?.owner is StreamOwner.Form) {
            Toast.makeText(context, "此操作会更新表单对象的所有引用位置", Toast.LENGTH_SHORT).show()
        }
        applyEditInternal(
            context, node, EditRequest(newText = newText, fontEntryId = AUTO_FONT_ID),
        )
    }

    // The fallback face the inline editor renders while typing, matching what
    // applyInlineText embeds (the auto match for the run's own font).
    fun inlineFontFace(id: Int): FontCatalog.FaceSource? {
        val node = findNode(parsed?.root ?: return null, id) ?: return null
        return fontCatalog?.autoMatchFace(node.font)
    }

    // For the debug overlay: which matcher step picked the run's substitute.
    fun fontDecisionFor(id: Int): FontCatalog.MatchExplain? {
        val node = findNode(parsed?.root ?: return null, id) ?: return null
        if (node.kind != NodeKind.TEXT) return null
        return fontCatalog?.explainMatch(node.font)
    }

    // Width scale the inline editor applies so its preview size tracks what the
    // commit embeds (both use the auto match for the run's own font).
    fun inlineFontScale(id: Int): Float {
        val node = findNode(parsed?.root ?: return 1f, id) ?: return 1f
        val cat = fontCatalog ?: return 1f
        val matchId = cat.autoMatchId(node.font) ?: return 1f
        return cat.widthScale(node.font, matchId)
    }

    private fun applyEditInternal(context: Context, node: DrawNode, request: EditRequest) {
        val doc = document ?: return
        parsed ?: return
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            var mutationApplied = false
            try {
                // editElement is cheap and may reject; do it first so a failed edit
                // keeps the sheet open with the user's input. Only the slow resync +
                // render is overlaid, and the sheet closes the instant it starts.
                val execution = withContext(Dispatchers.IO) {
                    val page = doc.getPage(pageIndex)
                    val before = ElementEditor.snapshot(
                        requireNotNull(node.stream) { "Element has no content-stream owner" },
                    )
                    val sub = resolveSubstitute(doc, node, request)
                    val r = ElementEditor.editElement(
                        doc, page, node, request, sub?.font, sub?.scale ?: 1f,
                    )
                    EditExecution(r, before, sub != null)
                }
                when (val result = execution.result) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.before)
                        if (execution.embeddedFont) embeddedFonts = true
                        mutationApplied = true
                        state = state.copy(editingId = null, busy = "正在应用编辑")
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    is EditResult.TextEncodeFailed -> {
                        val chars = result.chars
                        val msg = if (chars.isNullOrBlank()) {
                            "此字体无法编码输入的文本"
                        } else {
                            "此字体缺少以下字符：$chars"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    EditResult.Degenerate ->
                        Toast.makeText(context, "无法变换此元素", Toast.LENGTH_SHORT).show()
                    EditResult.NoChange -> state = state.copy(editingId = null)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "edit failed", t)
                state = state.copy(
                    error = "应用编辑失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "应用编辑失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    private class Substitute(val font: PDFont, val scale: Float)

    private class EditExecution(
        val result: EditResult,
        val before: PageEditSnapshot,
        val embeddedFont: Boolean,
    )

    // Prefer-confident policy: on a text edit with no explicit pick, swap to a
    // precisely identified metric-compatible font even when the original could
    // encode, so missing glyphs and unreliable embedded widths stop biting.
    private fun resolveSubstitute(doc: PDDocument, node: DrawNode, request: EditRequest): Substitute? {
        val cat = fontCatalog ?: return null
        val id = request.fontEntryId
        val realId = when {
            id == AUTO_FONT_ID -> cat.autoMatchId(node.font)
            id != null -> id
            request.newText != null -> cat.confidentMatchId(node.font)
            else -> null
        } ?: return null
        val font = cat.resolve(doc, realId) ?: return null
        return Substitute(font, cat.widthScale(node.font, realId))
    }

    fun importFont(context: Context, uri: Uri) {
        val cat = fontCatalog
        if (cat == null) {
            Toast.makeText(context, "请先打开文档", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { cat.importFont(uri) }
            if (ok) {
                state = state.copy(fontCatalogTick = state.fontCatalogTick + 1)
                Toast.makeText(context, "字体已添加", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "无法导入此字体", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun undo() = stepHistory(undoStack, redoStack)

    fun redo() = stepHistory(redoStack, undoStack)

    private fun stepHistory(from: ArrayDeque<EditSnapshot>, to: ArrayDeque<EditSnapshot>) {
        val doc = document ?: return
        if (from.isEmpty()) return
        viewModelScope.launch {
            state = state.copy(busy = "正在处理")
            val entry = from.last()
            try {
                val current = withContext(Dispatchers.IO) {
                    val page = doc.getPage(entry.pageIndex)
                    val snapshot = ElementEditor.snapshot(entry.content.owner)
                    ElementEditor.restore(doc, page, entry.content)
                    snapshot
                }

                // 页面恢复成功后再移动历史记录，失败时仍可重试同一次撤销或重做。
                from.removeLast()
                historyBytes -= entry.content.content.size
                to.addLast(EditSnapshot(entry.pageIndex, current))
                historyBytes += current.content.size

                state = state.copy(
                    pageIndex = entry.pageIndex,
                    dirty = true,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                resyncCacheAndReopen()
                renderPage(entry.pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "history step failed", t)
                state = state.copy(
                    error = "更新编辑历史失败",
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    // Capped both ways so history never grows without bound.
    private fun pushUndo(pageIndex: Int, content: PageEditSnapshot) {
        undoStack.addLast(EditSnapshot(pageIndex, content))
        historyBytes += content.content.size
        for (e in redoStack) historyBytes -= e.content.content.size
        redoStack.clear()
        while (undoStack.size > MAX_HISTORY || historyBytes > MAX_HISTORY_BYTES) {
            if (undoStack.size <= 1) break
            historyBytes -= undoStack.removeFirst().content.content.size
        }
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        historyBytes = 0
    }

    fun saveCopy(context: Context, uri: Uri) {
        val doc = document ?: return
        viewModelScope.launch {
            state = state.copy(busy = "正在保存")
            try {
                withContext(Dispatchers.IO) {
                    PdfDocumentWriter.saveCopy(doc, context.contentResolver.openOutputStream(uri, "wt"))
                }
                state = state.copy(dirty = false)
                Toast.makeText(context, "副本已保存", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                Toast.makeText(context, "保存失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun closeDocument() {
        documentGeneration++
        searchJob?.cancel()
        state = state.copy(busy = "正在关闭文档")
        viewModelScope.launch {
            readerDataMutex.withLock {
                renderMutex.withLock {
                    closeRenderer()
                    document?.close()
                    document = null
                }
            }
            parsed = null
            clearHistory()
            clearReaderCaches()
            runCatching { cacheFile?.delete() }
            cacheFile = null
            fontCatalog = null
            embeddedFonts = false
            state = PdfUiState()
        }
    }

    private suspend fun renderPage(index: Int) {
        val generation = documentGeneration
        try {
            val result = withContext(Dispatchers.IO) {
                // long: 页面解析和 PDFBox 回退共享同一 PDDocument，串行读取可避免切换文档时旧任务访问已关闭对象。
                readerDataMutex.withLock {
                    check(generation == documentGeneration) { "文档已经切换" }
                    val doc = requireNotNull(document) { "文档已经关闭" }
                    val page = doc.getPage(index)
                    val crop = page.cropBox
                    val scale = RENDER_DPI / 72f
                    val rot = ((page.rotation % 360) + 360) % 360
                    val baseW = Math.round(crop.width * scale)
                    val baseH = Math.round(crop.height * scale)
                    val pxW = if (rot == 90 || rot == 270) baseH else baseW
                    val pxH = if (rot == 90 || rot == 270) baseW else baseH
                    val bmp = renderPageBitmap(index, pxW, pxH, generation)
                    val parsedPage = ContentStreamEngine.parse(page)
                    val transform = PageTransform(
                        crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height,
                        page.rotation, scale,
                    )
                    Triple(bmp, parsedPage, transform)
                }
            }
            if (generation != documentGeneration) return
            parsed = result.second
            state = state.copy(
                bitmap = result.first.asImageBitmap(),
                elementCount = result.second.leaves.size,
                page = result.second,
                pageTransform = result.third,
                swatchColors = sampleLeafColors(result.first, result.second.leaves, result.third),
                selectedId = null,
                expanded = collectGroupIds(result.second.root),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Log.e(TAG, "render failed page=$index", t)
            if (generation == documentGeneration) {
                state = state.copy(error = "渲染页面失败")
            }
        }
    }

    // pdfbox-android can't resolve some color spaces (Separation, etc.), so the
    // true paint color is read from the pixel pdfium actually drew at the
    // element's center. Text keeps its parsed color (glyph centers are usually
    // background).
    private fun sampleLeafColors(
        bmp: Bitmap,
        leaves: List<DrawNode>,
        t: PageTransform,
    ): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        val w = bmp.width
        val h = bmp.height
        for (leaf in leaves) {
            if (leaf.kind != NodeKind.PATH && leaf.kind != NodeKind.IMAGE) continue
            val b = leaf.bounds ?: continue
            val r = t.toRect(b)
            val cx = ((r.left + r.right) * 0.5f).toInt().coerceIn(0, w - 1)
            val cy = ((r.top + r.bottom) * 0.5f).toInt().coerceIn(0, h - 1)
            val px = bmp.getPixel(cx, cy)
            if (px ushr 24 != 0) map[leaf.id] = px or (0xFF shl 24)
        }
        return map
    }

    private suspend fun renderPageBitmap(
        index: Int,
        pxW: Int,
        pxH: Int,
        generation: Int,
    ): Bitmap =
        try {
            renderWithPdfium(index, pxW, pxH, generation)
        } catch (t: Throwable) {
            Log.e(TAG, "pdfium render failed page=$index, falling back to pdfbox", t)
            PDFRenderer(requireNotNull(document)).renderImageWithDPI(index, RENDER_DPI)
        }

    // pdfium leaves empty pixels transparent and PDFs assume white paper, so
    // prefill white. It is single-page and not thread-safe, hence the mutex.
    private suspend fun renderWithPdfium(
        index: Int,
        pxW: Int,
        pxH: Int,
        generation: Int,
    ): Bitmap =
        renderMutex.withLock {
            check(generation == documentGeneration) { "文档已经切换" }
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

    // Crisp re-render of just the visible window at the current zoom. The matrix
    // maps page points (top-left, y-down) onto the tile: scale carries points to
    // tile pixels, translate pulls the region's corner to the origin. Rotation 0
    // only; rotated pages keep the upscaled base bitmap.
    suspend fun renderRegion(
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        outW: Int,
        outH: Int,
    ): Bitmap? {
        val doc = document ?: return null
        val generation = documentGeneration
        if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return null
        val bw = right - left
        val bh = bottom - top
        if (bw < 1f || bh < 1f) return null
        val w = outW.coerceIn(1, MAX_TILE_PX)
        val h = outH.coerceIn(1, MAX_TILE_PX)
        return withContext(Dispatchers.IO) {
            val page = doc.getPage(pageIndex)
            if (((page.rotation % 360) + 360) % 360 != 0) return@withContext null
            val s = RENDER_DPI / 72f
            val matrix = Matrix().apply {
                setScale((w / bw) * s, (h / bh) * s)
                postTranslate(-left * (w / bw), -top * (h / bh))
            }
            renderMutex.withLock {
                if (generation != documentGeneration) return@withLock null
                val renderer = pdfRenderer ?: return@withLock null
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val pdfPage = renderer.openPage(pageIndex)
                try {
                    pdfPage.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    pdfPage.close()
                }
                bmp
            }
        }
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

    // After an in-memory edit the cache file is stale; rewrite it and reopen
    // pdfium on the fresh bytes. saveIncremental appends only the changed objects
    // to the original, so editing one page skips re-serializing the whole doc.
    // Once a font is embedded the increment can't reach the new font objects
    // (commit only flags the page chain), so switch to full saves from then on.
    private suspend fun resyncCacheAndReopen() {
        val doc = document ?: return
        val file = cacheFile ?: return
        renderMutex.withLock {
            closeRenderer()
            withContext(Dispatchers.IO) {
                try {
                    if (embeddedFonts) {
                        file.outputStream().use { doc.save(it) }
                    } else {
                        file.outputStream().use { doc.saveIncremental(it) }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "incremental save failed, full save", t)
                    file.outputStream().use { doc.save(it) }
                }
            }
            openRenderer(file)
            invalidateReaderDocumentContent()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/') ?: "文档.pdf"
    }

    private fun collectReaderPageInfos(doc: PDDocument): List<ReaderPageInfo> =
        (0 until doc.numberOfPages).map { pageIndex ->
            val page = doc.getPage(pageIndex)
            val crop = page.cropBox
            val rotation = ((page.rotation % 360) + 360) % 360
            val width = if (rotation == 90 || rotation == 270) crop.height else crop.width
            val height = if (rotation == 90 || rotation == 270) crop.width else crop.height
            ReaderPageInfo(pageIndex, width.coerceAtLeast(1f), height.coerceAtLeast(1f))
        }

    private fun collectReaderOutline(doc: PDDocument): List<ReaderOutlineEntry> {
        val root = doc.documentCatalog.documentOutline ?: return emptyList()
        val entries = ArrayList<ReaderOutlineEntry>()
        appendReaderOutline(doc, root, level = 0, entries)
        return entries
    }

    private fun appendReaderOutline(
        doc: PDDocument,
        node: PDOutlineNode,
        level: Int,
        entries: MutableList<ReaderOutlineEntry>,
    ) {
        node.children().forEach { item ->
            val pageIndex = runCatching {
                item.findDestinationPage(doc)?.let(doc.pages::indexOf)
            }.getOrNull() ?: -1
            val title = item.title?.trim().orEmpty()
            if (title.isNotEmpty() && pageIndex >= 0) {
                entries += ReaderOutlineEntry(title, pageIndex, level)
            }
            if (item.hasChildren()) appendReaderOutline(doc, item, level + 1, entries)
        }
    }

    private fun clearReaderCaches() {
        readerPages.clear()
        readerThumbnails.clear()
        readerPageLru.clear()
        readerThumbnailLru.clear()
        readerTextCache.clear()
        readerState = ReaderUiState()
    }

    private fun invalidateReaderDocumentContent() {
        readerPages.clear()
        readerThumbnails.clear()
        readerPageLru.clear()
        readerThumbnailLru.clear()
        readerTextCache.clear()
        readerState = readerState.copy(
            searchQuery = "",
            searchResults = emptyList(),
            searching = false,
            searchProgress = 0,
            searchError = null,
        )
    }

    override fun onCleared() {
        documentGeneration++
        searchJob?.cancel()
        closeRenderer()
        document?.close()
        document = null
        runCatching { cacheFile?.delete() }
        cacheFile = null
    }

    companion object {
        const val RENDER_DPI = 144f
        private const val MAX_TILE_PX = 4096
        private const val DEFAULT_READER_WIDTH_PX = 1080
        private const val MIN_READER_WIDTH_PX = 320
        private const val MAX_READER_WIDTH_PX = 2560
        private const val THUMBNAIL_WIDTH_PX = 180
        private const val MAX_READER_PAGE_CACHE = 8
        private const val MAX_THUMBNAIL_CACHE = 40
        private const val TAG = "Reader"
        private const val MAX_HISTORY = 50
        private const val MAX_HISTORY_BYTES = 16L * 1024 * 1024
    }
}

private data class LoadedDocument(
    val document: PDDocument,
    val file: File,
    val title: String,
    val pageInfos: List<ReaderPageInfo>,
    val outline: List<ReaderOutlineEntry>,
)

private class EditSnapshot(val pageIndex: Int, val content: PageEditSnapshot)

class EditTarget(
    val node: DrawNode,
    val caps: EditCaps,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val fillArgb: Int?,
    val strokeArgb: Int?,
    val text: String?,
    val colorSpace: String?,
    val fontOptions: List<FontOption> = emptyList(),
    val editsSharedForm: Boolean = false,
)

data class PdfUiState(
    val loading: Boolean = false,
    val busy: String? = null,
    val hasDocument: Boolean = false,
    val mode: AppMode? = null,
    val bitmap: ImageBitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val elementCount: Int = 0,
    val fileName: String = "",
    val sourceUri: String? = null,
    val page: ParsedPage? = null,
    val pageTransform: PageTransform? = null,
    val selectedId: Int? = null,
    val editingId: Int? = null,
    val swatchColors: Map<Int, Int> = emptyMap(),
    val revealTick: Int = 0,
    val expanded: Set<Int> = emptySet(),
    val showRaw: Boolean = false,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val documentToken: Int = 0,
    val fontCatalogTick: Int = 0,
    val error: String? = null,
)

data class ReaderPageState(
    val info: ReaderPageInfo,
    val widthPx: Int,
    val bitmap: ImageBitmap? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ReaderUiState(
    val pageInfos: List<ReaderPageInfo> = emptyList(),
    val outline: List<ReaderOutlineEntry> = emptyList(),
    val bookmarks: Set<Int> = emptySet(),
    val searchQuery: String = "",
    val searchResults: List<ReaderSearchResult> = emptyList(),
    val searching: Boolean = false,
    val searchProgress: Int = 0,
    val searchError: String? = null,
)
