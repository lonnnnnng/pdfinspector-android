package com.loooong.reader

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.Search
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.asset.FileAsset
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.streamer.Streamer

sealed interface EbookScreen {
    data object Library : EbookScreen
    data class Loading(val message: String) : EbookScreen
    data class Txt(val document: TxtEbookDocument) : EbookScreen
    data class Epub(val document: EpubEbookDocument) : EbookScreen
    data class Error(val message: String) : EbookScreen
}

data class TxtEbookDocument(
    val sourceId: String,
    val title: String,
    val paragraphs: List<String>,
    val initialParagraphIndex: Int,
)

@OptIn(ExperimentalReadiumApi::class)
data class EpubEbookDocument(
    val sourceId: String,
    val title: String,
    val publication: Publication,
    val navigatorFactory: EpubNavigatorFactory,
    val initialLocator: Locator?,
    val tableOfContents: List<EpubTocEntry>,
)

data class EpubTocEntry(
    val title: String,
    val locator: Locator,
    val depth: Int,
)

private data class CachedEbookSource(
    val sourceId: String,
    val sourceKind: EbookSourceKind,
    val title: String,
    val mimeType: String?,
    val file: File,
    val format: EbookFormat,
)

@OptIn(ExperimentalReadiumApi::class, Search::class)
class EbookViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = EbookPreferences(application)
    private val streamer = Streamer(application)
    private var currentPublication: Publication? = null
    private var openJob: Job? = null
    private var openGeneration = 0L
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var activePositionSourceId: String? = null
    private var latestTxtParagraphIndex = 0
    private var latestEpubLocator: Locator? = null

    var screen by mutableStateOf<EbookScreen>(EbookScreen.Library)
        private set
    var history by mutableStateOf(preferences.loadHistory())
        private set
    var settings by mutableStateOf(preferences.loadSettings())
        private set
    var txtSearchResults by mutableStateOf<List<EbookSearchResult>>(emptyList())
        private set
    var epubSearchResults by mutableStateOf<List<Locator>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    fun openUri(activity: Activity, uri: Uri) {
        val generation = beginOpenRequest()
        openJob = viewModelScope.launch {
            screen = EbookScreen.Loading("正在打开电子书…")
            val source = runCatching {
                withContext(Dispatchers.IO) { cacheLocalSource(uri) }
            }.getOrElse {
                if (generation == openGeneration) {
                    screen = EbookScreen.Error(it.userFacingMessage("无法读取所选文件"))
                }
                return@launch
            }
            openCachedSource(activity, source, generation)
        }
    }

    fun openOnline(activity: Activity, rawUrl: String) {
        val generation = beginOpenRequest()
        val url = normalizeHttpsEbookUrl(rawUrl)
        if (url == null) {
            screen = EbookScreen.Error("请输入有效的 HTTPS 电子书地址")
            return
        }
        openJob = viewModelScope.launch {
            screen = EbookScreen.Loading("正在下载电子书…")
            val source = runCatching {
                withContext(Dispatchers.IO) { downloadOnlineSource(url) }
            }.getOrElse {
                if (generation == openGeneration) {
                    screen = EbookScreen.Error(it.userFacingMessage("在线电子书下载失败"))
                }
                return@launch
            }
            openCachedSource(activity, source, generation)
        }
    }

    fun openHistory(activity: Activity, entry: EbookHistoryEntry) {
        val generation = beginOpenRequest()
        openJob = viewModelScope.launch {
            screen = EbookScreen.Loading("正在恢复上次阅读…")
            // long: 最近阅读优先使用应用内副本，原文件移动、授权失效或网络断开时仍能继续阅读。
            val source = withContext(Dispatchers.IO) { cachedHistorySource(entry) }
                ?: runCatching {
                    withContext(Dispatchers.IO) {
                        when (entry.sourceKind) {
                            EbookSourceKind.LOCAL -> cacheLocalSource(entry.sourceId.toUri())
                            EbookSourceKind.ONLINE -> downloadOnlineSource(entry.sourceId)
                        }
                    }
                }.getOrElse {
                    if (generation == openGeneration) {
                        screen = EbookScreen.Error(it.userFacingMessage("最近阅读的电子书已无法打开"))
                    }
                    return@launch
                }
            openCachedSource(activity, source, generation)
        }
    }

    fun restoreLastOpened(activity: Activity) {
        if (screen !is EbookScreen.Library) return
        history.firstOrNull()?.let { openHistory(activity, it) }
    }

    fun removeHistory(entry: EbookHistoryEntry) {
        preferences.removeHistory(entry.sourceId)
        preferences.removePosition(entry.sourceId)
        history = preferences.loadHistory()
        // long: 移除书架记录后同步回收本地副本，避免用户以为删除成功但存储仍被占用。
        pruneEbookCache(history.mapNotNull { it.cacheFileName })
    }

    private suspend fun openCachedSource(
        activity: Activity,
        source: CachedEbookSource,
        generation: Long,
    ) {
        if (generation != openGeneration) return
        closeCurrentPublication()
        clearSearch()
        when (source.format) {
            EbookFormat.TXT -> {
                val document = runCatching {
                    withContext(Dispatchers.IO) {
                        if (source.file.length() > MAX_TXT_BYTES) {
                            error("TXT 文件不能超过 ${MAX_TXT_BYTES / 1024 / 1024} MB")
                        }
                        val paragraphs = splitTxtParagraphs(decodeTxt(source.file.readBytes()))
                        val saved = preferences.loadPosition(source.sourceId)
                        TxtEbookDocument(
                            sourceId = source.sourceId,
                            title = source.title,
                            paragraphs = paragraphs,
                            initialParagraphIndex = saved?.paragraphIndex
                                ?.let { clampTxtParagraphIndex(it, paragraphs.size) } ?: 0,
                        )
                    }
                }.getOrElse {
                    if (generation == openGeneration) {
                        screen = EbookScreen.Error(it.userFacingMessage("TXT 文件解析失败"))
                    }
                    return
                }
                // long: 文件复制和解码期间用户可能又选了另一本书，旧结果不能覆盖当前打开请求。
                if (generation != openGeneration) return
                activePositionSourceId = document.sourceId
                latestTxtParagraphIndex = document.initialParagraphIndex
                latestEpubLocator = null
                screen = EbookScreen.Txt(document)
                recordHistory(
                    source,
                    document.title,
                    txtReadingProgress(document.initialParagraphIndex, document.paragraphs.size),
                )
            }
            EbookFormat.EPUB -> {
                val publication = runCatching {
                    streamer.open(
                        FileAsset(source.file),
                        allowUserInteraction = true,
                        sender = activity,
                    ).getOrThrow()
                }.getOrElse {
                    if (generation == openGeneration) {
                        screen = EbookScreen.Error(it.userFacingMessage("EPUB 文件解析失败"))
                    }
                    return
                }
                if (generation != openGeneration) {
                    publication.close()
                    return
                }
                if (publication.isRestricted || !publication.conformsTo(Publication.Profile.EPUB)) {
                    publication.close()
                    screen = EbookScreen.Error("该 EPUB 受保护或格式不受支持")
                    return
                }
                currentPublication = publication
                val initialLocator = preferences.loadPosition(source.sourceId)
                    ?.locatorJson
                    ?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                val title = publication.metadata.title.takeIf(String::isNotBlank) ?: source.title
                activePositionSourceId = source.sourceId
                latestTxtParagraphIndex = 0
                latestEpubLocator = initialLocator
                screen = EbookScreen.Epub(
                    EpubEbookDocument(
                        sourceId = source.sourceId,
                        title = title,
                        publication = publication,
                        navigatorFactory = EpubNavigatorFactory(publication),
                        initialLocator = initialLocator,
                        tableOfContents = flattenTableOfContents(publication),
                    ),
                )
                recordHistory(source, title, normalizedEbookProgress(initialLocator?.locations?.totalProgression))
            }
        }
    }

    fun showLibrary() {
        cancelOpenRequest()
        clearSearch()
        history = preferences.loadHistory()
        screen = EbookScreen.Library
    }

    fun releasePublication(publication: Publication) {
        if (currentPublication === publication) {
            closeCurrentPublication()
        }
    }

    fun updateSettings(newSettings: EbookReaderSettings) {
        settings = newSettings.copy(
            fontSizeSp = newSettings.fontSizeSp.coerceIn(14f, 32f),
            lineHeight = newSettings.lineHeight.coerceIn(1.2f, 2.2f),
            horizontalPaddingDp = newSettings.horizontalPaddingDp.coerceIn(8f, 40f),
        )
        preferences.saveSettings(settings)
    }

    fun saveTxtPosition(document: TxtEbookDocument, paragraphIndex: Int) {
        val safeIndex = clampTxtParagraphIndex(paragraphIndex, document.paragraphs.size)
        if (activePositionSourceId == document.sourceId) latestTxtParagraphIndex = safeIndex
        preferences.savePosition(
            EbookReadingPosition(
                sourceId = document.sourceId,
                format = EbookFormat.TXT,
                paragraphIndex = safeIndex,
                locatorJson = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        preferences.updateHistoryProgress(
            document.sourceId,
            txtReadingProgress(safeIndex, document.paragraphs.size),
        )
    }

    fun saveEpubPosition(document: EpubEbookDocument, locator: Locator) {
        if (activePositionSourceId == document.sourceId) latestEpubLocator = locator
        preferences.savePosition(
            EbookReadingPosition(
                sourceId = document.sourceId,
                format = EbookFormat.EPUB,
                paragraphIndex = 0,
                locatorJson = locator.toJSON().toString(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        normalizedEbookProgress(locator.locations.totalProgression)?.let { progress ->
            preferences.updateHistoryProgress(document.sourceId, progress)
        }
    }

    fun currentTxtPosition(document: TxtEbookDocument): Int =
        if (activePositionSourceId == document.sourceId) {
            latestTxtParagraphIndex
        } else {
            document.initialParagraphIndex
        }

    fun currentEpubLocator(document: EpubEbookDocument): Locator? =
        if (activePositionSourceId == document.sourceId) {
            latestEpubLocator
        } else {
            document.initialLocator
        }

    fun searchTxt(document: TxtEbookDocument, query: String) {
        val normalized = query.trim()
        searchJob?.cancel()
        val generation = ++searchGeneration
        epubSearchResults = emptyList()
        txtSearchResults = emptyList()
        searchError = null
        if (normalized.isEmpty()) {
            searching = false
            searchJob = null
            return
        }
        searchJob = viewModelScope.launch {
            searching = true
            try {
                delay(SEARCH_DEBOUNCE_MS)
                // long: 大型 TXT 的段落扫描属于 CPU 密集任务，必须离开主线程以保证输入和滚动响应。
                val results = withContext(Dispatchers.Default) {
                    searchEbookParagraphs(document.paragraphs, normalized).take(MAX_SEARCH_RESULTS)
                }
                if (generation == searchGeneration) txtSearchResults = results
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == searchGeneration) {
                    searchError = error.userFacingMessage("TXT 搜索失败")
                }
            } finally {
                if (generation == searchGeneration) {
                    searching = false
                    searchJob = null
                }
            }
        }
    }

    fun searchEpub(document: EpubEbookDocument, query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            cancelSearchJob()
            epubSearchResults = emptyList()
            searchError = null
            return
        }
        searchJob?.cancel()
        val generation = ++searchGeneration
        searchJob = viewModelScope.launch {
            searching = true
            searchError = null
            epubSearchResults = emptyList()
            try {
                delay(SEARCH_DEBOUNCE_MS)
                val (results, failureMessage) = withContext(Dispatchers.IO) {
                    val iterator = document.publication.search(normalized).getOrNull()
                        ?: return@withContext emptyList<Locator>() to "这本 EPUB 不支持全文搜索"
                    val matches = mutableListOf<Locator>()
                    var failure: String? = null
                    try {
                        while (matches.size < MAX_SEARCH_RESULTS) {
                            val next = iterator.next()
                            if (next.isFailure) {
                                failure = "EPUB 搜索过程中发生错误"
                                break
                            }
                            val page = next.getOrNull() ?: break
                            matches += page.locators.take(MAX_SEARCH_RESULTS - matches.size)
                        }
                    } finally {
                        withContext(NonCancellable) { iterator.close() }
                    }
                    matches to failure
                }
                // long: 查询切换后只允许最新一代结果更新界面，避免旧 EPUB 搜索晚到后闪回。
                if (generation == searchGeneration) {
                    epubSearchResults = results
                    searchError = failureMessage
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == searchGeneration) {
                    searchError = error.userFacingMessage("EPUB 搜索失败")
                }
            } finally {
                if (generation == searchGeneration) {
                    searching = false
                    searchJob = null
                }
            }
        }
    }

    fun clearSearch() {
        cancelSearchJob()
        searchError = null
        txtSearchResults = emptyList()
        epubSearchResults = emptyList()
    }

    private fun recordHistory(source: CachedEbookSource, title: String, progress: Float?) {
        preferences.recordHistory(
            EbookHistoryEntry(
                sourceId = source.sourceId,
                title = title,
                format = source.format,
                sourceKind = source.sourceKind,
                updatedAt = System.currentTimeMillis(),
                cacheFileName = source.file.name,
                progress = progress,
            ),
        )
        history = preferences.loadHistory()
        pruneEbookCache(history.mapNotNull { it.cacheFileName })
    }

    private fun cachedHistorySource(entry: EbookHistoryEntry): CachedEbookSource? {
        val cacheName = entry.cacheFileName ?: return null
        if (File(cacheName).name != cacheName) return null
        val file = File(ebookCacheDirectory(), cacheName)
        if (!file.isFile || file.length() > MAX_EBOOK_BYTES) return null
        val format = when (entry.format) {
            EbookFormat.TXT -> EbookFormat.TXT.takeIf { file.length() <= MAX_TXT_BYTES }
            EbookFormat.EPUB -> EbookFormat.EPUB.takeIf {
                inspectEpubArchive(file) == EpubArchiveStatus.VALID
            }
        } ?: return null
        return CachedEbookSource(
            sourceId = entry.sourceId,
            sourceKind = entry.sourceKind,
            title = entry.title,
            mimeType = null,
            file = file,
            format = format,
        )
    }

    private fun cacheLocalSource(uri: Uri): CachedEbookSource {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val title = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "电子书"
        val mimeType = resolver.getType(uri)
        val extension = title.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val target = ebookCacheFile("local-${sha256(uri.toString())}", extension)
        if (target.isFile && target.length() <= MAX_EBOOK_BYTES) {
            val cachedFormat = runCatching { detectEbookFile(target, title, mimeType) }.getOrNull()
            if (cachedFormat != null) {
                return CachedEbookSource(
                    uri.toString(),
                    EbookSourceKind.LOCAL,
                    title,
                    mimeType,
                    target,
                    cachedFormat,
                )
            }
        }
        resolver.openInputStream(uri)?.use { input ->
            copyWithLimit(input, target, MAX_EBOOK_BYTES)
        } ?: error("系统文件提供商没有返回可读内容")
        val format = detectEbookFile(target, title, mimeType)
            ?: error("仅支持 EPUB 或 TXT 文件")
        return CachedEbookSource(uri.toString(), EbookSourceKind.LOCAL, title, mimeType, target, format)
    }

    private fun downloadOnlineSource(url: String): CachedEbookSource {
        var currentUrl = url
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = URI(currentUrl).toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", "Reader/${BuildConfig.VERSION_NAME}")
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: error("重定向地址为空")
                    if (redirectCount >= MAX_REDIRECTS) error("在线地址重定向次数过多")
                    // long: 每次重定向都重新校验 HTTPS 和凭据，避免首个安全地址把下载引向明文或带账号的目标。
                    val resolved = URI(currentUrl).resolve(location).toString()
                    currentUrl = normalizeHttpsEbookUrl(resolved) ?: error("重定向目标不是安全的 HTTPS 地址")
                    return@repeat
                }
                if (code !in 200..299) error("服务器返回 HTTP $code")
                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_EBOOK_BYTES) {
                    error("电子书文件不能超过 ${MAX_EBOOK_BYTES / 1024 / 1024} MB")
                }
                val sourceTitle = onlineFileName(currentUrl)
                val extension = sourceTitle.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                val target = ebookCacheFile("online-${sha256(url)}", extension)
                if (target.isFile && target.length() <= MAX_EBOOK_BYTES) {
                    val cachedFormat = runCatching { detectEbookFile(target, sourceTitle, connection.contentType) }.getOrNull()
                    if (cachedFormat != null) {
                        return CachedEbookSource(
                            url,
                            EbookSourceKind.ONLINE,
                            sourceTitle,
                            connection.contentType,
                            target,
                            cachedFormat,
                        )
                    }
                }
                connection.inputStream.use { input -> copyWithLimit(input, target, MAX_EBOOK_BYTES) }
                val mimeType = connection.contentType
                val format = detectEbookFile(target, sourceTitle, mimeType)
                    ?: error("在线地址返回的内容不是 EPUB 或 TXT")
                return CachedEbookSource(url, EbookSourceKind.ONLINE, sourceTitle, mimeType, target, format)
            } finally {
                connection.disconnect()
            }
        }
        error("在线地址重定向失败")
    }

    private fun detectEbookFile(file: File, title: String, mimeType: String?): EbookFormat? {
        return when (inspectEpubArchive(file)) {
            EpubArchiveStatus.VALID -> EbookFormat.EPUB
            EpubArchiveStatus.UNSAFE -> error("EPUB 解压后内容过大或压缩包结构不安全")
            EpubArchiveStatus.INVALID -> detectEbookFormat(title, mimeType, epubArchive = false)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor: Cursor = getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) null else it.getString(0)
        }
    }

    private fun ebookCacheDirectory(): File =
        // long: 电子书副本承担离线恢复职责，放在 filesDir 避免系统按普通临时缓存随时清理。
        File(getApplication<Application>().filesDir, "ebooks").apply { mkdirs() }

    private fun ebookCacheFile(stem: String, extension: String): File {
        val directory = ebookCacheDirectory()
        val safeExtension = extension.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        return File(directory, stem + safeExtension?.let { ".$it" }.orEmpty())
    }

    private fun pruneEbookCache(historyNames: List<String>) {
        val directory = ebookCacheDirectory()
        val filesByName = directory.listFiles()
            ?.filter(File::isFile)
            ?.associateBy(File::getName)
            .orEmpty()
            .toMutableMap()
        val referencedNames = historyNames.toSet()
        filesByName.values.filter { it.name !in referencedNames }.forEach { file ->
            file.delete()
            filesByName.remove(file.name)
        }

        var totalBytes = filesByName.values.sumOf(File::length)
        val currentName = historyNames.firstOrNull()
        // long: 超过上限时先回收最旧书籍，当前书即使较大也必须保留，避免刚打开就失去离线副本。
        historyNames.asReversed().forEach { name ->
            if (totalBytes <= MAX_EBOOK_CACHE_BYTES || name == currentName) return@forEach
            filesByName.remove(name)?.let { file ->
                val fileBytes = file.length()
                if (file.delete()) totalBytes -= fileBytes
            }
        }
    }

    private fun copyWithLimit(input: java.io.InputStream, target: File, limit: Long) {
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            var total = 0L
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) error("电子书文件不能超过 ${limit / 1024 / 1024} MB")
                    output.write(buffer, 0, count)
                }
            }
            // long: 先写临时文件再替换缓存，下载中断时不会把半本书暴露给后续 Readium 解析。
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun onlineFileName(url: String): String {
        val encoded = URI(url).path.substringAfterLast('/').takeIf(String::isNotBlank) ?: "在线电子书"
        return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }
            .getOrDefault(encoded)
            .replace(Regex("[\\r\\n/\\\\]"), "_")
            .take(120)
    }

    private fun flattenTableOfContents(publication: Publication): List<EpubTocEntry> {
        fun append(links: List<Link>, depth: Int, target: MutableList<EpubTocEntry>) {
            links.forEach { link ->
                val locator = publication.locatorFromLink(link)
                val title = link.title?.takeIf(String::isNotBlank)
                if (locator != null && title != null) target += EpubTocEntry(title, locator, depth)
                append(link.children, depth + 1, target)
            }
        }
        return buildList { append(publication.tableOfContents, 0, this) }
    }

    private fun closeCurrentPublication() {
        currentPublication?.close()
        currentPublication = null
    }

    private fun beginOpenRequest(): Long {
        openJob?.cancel()
        openJob = null
        return ++openGeneration
    }

    private fun cancelOpenRequest() {
        openGeneration += 1
        openJob?.cancel()
        openJob = null
    }

    private fun cancelSearchJob() {
        searchGeneration += 1
        searchJob?.cancel()
        searchJob = null
        searching = false
    }

    override fun onCleared() {
        cancelOpenRequest()
        cancelSearchJob()
        closeCurrentPublication()
        super.onCleared()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun Throwable.userFacingMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank)?.let { "$fallback：$it" } ?: fallback

    private companion object {
        const val MAX_EBOOK_BYTES = 100L * 1024L * 1024L
        const val MAX_EBOOK_CACHE_BYTES = 512L * 1024L * 1024L
        const val MAX_TXT_BYTES = 20L * 1024L * 1024L
        const val MAX_SEARCH_RESULTS = 200
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
