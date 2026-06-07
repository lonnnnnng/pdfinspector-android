package SVS.pdfinspector.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// These stay path-only / font-free so they run on a plain JVM. The text
// re-encode success path needs the font AFMs that pdfbox-android ships as
// Android assets, so it is exercised on-device, not here.
class ElementEditorTest {

    @Test
    fun translatingPathShiftsBoundsAndWrapsTokens() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }
        val before = path.bounds!!.minX

        val result = ElementEditor.editElement(
            doc, page, parsed.tokens, path, EditRequest(dx = 10f),
        )
        assertTrue("edit should apply", result is EditResult.Applied)
        val ops = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue("q present", ops.contains("q"))
        assertTrue("cm present", ops.contains("cm"))
        assertTrue("Q present", ops.contains("Q"))

        val moved = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(before + 10f, moved.bounds!!.minX, 0.5f)
        doc.close()
    }

    @Test
    fun recoloringPathSetsNewFill() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }

        val blue = (0xFF shl 24) or 0x0000FF
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(fillArgb = blue))

        val recolored = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(blue, recolored.colorArgb)
        doc.close()
    }

    @Test
    fun colorOnlyEditWrapsWithoutCm() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }

        val green = (0xFF shl 24) or 0x00FF00
        val result = ElementEditor.editElement(
            doc, page, parsed.tokens, path, EditRequest(fillArgb = green),
        )
        assertTrue(result is EditResult.Applied)
        val ops = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue("q present", ops.contains("q"))
        assertTrue("Q present", ops.contains("Q"))
        assertTrue("no cm for color-only", !ops.contains("cm"))
        doc.close()
    }

    @Test
    fun textEditWithoutFontIsRejected() {
        val tokens = listOf<Any>(
            COSString("hi".toByteArray(Charsets.ISO_8859_1)),
            Operator.getOperator("Tj"),
        )
        val node = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "hi", font = null,
        )
        val doc = PDDocument()
        val result = ElementEditor.editElement(doc, PDPage(), tokens, node, EditRequest(newText = "x"))
        assertTrue("missing font should be rejected", result is EditResult.TextEncodeFailed)
        doc.close()
    }

    private fun pathPdf(): ByteArray {
        val content = "1 0 0 rg\n50 50 200 100 re f\n"
        val length = content.toByteArray(Charsets.ISO_8859_1).size
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << >> /Contents 4 0 R >>",
            "<< /Length $length >>\nstream\n${content}endstream",
        )
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        val offsets = IntArray(objects.size + 1)
        for ((i, obj) in objects.withIndex()) {
            offsets[i + 1] = sb.toString().toByteArray(Charsets.ISO_8859_1).size
            sb.append("${i + 1} 0 obj\n").append(obj).append("\nendobj\n")
        }
        val xrefOffset = sb.toString().toByteArray(Charsets.ISO_8859_1).size
        sb.append("xref\n0 ${objects.size + 1}\n")
        sb.append("0000000000 65535 f \n")
        for (i in 1..objects.size) sb.append(String.format("%010d 00000 n \n", offsets[i]))
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        sb.append("startxref\n$xrefOffset\n%%EOF")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
