package SVS.pdfinspector.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EbookProgressMappingTest {
    @Test
    fun progressMapsToFirstMiddleAndLastParagraph() {
        assertEquals(0, readerIndexForProgress(0f, 101))
        assertEquals(50, readerIndexForProgress(0.5f, 101))
        assertEquals(100, readerIndexForProgress(1f, 101))
    }

    @Test
    fun progressAndIndexAreClampedToDocumentBounds() {
        assertEquals(0, readerIndexForProgress(-1f, 10))
        assertEquals(9, readerIndexForProgress(2f, 10))
        assertEquals(0f, readerProgressForIndex(-1, 10))
        assertEquals(1f, readerProgressForIndex(99, 10))
    }

    @Test
    fun emptyAndSingleParagraphDocumentsStayAtStart() {
        assertEquals(0, readerIndexForProgress(1f, 0))
        assertEquals(0, readerIndexForProgress(1f, 1))
        assertEquals(0f, readerProgressForIndex(1, 0))
        assertEquals(0f, readerProgressForIndex(1, 1))
    }

    @Test
    fun visibleParagraphsReduceTheScrollablePositionRange() {
        assertEquals(7, readerScrollablePositionCount(itemCount = 14, visibleItemCount = 8))
        assertEquals(14, readerScrollablePositionCount(itemCount = 14, visibleItemCount = 0))
        assertEquals(1, readerScrollablePositionCount(itemCount = 14, visibleItemCount = 14))
    }

    @Test
    fun currentTxtChapterUsesTheLatestHeadingBeforeTheVisibleParagraph() {
        val entries = listOf(0 to "序章", 5 to "第一章", 12 to "第二章")

        assertEquals(null, currentTxtChapterTitle(entries, -1))
        assertEquals("序章", currentTxtChapterTitle(entries, 0))
        assertEquals("第一章", currentTxtChapterTitle(entries, 9))
        assertEquals("第二章", currentTxtChapterTitle(entries, 99))
    }
}
