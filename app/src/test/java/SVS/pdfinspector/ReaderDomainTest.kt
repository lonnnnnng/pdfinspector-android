package SVS.pdfinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDomainTest {

    @Test
    fun readingHistoryMovesReopenedDocumentToFrontAndKeepsLatestProgress() {
        val existing = listOf(
            ReaderHistoryEntry("content://docs/a", "A.pdf", 1, 100L),
            ReaderHistoryEntry("content://docs/b", "B.pdf", 4, 90L),
        )

        val merged = mergeReadingHistory(
            existing,
            ReaderHistoryEntry("content://docs/a", "A.pdf", 7, 120L),
            limit = 10,
        )

        assertEquals(listOf("content://docs/a", "content://docs/b"), merged.map { it.uri })
        assertEquals(7, merged.first().pageIndex)
        assertEquals(120L, merged.first().updatedAt)
    }

    @Test
    fun readingHistoryIsBounded() {
        val existing = (0 until 20).map {
            ReaderHistoryEntry("content://docs/$it", "$it.pdf", it, 100L - it)
        }

        val merged = mergeReadingHistory(
            existing,
            ReaderHistoryEntry("content://docs/new", "新文档.pdf", 0, 200L),
            limit = 20,
        )

        assertEquals(20, merged.size)
        assertEquals("content://docs/new", merged.first().uri)
        assertFalse(merged.any { it.uri == "content://docs/19" })
    }

    @Test
    fun bookmarkToggleAddsThenRemovesPage() {
        val added = togglePageBookmark(setOf(1, 3), 2)
        assertEquals(setOf(1, 2, 3), added)

        val removed = togglePageBookmark(added, 2)
        assertEquals(setOf(1, 3), removed)
    }

    @Test
    fun fullTextSearchReturnsPageNumberAndCompactSnippet() {
        val pages = listOf(
            "第一章 简介",
            "Android PDF 阅读模式支持全文搜索和连续滚动。",
            "附录",
        )

        val results = searchPageTexts(pages, "pdf 阅读")

        assertEquals(1, results.size)
        assertEquals(1, results.single().pageIndex)
        assertTrue(results.single().snippet.contains("PDF 阅读模式", ignoreCase = true))
        assertTrue(searchPageTexts(pages, "   ").isEmpty())
    }
}
