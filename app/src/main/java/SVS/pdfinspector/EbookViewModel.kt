package SVS.pdfinspector

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    private var searchJob: Job? = null
    private var searchGeneration = 0L

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
        openJob?.cancel()
        openJob = viewModelScope.launch {
            screen = EbookScreen.Loading("正在打开电子书…")
            val source = runCatching {
                withContext(Dispatchers.IO) { cacheLocalSource(uri) }
            }.getOrElse {
                screen = EbookScreen.Error(it.userFacingMessage("无法读取所选文件"))
                return@launch
            }
            openCachedSource(activity, source)
        }
    }

    fun openOnline(activity: Activity, rawUrl: String) {
        val url = normalizeHttpsEbookUrl(rawUrl)
        if (url == null) {
            screen = EbookScreen.Error("请输入有效的 HTTPS 电子书地址")
            return
        }
        openJob?.cancel()
        openJob = viewModelScope.launch {
            screen = EbookScreen.Loading("正在下载电子书…")
            val source = runCatching {
                withContext(Dispatchers.IO) { downloadOnlineSource(url) }
            }.getOrElse {
                screen = EbookScreen.Error(it.userFacingMessage("在线电子书下载失败"))
                return@launch
            }
            openCachedSource(activity, source)
        }
    }

    fun openHistory(activity: Activity, entry: EbookHistoryEntry) {
        when (entry.sourceKind) {
            EbookSourceKind.LOCAL -> openUri(activity, entry.sourceId.toUri())
            EbookSourceKind.ONLINE -> openOnline(activity, entry.sourceId)
        }
    }

    private suspend fun openCachedSource(activity: Activity, source: CachedEbookSource) {
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
                                ?.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)) ?: 0,
                        )
                    }
                }.getOrElse {
                    screen = EbookScreen.Error(it.userFacingMessage("TXT 文件解析失败"))
                    return
                }
                screen = EbookScreen.Txt(document)
                recordHistory(source, document.title)
            }
            EbookFormat.EPUB -> {
                val publication = runCatching {
                    streamer.open(
                        FileAsset(source.file),
                        allowUserInteraction = true,
                        sender = activity,
                    ).getOrThrow()
                }.getOrElse {
                    screen = EbookScreen.Error(it.userFacingMessage("EPUB 文件解析失败"))
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
                recordHistory(source, title)
            }
        }
    }

    fun showLibrary() {
        openJob?.cancel()
        clearSearch()
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
        preferences.savePosition(
            EbookReadingPosition(
                sourceId = document.sourceId,
                format = EbookFormat.TXT,
                paragraphIndex = paragraphIndex.coerceAtLeast(0),
                locatorJson = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun saveEpubPosition(document: EpubEbookDocument, locator: Locator) {
        preferences.savePosition(
            EbookReadingPosition(
                sourceId = document.sourceId,
                format = EbookFormat.EPUB,
                paragraphIndex = 0,
                locatorJson = locator.toJSON().toString(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun searchTxt(document: TxtEbookDocument, query: String) {
        cancelSearchJob()
        epubSearchResults = emptyList()
        txtSearchResults = searchEbookParagraphs(document.paragraphs, query).take(MAX_SEARCH_RESULTS)
        searchError = null
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
            val iterator = document.publication.search(normalized).getOrNull()
            if (iterator == null) {
                if (generation == searchGeneration) {
                    searching = false
                    searchError = "这本 EPUB 不支持全文搜索"
                    searchJob = null
                }
                return@launch
            }
            val results = mutableListOf<Locator>()
            var failureMessage: String? = null
            try {
                while (results.size < MAX_SEARCH_RESULTS) {
                    val next = iterator.next()
                    if (next.isFailure) {
                        failureMessage = "EPUB 搜索过程中发生错误"
                        break
                    }
                    val page = next.getOrNull() ?: break
                    results += page.locators.take(MAX_SEARCH_RESULTS - results.size)
                }
            } finally {
                withContext(NonCancellable) { iterator.close() }
                // long: 旧查询即使晚于新查询结束，也不能覆盖用户当前看到的搜索结果和加载状态。
                if (generation == searchGeneration) {
                    epubSearchResults = results
                    searchError = failureMessage
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

    private fun recordHistory(source: CachedEbookSource, title: String) {
        preferences.recordHistory(
            EbookHistoryEntry(
                sourceId = source.sourceId,
                title = title,
                format = source.format,
                sourceKind = source.sourceKind,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        history = preferences.loadHistory()
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
                connection.setRequestProperty("User-Agent", "PdfInspector/${BuildConfig.VERSION_NAME}")
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

    private fun ebookCacheFile(stem: String, extension: String): File {
        val directory = File(getApplication<Application>().cacheDir, "ebooks").apply { mkdirs() }
        val safeExtension = extension.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        return File(directory, stem + safeExtension?.let { ".$it" }.orEmpty())
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

    private fun cancelSearchJob() {
        searchGeneration += 1
        searchJob?.cancel()
        searchJob = null
        searching = false
    }

    override fun onCleared() {
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
        const val MAX_TXT_BYTES = 20L * 1024L * 1024L
        const val MAX_SEARCH_RESULTS = 200
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
