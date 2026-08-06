package com.loooong.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.loooong.reader.engine.ContentStreamEngine
import com.loooong.reader.engine.Bounds
import com.loooong.reader.engine.AlignmentAction
import com.loooong.reader.engine.DrawNode
import com.loooong.reader.engine.EditCaps
import com.loooong.reader.engine.EditRequest
import com.loooong.reader.engine.EditResult
import com.loooong.reader.engine.ElementEditor
import com.loooong.reader.engine.ElementAlignment
import com.loooong.reader.engine.ImageInsertRequest
import com.loooong.reader.engine.NodeKind
import com.loooong.reader.engine.LayerAction
import com.loooong.reader.engine.PageEditSnapshot
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.loooong.reader.engine.ParsedPage
import com.loooong.reader.engine.StreamOwner
import com.loooong.reader.engine.TextInsertRequest
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
    private var elementClipboard: ElementClipboard? = null

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
                elementClipboard = null
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
            state = state.copy(mode = AppMode.READ, selectedId = null, selectedIds = emptySet(), editingId = null)
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

    fun removeReaderHistory(context: Context, entry: ReaderHistoryEntry) {
        val preferences = ReaderPreferences(context)
        preferences.removeHistory(entry.uri)
        readerHistory = preferences.loadHistory()
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
            selectedIds = id?.let(::setOf) ?: emptySet(),
            editingId = null,
            revealTick = if (reveal) state.revealTick + 1 else state.revealTick,
        )
    }

    /** long: 长按只在页面级叶子对象之间切换多选，文本 run 和分组继续保留原有编辑语义。 */
    fun toggleSelection(id: Int, reveal: Boolean = false) {
        val page = parsed ?: return
        if (page.leaves.none { it.id == id }) return
        val next = state.selectedIds.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        state = state.copy(
            selectedIds = next,
            selectedId = when {
                id in next -> id
                next.isEmpty() -> null
                else -> next.last()
            },
            editingId = null,
            revealTick = if (reveal) state.revealTick + 1 else state.revealTick,
        )
    }

    /** long: 将当前选中元素保存到应用内剪贴板，同时给文本保留系统剪贴板兼容性。 */
    fun copySelectedElement(context: Context) {
        if (state.selectedIds.size > 1) {
            Toast.makeText(context, "复制前请只选择一个元素", Toast.LENGTH_SHORT).show()
            return
        }
        val page = parsed ?: return
        val node = findNode(page.root, state.selectedId) ?: return
        if (node.stream?.owner !is StreamOwner.Page) {
            Toast.makeText(context, "共享表单对象暂不支持复制", Toast.LENGTH_SHORT).show()
            return
        }
        val bounds = node.bounds ?: run {
            Toast.makeText(context, "当前元素没有可复制的范围", Toast.LENGTH_SHORT).show()
            return
        }
        when (node.kind) {
            NodeKind.TEXT -> {
                val text = node.text.orEmpty()
                if (text.isBlank()) return
                elementClipboard = ElementClipboard.Text(
                    text = text,
                    fontSize = node.fontSize.coerceAtLeast(12f),
                    fillArgb = node.colorArgb ?: 0xFF202124.toInt(),
                    bounds = bounds.copyBounds(),
                    sourcePageIndex = state.pageIndex,
                )
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PDF 文本", text))
            }
            NodeKind.PATH, NodeKind.IMAGE -> {
                val stream = node.stream ?: return
                val copied = stream.tokens.subList(node.startIndex, node.endIndex + 1).toList()
                elementClipboard = ElementClipboard.Raw(
                    kind = node.kind,
                    tokens = copied,
                    sourceResources = stream.resources,
                    bounds = bounds.copyBounds(),
                    sourcePageIndex = state.pageIndex,
                )
            }
            NodeKind.GROUP -> {
                Toast.makeText(context, "请选择具体元素后再复制", Toast.LENGTH_SHORT).show()
                return
            }
        }
        state = state.copy(hasElementClipboard = true)
        Toast.makeText(context, "已复制元素，可粘贴到当前 PDF", Toast.LENGTH_SHORT).show()
    }

    /** long: 粘贴内部元素剪贴板；没有内部对象时仍允许通过已有文本入口粘贴。 */
    fun pasteElement(context: Context) {
        val clip = elementClipboard
        if (clip == null) {
            pasteText(context)
            return
        }
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在粘贴元素", error = null)
                val execution = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val before = ElementEditor.snapshot(page)
                        val crop = page.cropBox
                        val result = when (clip) {
                            is ElementClipboard.Text -> {
                                val catalog = fontCatalog ?: error("字体目录未就绪")
                                val font = catalog.resolveForText(doc, clip.text)
                                    ?: error("没有可编码此文本的字体")
                                val x = if (clip.sourcePageIndex == pageIndex) {
                                    clip.bounds.minX + 24f
                                } else {
                                    crop.lowerLeftX + (crop.width - clip.bounds.width) / 2f
                                }
                                val y = if (clip.sourcePageIndex == pageIndex) {
                                    clip.bounds.minY + 24f
                                } else {
                                    crop.lowerLeftY + crop.height / 2f
                                }
                                ElementEditor.insertText(
                                    doc,
                                    page,
                                    parsedPage.tokens,
                                    TextInsertRequest(
                                        text = clip.text,
                                        x = x,
                                        y = y,
                                        fontSize = clip.fontSize,
                                        font = font,
                                        fillArgb = clip.fillArgb,
                                    ),
                                )
                            }
                            is ElementClipboard.Raw -> {
                                val dx = if (clip.sourcePageIndex == pageIndex) 24f
                                else crop.lowerLeftX + crop.width / 2f - (clip.bounds.minX + clip.bounds.maxX) / 2f
                                val dy = if (clip.sourcePageIndex == pageIndex) 24f
                                else crop.lowerLeftY + crop.height / 2f - (clip.bounds.minY + clip.bounds.maxY) / 2f
                                ElementEditor.pasteNode(
                                    doc,
                                    page,
                                    parsedPage.tokens,
                                    clip.tokens,
                                    clip.sourceResources,
                                    dx,
                                    dy,
                                )
                            }
                        }
                        PasteExecution(result, before, clip is ElementClipboard.Text)
                    }
                }
                when (execution.result) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.before)
                        if (execution.embeddedFont) embeddedFonts = true
                        mutationApplied = true
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    else -> Toast.makeText(context, "无法粘贴此元素", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "paste failed", t)
                state = state.copy(
                    error = "粘贴元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "粘贴元素失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
            }
        }
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
        val nodes = state.selectedIds.mapNotNull { findNode(parsedPage.root, it) }
            .ifEmpty { listOfNotNull(findNode(parsedPage.root, state.selectedId)) }
        if (nodes.isEmpty()) return
        val pageIndex = state.pageIndex
        val batch = nodes.size > 1
        if (batch && nodes.any { it.stream?.owner !is StreamOwner.Page || it.kind == NodeKind.GROUP }) {
            Toast.makeText(context, "多选删除仅支持页面级文本、路径和图片", Toast.LENGTH_SHORT).show()
            return
        }
        val editsSharedForm = !batch && nodes.single().stream?.owner is StreamOwner.Form
        viewModelScope.launch {
            state = state.copy(busy = "正在删除")
            var mutationApplied = false
            try {
                val before = withContext(Dispatchers.IO) {
                    val page = doc.getPage(pageIndex)
                    val stream = requireNotNull(nodes.first().stream) { "Element has no content-stream owner" }
                    val snapshot = ElementEditor.snapshot(stream)
                    if (batch) {
                        check(ElementEditor.deleteNodes(doc, page, nodes) is EditResult.Applied) {
                            "无法批量删除所选元素"
                        }
                    } else {
                        ElementEditor.deleteNode(doc, page, nodes.single())
                    }
                    snapshot
                }
                // long: 一批元素共用同一内容流快照，所以整次删除只登记一条撤销记录。
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
        state = state.copy(selectedId = id, selectedIds = setOf(id), editingId = id)
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

    fun insertTextAtCenter(context: Context, text: String) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        if (text.isBlank()) return
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在插入文本", error = null)
                val execution = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val box = page.cropBox
                        val catalog = fontCatalog ?: error("字体目录未就绪")
                        val font = catalog.resolveForText(doc, text) ?: error("没有可编码此文本的字体")
                        val before = ElementEditor.snapshot(page)
                        val request = TextInsertRequest(
                            text = text,
                            x = box.lowerLeftX + box.width / 2f,
                            y = box.lowerLeftY + box.height / 2f,
                            fontSize = 18f,
                            font = font,
                            fillArgb = 0xFF202124.toInt(),
                        )
                        ElementEditor.insertText(doc, page, parsedPage.tokens, request) to before
                    }
                }
                when (val result = execution.first) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.second)
                        embeddedFonts = true
                        mutationApplied = true
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    is EditResult.TextEncodeFailed ->
                        Toast.makeText(context, "默认字体无法编码此文本，请先导入字体", Toast.LENGTH_LONG).show()
                    EditResult.NoChange -> Unit
                    EditResult.Degenerate ->
                        Toast.makeText(context, "无法插入文本", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "insert text failed", t)
                state = state.copy(
                    error = "插入文本失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "插入文本失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun pasteText(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(context, "剪贴板中没有文本", Toast.LENGTH_SHORT).show()
        } else {
            insertTextAtCenter(context, text)
        }
    }

    fun insertImage(context: Context, uri: Uri) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在插入图片", error = null)
                val execution = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val bytes = context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "无法读取图片" }.readBytes()
                        }
                        val page = doc.getPage(pageIndex)
                        val box = page.cropBox
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val sourceW = bounds.outWidth.takeIf { it > 0 } ?: 1
                        val sourceH = bounds.outHeight.takeIf { it > 0 } ?: 1
                        val maxW = (box.width * 0.55f).coerceAtLeast(72f)
                        val maxH = (box.height * 0.45f).coerceAtLeast(72f)
                        val factor = minOf(maxW / sourceW, maxH / sourceH)
                        val width = (sourceW * factor).coerceAtLeast(24f)
                        val height = (sourceH * factor).coerceAtLeast(24f)
                        val before = ElementEditor.snapshot(page)
                        val request = ImageInsertRequest(
                            bytes = bytes,
                            x = box.lowerLeftX + (box.width - width) / 2f,
                            y = box.lowerLeftY + (box.height - height) / 2f,
                            width = width,
                            height = height,
                        )
                        ElementEditor.insertImage(doc, page, parsedPage.tokens, request) to before
                    }
                }
                when (execution.first) {
                    is EditResult.Applied -> {
                        pushUndo(pageIndex, execution.second)
                        mutationApplied = true
                        state = state.copy(dirty = true, canUndo = true, canRedo = false)
                        resyncCacheAndReopen()
                        renderPage(pageIndex)
                    }
                    else -> Toast.makeText(context, "无法插入此图片", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "insert image failed", t)
                state = state.copy(
                    error = "插入图片失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
                Toast.makeText(context, "插入图片失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun canTransform(id: Int): Boolean {
        val parsedPage = parsed ?: return false
        val node = findNode(parsedPage.root, id) ?: return false
        val tokens = node.stream?.tokens ?: parsedPage.tokens
        return ElementEditor.capabilities(tokens, node).canGeom
    }

    fun canTransformSelection(): Boolean {
        val page = parsed ?: return false
        val nodes = state.selectedIds.mapNotNull { findNode(page.root, it) }
        if (nodes.isEmpty() || nodes.size != state.selectedIds.size) return false
        val stream = nodes.first().stream ?: return false
        if (stream.owner !is StreamOwner.Page || nodes.any { it.stream !== stream }) return false
        return nodes.all { node ->
            ElementEditor.capabilities(stream.tokens, node).canGeom
        }
    }

    /** long: 多选变换围绕联合外接框中心执行，整次手势只写流并登记一条撤销记录。 */
    fun applyCanvasTransformSelection(
        context: Context,
        dx: Float,
        dy: Float,
        scale: Float,
        rotationDegrees: Float,
    ) {
        if (!canTransformSelection()) return
        val doc = document ?: return
        val pageState = parsed ?: return
        val nodes = state.selectedIds.mapNotNull { findNode(pageState.root, it) }
        val union = Bounds.empty()
        nodes.forEach { it.bounds?.let(union::includeBounds) }
        if (!union.isValid || scale <= 0f) return
        if (!dx.isFinite() || !dy.isFinite() || !scale.isFinite() || !rotationDegrees.isFinite()) return
        if (dx == 0f && dy == 0f && scale == 1f && rotationDegrees == 0f) return
        val edits = nodes.map { node ->
            node to EditRequest(
                dx = dx,
                dy = dy,
                scaleX = scale,
                scaleY = scale,
                rotationDegrees = rotationDegrees,
                pivotX = (union.minX + union.maxX) / 2f,
                pivotY = (union.minY + union.maxY) / 2f,
            )
        }
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在变换元素", error = null)
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = ElementEditor.snapshot(page)
                        check(ElementEditor.editElements(doc, page, edits) is EditResult.Applied) {
                            "无法批量变换所选元素"
                        }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "multi transform failed", t)
                state = state.copy(
                    error = "变换元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun alignSelected(context: Context, action: AlignmentAction) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        val nodes = state.selectedIds.mapNotNull { findNode(parsedPage.root, it) }
        val minimum = if (
            action == AlignmentAction.DISTRIBUTE_HORIZONTAL ||
            action == AlignmentAction.DISTRIBUTE_VERTICAL
        ) 3 else 2
        if (nodes.size < minimum) {
            Toast.makeText(context, if (minimum == 3) "至少选择 3 个元素" else "至少选择 2 个元素", Toast.LENGTH_SHORT).show()
            return
        }
        if (nodes.any { node ->
                node.stream?.owner !is StreamOwner.Page ||
                    !ElementEditor.capabilities(node.stream?.tokens.orEmpty(), node).canGeom
            }
        ) {
            Toast.makeText(context, "所选内容包含无法对齐的元素", Toast.LENGTH_SHORT).show()
            return
        }
        val translations = ElementAlignment.compute(nodes, action)
        if (translations.isEmpty()) {
            Toast.makeText(context, "所选元素已经满足此布局", Toast.LENGTH_SHORT).show()
            return
        }
        val byId = nodes.associateBy { it.id }
        val edits = translations.mapNotNull { move ->
            byId[move.id]?.let { node -> node to EditRequest(dx = move.dx, dy = move.dy) }
        }
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在对齐元素", error = null)
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = ElementEditor.snapshot(page)
                        check(ElementEditor.editElements(doc, page, edits) is EditResult.Applied) {
                            "无法批量对齐所选元素"
                        }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "align failed", t)
                state = state.copy(
                    error = "对齐元素失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun reorderSelected(context: Context, action: LayerAction) {
        val doc = document ?: return
        val parsedPage = parsed ?: return
        if (state.selectedIds.size != 1) return
        val id = state.selectedIds.single()
        val target = findSafeLayerMove(parsedPage.root, id, action)
        if (target == null) {
            Toast.makeText(context, "此元素依赖外部绘制状态，暂不能调整图层", Toast.LENGTH_SHORT).show()
            return
        }
        val pageIndex = state.pageIndex
        viewModelScope.launch {
            var mutationApplied = false
            try {
                state = state.copy(busy = "正在调整图层", error = null)
                val before = withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val page = doc.getPage(pageIndex)
                        val snapshot = ElementEditor.snapshot(target.stream)
                        check(
                            ElementEditor.reorderRange(
                                doc,
                                page,
                                target.stream,
                                target.start,
                                target.end,
                                target.insertAt,
                            ) is EditResult.Applied,
                        ) { "图层位置没有变化" }
                        snapshot
                    }
                }
                pushUndo(pageIndex, before)
                mutationApplied = true
                state = state.copy(dirty = true, canUndo = true, canRedo = false)
                resyncCacheAndReopen()
                renderPage(pageIndex)
            } catch (t: Throwable) {
                Log.e(TAG, "reorder failed", t)
                state = state.copy(
                    error = "调整图层失败",
                    dirty = state.dirty || mutationApplied,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            } finally {
                state = state.copy(busy = null)
            }
        }
    }

    fun applyCanvasTransform(
        context: Context,
        id: Int,
        dx: Float,
        dy: Float,
        scale: Float,
        rotationDegrees: Float,
    ) {
        val node = findNode(parsed?.root ?: return, id) ?: return
        val bounds = node.bounds ?: return
        if (!dx.isFinite() || !dy.isFinite() || !scale.isFinite() || !rotationDegrees.isFinite()) return
        if (scale <= 0f) return
        if (!canTransform(id)) return
        if (dx == 0f && dy == 0f && scale == 1f && rotationDegrees == 0f) return
        if (node.stream?.owner is StreamOwner.Form) {
            Toast.makeText(context, "此操作会更新表单对象的所有引用位置", Toast.LENGTH_SHORT).show()
        }
        // long: 每次画布手势只在抬手时提交一次，确保一次移动、缩放或旋转只产生一条撤销记录。
        applyEditInternal(
            context,
            node,
            EditRequest(
                dx = dx,
                dy = dy,
                scaleX = scale,
                scaleY = scale,
                rotationDegrees = rotationDegrees,
                pivotX = (bounds.minX + bounds.maxX) / 2f,
                pivotY = (bounds.minY + bounds.maxY) / 2f,
            ),
        )
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

    fun saveCopy(context: Context, uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        val doc = document ?: run {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            var saved = false
            state = state.copy(busy = "正在保存", error = null)
            try {
                withContext(Dispatchers.IO) {
                    readerDataMutex.withLock {
                        val stagingFile = File.createTempFile("pdf-export-", ".pdf", context.cacheDir)
                        PdfDocumentWriter.saveCopy(
                            doc,
                            { context.contentResolver.openOutputStream(uri, "wt") },
                            stagingFile,
                        )
                    }
                }
                state = state.copy(dirty = false)
                saved = true
                Toast.makeText(context, "副本已保存", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                state = state.copy(error = "保存 PDF 副本失败，请重试")
                Toast.makeText(context, "保存失败", Toast.LENGTH_LONG).show()
            } finally {
                state = state.copy(busy = null)
                runCatching { onComplete(saved) }
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
            elementClipboard = null
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
                selectedIds = emptySet(),
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

private sealed class ElementClipboard {
    class Text(
        val text: String,
        val fontSize: Float,
        val fillArgb: Int,
        val bounds: Bounds,
        val sourcePageIndex: Int,
    ) : ElementClipboard()

    class Raw(
        val kind: NodeKind,
        val tokens: List<Any>,
        val sourceResources: PDResources?,
        val bounds: Bounds,
        val sourcePageIndex: Int,
    ) : ElementClipboard()
}

private class PasteExecution(
    val result: EditResult,
    val before: PageEditSnapshot,
    val embeddedFont: Boolean,
)

private fun Bounds.copyBounds() = Bounds(minX, minY, maxX, maxY)

private class LayerMove(
    val stream: com.loooong.reader.engine.ParsedStream,
    val start: Int,
    val end: Int,
    val insertAt: Int,
)

/** long: 仅把单子节点 q/Q 分组视为自包含图层，避免重排后继承到错误的颜色或 CTM。 */
private fun findSafeLayerMove(root: DrawNode, id: Int, action: LayerAction): LayerMove? {
    val path = ArrayList<DrawNode>()
    fun walk(node: DrawNode): Boolean {
        path.add(node)
        if (node.id == id) return true
        for (child in node.children) if (walk(child)) return true
        path.removeAt(path.lastIndex)
        return false
    }
    if (!walk(root)) return null
    val selectedIndex = path.lastIndex
    val selected = path[selectedIndex]
    val blockIndex = when {
        selected.kind == NodeKind.GROUP && isSafeLayerBlock(selected) -> selectedIndex
        selectedIndex > 0 -> {
            val parent = path[selectedIndex - 1]
            if (parent.children.size == 1 && isSafeLayerBlock(parent)) selectedIndex - 1 else return null
        }
        else -> return null
    }
    if (blockIndex <= 0) return null
    val block = path[blockIndex]
    val container = path[blockIndex - 1]
    val siblings = container.children
    val index = siblings.indexOfFirst { it.id == block.id }
    if (index < 0) return null
    val insertAt = when (action) {
        LayerAction.FORWARD -> siblings.getOrNull(index + 1)?.endIndex?.plus(1)
        LayerAction.BACKWARD -> siblings.getOrNull(index - 1)?.startIndex
        LayerAction.TO_FRONT -> siblings.lastOrNull()?.takeIf { it.id != block.id }?.endIndex?.plus(1)
        LayerAction.TO_BACK -> siblings.firstOrNull()?.takeIf { it.id != block.id }?.startIndex
    } ?: return null
    return LayerMove(requireNotNull(block.stream), block.startIndex, block.endIndex, insertAt)
}

private fun isSafeLayerBlock(node: DrawNode): Boolean {
    val stream = node.stream ?: return false
    if (node.kind != NodeKind.GROUP || stream.owner !is StreamOwner.Page) return false
    val open = stream.tokens.getOrNull(node.startIndex) as? Operator
    val close = stream.tokens.getOrNull(node.endIndex) as? Operator
    return open?.name == "q" && close?.name == "Q"
}

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
    val selectedIds: Set<Int> = emptySet(),
    val editingId: Int? = null,
    val swatchColors: Map<Int, Int> = emptyMap(),
    val revealTick: Int = 0,
    val expanded: Set<Int> = emptySet(),
    val showRaw: Boolean = false,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val hasElementClipboard: Boolean = false,
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
