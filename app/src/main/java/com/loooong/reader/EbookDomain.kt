package com.loooong.reader

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONObject

enum class EbookFormat {
    EPUB,
    TXT,
}

enum class EpubArchiveStatus {
    VALID,
    INVALID,
    UNSAFE,
}

data class EbookSearchResult(
    val paragraphIndex: Int,
    val snippet: String,
)

data class EbookReadingPosition(
    val sourceId: String,
    val format: EbookFormat,
    val paragraphIndex: Int,
    val locatorJson: String?,
    val updatedAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("sourceId", sourceId)
        .put("format", format.name)
        .put("paragraphIndex", paragraphIndex)
        .put("locatorJson", locatorJson)
        .put("updatedAt", updatedAt)
        .toString()
}

fun ebookReadingPositionFromJson(raw: String): EbookReadingPosition? = runCatching {
    val json = JSONObject(raw)
    EbookReadingPosition(
        sourceId = json.getString("sourceId"),
        format = EbookFormat.valueOf(json.getString("format")),
        paragraphIndex = json.optInt("paragraphIndex", 0).coerceAtLeast(0),
        locatorJson = json.optString("locatorJson").takeIf { it.isNotBlank() && it != "null" },
        updatedAt = json.optLong("updatedAt", 0L),
    )
}.getOrNull()

fun detectEbookFormat(
    fileName: String?,
    mimeType: String?,
    epubArchive: Boolean,
): EbookFormat? {
    val extension = fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
    val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    return when {
        epubArchive -> EbookFormat.EPUB
        extension == "txt" || normalizedMime == "text/plain" -> EbookFormat.TXT
        else -> null
    }
}

fun isEpubArchive(bytes: ByteArray): Boolean = runCatching {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val firstEntry = zip.nextEntry ?: return@use false
        if (firstEntry.name != "mimetype" || firstEntry.method != ZipEntry.STORED) return@use false
        zip.readBytes().toString(StandardCharsets.US_ASCII) == "application/epub+zip"
    }
}.getOrDefault(false)

fun inspectEpubArchive(
    file: File,
    maxEntries: Int = 10_000,
    maxEntryBytes: Long = 64L * 1024L * 1024L,
    maxUncompressedBytes: Long = 256L * 1024L * 1024L,
): EpubArchiveStatus {
    if (maxEntries <= 0 || maxEntryBytes <= 0L || maxUncompressedBytes <= 0L) {
        return EpubArchiveStatus.UNSAFE
    }
    val metadataStatus = runCatching {
        ZipFile(file).use { archive ->
            val entries = archive.entries()
            var entryCount = 0
            var totalBytes = 0L
            // long: Readium 打开前先用 ZIP 中心目录限制展开规模，避免小体积恶意 EPUB 耗尽真机内存或存储。
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                if (entryCount > maxEntries) return@use EpubArchiveStatus.UNSAFE
                if (!isSafeEpubEntryName(entry.name)) return@use EpubArchiveStatus.UNSAFE
                if (entry.isDirectory) continue
                val size = entry.size
                if (size < 0L) return@use EpubArchiveStatus.INVALID
                if (size > maxEntryBytes || size > maxUncompressedBytes - totalBytes) {
                    return@use EpubArchiveStatus.UNSAFE
                }
                totalBytes += size
            }
            EpubArchiveStatus.VALID
        }
    }.getOrElse { EpubArchiveStatus.INVALID }
    if (metadataStatus != EpubArchiveStatus.VALID) return metadataStatus

    return runCatching {
        ZipInputStream(FileInputStream(file)).use { zip ->
            val firstEntry = zip.nextEntry ?: return@use EpubArchiveStatus.INVALID
            if (
                firstEntry.name != "mimetype" ||
                firstEntry.method != ZipEntry.STORED ||
                firstEntry.size != EPUB_MIME_TYPE.length.toLong()
            ) {
                return@use EpubArchiveStatus.INVALID
            }
            val bytes = ByteArray(EPUB_MIME_TYPE.length + 1)
            var count = 0
            while (count < bytes.size) {
                val read = zip.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (
                count == EPUB_MIME_TYPE.length &&
                bytes.copyOf(count).toString(StandardCharsets.US_ASCII) == EPUB_MIME_TYPE
            ) {
                EpubArchiveStatus.VALID
            } else {
                EpubArchiveStatus.INVALID
            }
        }
    }.getOrElse { EpubArchiveStatus.INVALID }
}

fun normalizeHttpsEbookUrl(raw: String): String? = runCatching {
    val uri = URI(raw.trim())
    if (!uri.scheme.equals("https", ignoreCase = true)) return@runCatching null
    if (uri.host.isNullOrBlank() || uri.userInfo != null) return@runCatching null
    uri.normalize().toASCIIString()
}.getOrNull()

fun decodeTxt(bytes: ByteArray): String {
    // long: 无 BOM 的旧中文 TXT 常见 GB18030，只有严格 UTF-8 解码失败后才回退，避免误判正常 UTF-8。
    val decoded = when {
        bytes.startsWithBytes(UTF8_BOM) -> bytes.decode(StandardCharsets.UTF_8, UTF8_BOM.size)
        bytes.startsWithBytes(UTF16_LE_BOM) -> bytes.decode(StandardCharsets.UTF_16LE, UTF16_LE_BOM.size)
        bytes.startsWithBytes(UTF16_BE_BOM) -> bytes.decode(StandardCharsets.UTF_16BE, UTF16_BE_BOM.size)
        else -> decodeStrictUtf8(bytes) ?: bytes.toString(charset("GB18030"))
    }
    return decoded.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n')
}

fun splitTxtParagraphs(text: String): List<String> = text
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .toList()
    .ifEmpty { listOf("") }

fun txtTableOfContents(paragraphs: List<String>): List<Pair<Int, String>> {
    val chapterPatterns = listOf(
        Regex("^第\\s*[0-9０-９一二三四五六七八九十百千万零〇两]+\\s*[章节回部卷篇](?:$|[\\s:：、.．-]+.{1,24}$)"),
        Regex("^(?i:chapter)\\s+(?:\\d+|[IVXLCDM]+)(?:$|[\\s:：.．-]+.{1,32}$)"),
        Regex("^(?:序章|序幕|楔子|前言|尾声|结语|后记)(?:$|[\\s:：.．-]+.{1,24}$)"),
    )
    return paragraphs.mapIndexedNotNull { index, paragraph ->
        // long: 目录只接收短标题，并要求章节编号后有明确分隔，避免把“第一章第一段……”正文误判成章节。
        paragraph.takeIf { title ->
            title.length <= MAX_TXT_TOC_TITLE_CHARS && chapterPatterns.any { it.matches(title) }
        }?.let { index to it }
    }
}

fun searchEbookParagraphs(
    paragraphs: List<String>,
    rawQuery: String,
): List<EbookSearchResult> {
    val query = rawQuery.trim()
    if (query.isEmpty()) return emptyList()
    return paragraphs.mapIndexedNotNull { index, paragraph ->
        val match = paragraph.indexOf(query, ignoreCase = true)
        if (match < 0) null else EbookSearchResult(index, ebookSearchSnippet(paragraph, match, query.length))
    }
}

private const val MAX_TXT_TOC_TITLE_CHARS = 48

private fun ebookSearchSnippet(text: String, matchStart: Int, matchLength: Int): String {
    val compact = text.replace(Regex("\\s+"), " ").trim()
    val matchedText = text.substring(matchStart, (matchStart + matchLength).coerceAtMost(text.length))
    val compactMatch = compact.indexOf(matchedText, ignoreCase = true).coerceAtLeast(0)
    val start = (compactMatch - 32).coerceAtLeast(0)
    val end = (compactMatch + matchLength + 48).coerceAtMost(compact.length)
    return buildString {
        if (start > 0) append("…")
        append(compact.substring(start, end))
        if (end < compact.length) append("…")
    }
}

private fun decodeStrictUtf8(bytes: ByteArray): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

private fun ByteArray.decode(charset: java.nio.charset.Charset, offset: Int): String =
    copyOfRange(offset, size).toString(charset)

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

private fun isSafeEpubEntryName(name: String): Boolean {
    if (name.isBlank() || name.startsWith('/') || name.contains('\\')) return false
    if (name.length >= 2 && name[0].isLetter() && name[1] == ':') return false
    return name.split('/').none { it == ".." }
}

private const val EPUB_MIME_TYPE = "application/epub+zip"
private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
