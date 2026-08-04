package com.loooong.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookDomainTest {

    @Test
    fun ebookFormatAcceptsEpubAndTxtFromMimeOrFileName() {
        assertEquals(
            EbookFormat.EPUB,
            detectEbookFormat("三体.epub", "application/octet-stream", epubArchive = true),
        )
        assertEquals(
            EbookFormat.EPUB,
            detectEbookFormat(null, "application/epub+zip", epubArchive = true),
        )
        assertEquals(
            EbookFormat.TXT,
            detectEbookFormat("长篇小说.TXT", "application/octet-stream", epubArchive = false),
        )
        assertNull(detectEbookFormat("封面.jpg", "image/jpeg", epubArchive = false))
    }

    @Test
    fun epubArchiveRequiresUncompressedMimetypeEntry() {
        val valid = epubBytes(mimetype = "application/epub+zip", compressed = false)
        val invalidMime = epubBytes(mimetype = "application/zip", compressed = false)
        val compressedMime = epubBytes(mimetype = "application/epub+zip", compressed = true)

        assertTrue(isEpubArchive(valid))
        assertEquals(false, isEpubArchive(invalidMime))
        assertEquals(false, isEpubArchive(compressedMime))
    }

    @Test
    fun epubArchiveInspectionRejectsOversizedAndUnsafeEntries() {
        val valid = epubFile(listOf("OEBPS/chapter.xhtml" to "正文".toByteArray()))
        val oversized = epubFile(listOf("OEBPS/chapter.xhtml" to ByteArray(128)))
        val unsafePath = epubFile(listOf("../outside.xhtml" to "正文".toByteArray()))

        assertEquals(EpubArchiveStatus.VALID, inspectEpubArchive(valid))
        assertEquals(
            EpubArchiveStatus.UNSAFE,
            inspectEpubArchive(oversized, maxEntryBytes = 64),
        )
        assertEquals(EpubArchiveStatus.UNSAFE, inspectEpubArchive(unsafePath))
    }

    @Test
    fun onlineSourceOnlyAcceptsHttpsWithoutCredentials() {
        assertEquals(
            "https://example.com/books/%E4%B8%89%E4%BD%93.epub",
            normalizeHttpsEbookUrl("  https://example.com/books/%E4%B8%89%E4%BD%93.epub  "),
        )
        assertNull(normalizeHttpsEbookUrl("http://example.com/book.epub"))
        assertNull(normalizeHttpsEbookUrl("https://user:secret@example.com/book.epub"))
        assertNull(normalizeHttpsEbookUrl("not a url"))
    }

    @Test
    fun txtDecoderSupportsBomAndFallsBackToGb18030() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "第一章\n开始".toByteArray(Charsets.UTF_8)
        val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "第二章".toByteArray(Charsets.UTF_16LE)
        val gb18030 = "中文旧编码".toByteArray(charset("GB18030"))

        assertEquals("第一章\n开始", decodeTxt(utf8))
        assertEquals("第二章", decodeTxt(utf16Le))
        assertEquals("中文旧编码", decodeTxt(gb18030))
    }

    @Test
    fun txtParagraphSearchReturnsStableLocationAndSnippet() {
        val paragraphs = splitTxtParagraphs("第一章\n\n夜色很深。\n他打开了那本电子书。\n\n尾声")

        val results = searchEbookParagraphs(paragraphs, "电子书")

        assertEquals(4, paragraphs.size)
        assertEquals(1, results.size)
        assertEquals(2, results.single().paragraphIndex)
        assertTrue(results.single().snippet.contains("电子书"))
    }

    @Test
    fun txtTableOfContentsRecognizesChineseAndEnglishChapterHeadings() {
        val headings = txtTableOfContents(
            listOf(
                "序章",
                "正文",
                "第 1 章 起程",
                "第一章第一段。这里是正文，不应进入目录。",
                "Chapter IX The Mock Turtle's Story",
                "Chapter house is a phrase in the body.",
                "尾声",
            ),
        )

        assertEquals(listOf(0, 2, 4, 6), headings.map { it.first })
    }

    @Test
    fun readingPositionRoundTripsAndRejectsBrokenJson() {
        val position = EbookReadingPosition(
            sourceId = "content://books/three-body",
            format = EbookFormat.TXT,
            paragraphIndex = 42,
            locatorJson = null,
            updatedAt = 1234L,
        )

        assertEquals(position, ebookReadingPositionFromJson(position.toJson()))
        assertNull(ebookReadingPositionFromJson("{broken"))
    }

    @Test
    fun txtParagraphPositionStaysWithinDocumentBounds() {
        assertEquals(0, clampTxtParagraphIndex(-4, 0))
        assertEquals(0, clampTxtParagraphIndex(-4, 3))
        assertEquals(2, clampTxtParagraphIndex(99, 3))
        assertEquals(1, clampTxtParagraphIndex(1, 3))
        assertEquals(0f, txtReadingProgress(-4, 0))
        assertEquals(0.5f, txtReadingProgress(1, 3))
        assertEquals(1f, txtReadingProgress(99, 3))
        assertEquals(0f, normalizedEbookProgress(-1.0))
        assertEquals(1f, normalizedEbookProgress(2.0))
        assertNull(normalizedEbookProgress(Double.NaN))
    }

    @Test
    fun ebookHistoryMovesReopenedBookToFront() {
        val existing = listOf(
            EbookHistoryEntry(
                "content://books/a",
                "A",
                EbookFormat.EPUB,
                EbookSourceKind.LOCAL,
                10L,
                progress = 0.42f,
            ),
            EbookHistoryEntry("https://example.com/b.txt", "B", EbookFormat.TXT, EbookSourceKind.ONLINE, 9L),
        )

        val merged = mergeEbookHistory(
            existing,
            EbookHistoryEntry(
                "content://books/a",
                "A 新标题",
                EbookFormat.EPUB,
                EbookSourceKind.LOCAL,
                20L,
                cacheFileName = "local-a.epub",
            ),
        )

        assertEquals(listOf("content://books/a", "https://example.com/b.txt"), merged.map { it.sourceId })
        assertEquals("A 新标题", merged.first().title)
        assertEquals("local-a.epub", merged.first().cacheFileName)
        assertEquals(0.42f, merged.first().progress)
        assertTrue(mergeEbookHistory(existing, existing.first(), limit = 0).isEmpty())
    }

    private fun epubBytes(mimetype: String, compressed: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entry = ZipEntry("mimetype")
            if (!compressed) {
                val bytes = mimetype.toByteArray(Charsets.US_ASCII)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                zip.putNextEntry(entry)
                zip.write(bytes)
            } else {
                zip.putNextEntry(entry)
                zip.write(mimetype.toByteArray(Charsets.US_ASCII))
            }
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun epubFile(entries: List<Pair<String, ByteArray>>): File {
        val file = kotlin.io.path.createTempFile("ebook-domain-", ".epub").toFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mime = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = java.util.zip.CRC32().apply { update(mimeBytes) }.value
            }
            zip.putNextEntry(mime)
            zip.write(mimeBytes)
            zip.closeEntry()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }
}
