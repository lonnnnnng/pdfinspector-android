package SVS.pdfinspector

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PdfDocumentWriterTest {

    @Test(expected = IllegalArgumentException::class)
    fun nullDestinationIsRejected() {
        PDDocument().use { document ->
            PdfDocumentWriter.saveCopy(document, null)
        }
    }

    @Test
    fun documentIsWrittenToDestination() {
        val output = ByteArrayOutputStream()

        PDDocument().use { document ->
            document.addPage(PDPage())
            PdfDocumentWriter.saveCopy(document, output)
        }

        val bytes = output.toByteArray()
        assertTrue(bytes.copyOfRange(0, 4).contentEquals("%PDF".toByteArray()))
    }
}
