package com.loooong.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfLeaveDecisionTest {

    @Test
    fun cleanDocumentProceedsWithoutConfirmation() {
        val result = decidePdfLeave(false, PdfLeaveTarget.CLOSE_DOCUMENT)

        assertEquals(PdfLeaveDecision.Proceed(PdfLeaveTarget.CLOSE_DOCUMENT), result)
    }

    @Test
    fun dirtyDocumentConfirmsTheOriginalTarget() {
        val result = decidePdfLeave(true, PdfLeaveTarget.OPEN_READ_DOCUMENT)

        assertEquals(PdfLeaveDecision.Confirm(PdfLeaveTarget.OPEN_READ_DOCUMENT), result)
    }

    @Test
    fun cancelledOrFailedSaveKeepsConfirmation() {
        val result = resolvePdfLeaveSave(PdfLeaveTarget.OPEN_EDIT_DOCUMENT, saved = false)

        assertEquals(PdfLeaveDecision.Confirm(PdfLeaveTarget.OPEN_EDIT_DOCUMENT), result)
    }

    @Test
    fun successfulSaveResumesTheOriginalTarget() {
        val result = resolvePdfLeaveSave(PdfLeaveTarget.CLOSE_DOCUMENT, saved = true)

        assertEquals(PdfLeaveDecision.Proceed(PdfLeaveTarget.CLOSE_DOCUMENT), result)
    }
}
