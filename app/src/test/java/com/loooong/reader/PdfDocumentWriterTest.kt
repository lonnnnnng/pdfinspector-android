package com.loooong.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class PdfDocumentWriterTest {

    @Test(expected = IllegalArgumentException::class)
    fun nullDestinationIsRejected() {
        val staging = File.createTempFile("pdf-writer-test-", ".pdf")
        PDDocument().use { document ->
            PdfDocumentWriter.saveCopy(document, { null }, staging)
        }
    }

    @Test
    fun documentIsWrittenToDestination() {
        val output = ByteArrayOutputStream()
        val staging = File.createTempFile("pdf-writer-test-", ".pdf")

        PDDocument().use { document ->
            document.addPage(PDPage())
            PdfDocumentWriter.saveCopy(document, { output }, staging)
        }

        val bytes = output.toByteArray()
        assertTrue(bytes.copyOfRange(0, 4).contentEquals("%PDF".toByteArray()))
        PDDocument.load(bytes).use { saved -> assertEquals(1, saved.numberOfPages) }
        assertTrue(!staging.exists())
    }
}
