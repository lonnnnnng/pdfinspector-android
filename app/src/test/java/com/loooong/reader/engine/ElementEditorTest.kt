package com.loooong.reader.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

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
    fun rotatingPathAroundCenterUpdatesBounds() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }

        val result = ElementEditor.editElement(
            doc,
            page,
            parsed.tokens,
            path,
            EditRequest(rotationDegrees = 90f, pivotX = 150f, pivotY = 100f),
        )

        assertTrue(result is EditResult.Applied)
        val rotated = ContentStreamEngine.parse(page).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(100f, rotated.bounds!!.minX, 0.5f)
        assertEquals(0f, rotated.bounds!!.minY, 0.5f)
        assertEquals(200f, rotated.bounds!!.maxX, 0.5f)
        assertEquals(200f, rotated.bounds!!.maxY, 0.5f)
        doc.close()
    }

    @Test
    fun insertingTextAddsFontResourceAndTextOperators() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)

        val result = ElementEditor.insertText(
            doc,
            page,
            parsed.tokens,
            TextInsertRequest("Hello", 80f, 120f, 18f, PDType1Font.HELVETICA, 0xFF202124.toInt()),
        )

        assertTrue(result is EditResult.Applied)
        val operators = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue(operators.contains("BT"))
        assertTrue(operators.contains("Tj"))
        assertTrue(page.resources.fontNames.any())
        doc.close()
    }

    @Test
    @Ignore("PDFBox Android image factory requires a real Android Bitmap runtime")
    fun insertingImageAddsXObjectAndDoOperator() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )

        val result = ElementEditor.insertImage(
            doc,
            page,
            parsed.tokens,
            ImageInsertRequest(png, 80f, 120f, 48f, 48f),
        )

        assertTrue(result is EditResult.Applied)
        val operators = (result as EditResult.Applied).tokens.filterIsInstance<Operator>().map { it.name }
        assertTrue(operators.contains("Do"))
        assertTrue(page.resources.xObjectNames.any())
        doc.close()
    }

    @Test
    fun emptyImageInsertionIsRejectedBeforeBitmapDecode() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.insertImage(
            doc,
            page,
            emptyList(),
            ImageInsertRequest(ByteArray(0), 0f, 0f, 48f, 48f),
        )

        assertTrue(result is EditResult.NoChange)
        doc.close()
    }

    @Test
    fun pastingPathDuplicatesItWithVisibleOffset() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }
        val copied = path.stream!!.tokens.subList(path.startIndex, path.endIndex + 1).toList()

        val result = ElementEditor.pasteNode(
            doc,
            page,
            parsed.tokens,
            copied,
            path.stream.resources,
            24f,
            24f,
        )

        assertTrue(result is EditResult.Applied)
        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(2, paths.size)
        assertEquals(50f, paths[0].bounds!!.minX, 0.5f)
        assertEquals(74f, paths[1].bounds!!.minX, 0.5f)
        assertEquals(74f, paths[1].bounds!!.minY, 0.5f)
        doc.close()
    }

    @Test
    fun batchDeleteWritesOnePageStream() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val tokens = listOf<Any>(
            COSFloat(0f), COSFloat(0f), COSFloat(10f), COSFloat(10f), Operator.getOperator("re"),
            Operator.getOperator("f"),
            COSFloat(20f), COSFloat(0f), COSFloat(10f), COSFloat(10f), Operator.getOperator("re"),
            Operator.getOperator("f"),
        )
        val stream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val first = DrawNode(1, NodeKind.PATH, "a", "", 0, 5, Bounds(0f, 0f, 10f, 10f), null, "", emptyList(), ctm = Affine.IDENTITY, stream = stream)
        val second = DrawNode(2, NodeKind.PATH, "b", "", 6, 11, Bounds(20f, 0f, 30f, 10f), null, "", emptyList(), ctm = Affine.IDENTITY, stream = stream)

        val result = ElementEditor.deleteNodes(doc, page, listOf(first, second))
        assertTrue(result is EditResult.Applied)
        assertTrue(ContentStreamEngine.parse(page).leaves.none { it.kind == NodeKind.PATH })
        doc.close()
    }

    @Test
    fun batchTransformMovesMultipleObjectsInOneRewrite() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val tokens = listOf<Any>(
            COSFloat(0f), COSFloat(0f), COSFloat(10f), COSFloat(10f), Operator.getOperator("re"),
            Operator.getOperator("f"),
            COSFloat(20f), COSFloat(0f), COSFloat(10f), COSFloat(10f), Operator.getOperator("re"),
            Operator.getOperator("f"),
        )
        val stream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val first = DrawNode(1, NodeKind.PATH, "a", "", 0, 5, Bounds(0f, 0f, 10f, 10f), null, "", emptyList(), ctm = Affine.IDENTITY, stream = stream)
        val second = DrawNode(2, NodeKind.PATH, "b", "", 6, 11, Bounds(20f, 0f, 30f, 10f), null, "", emptyList(), ctm = Affine.IDENTITY, stream = stream)

        val result = ElementEditor.editElements(
            doc,
            page,
            listOf(
                first to EditRequest(dx = 10f),
                second to EditRequest(dx = -10f),
            ),
        )
        assertTrue(result is EditResult.Applied)
        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(10f, paths[0].bounds!!.minX, 0.5f)
        assertEquals(10f, paths[1].bounds!!.minX, 0.5f)
        doc.close()
    }

    @Test
    fun reorderRangeMovesWholeQqBlock() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val tokens = listOf<Any>(
            Operator.getOperator("q"), COSFloat(1f), Operator.getOperator("Q"),
            Operator.getOperator("q"), COSFloat(2f), Operator.getOperator("Q"),
        )
        val stream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val result = ElementEditor.reorderRange(doc, page, stream, 0, 2, 6)
        assertTrue(result is EditResult.Applied)
        val floats = (result as EditResult.Applied).tokens.filterIsInstance<COSFloat>().map { it.floatValue() }
        assertEquals(listOf(2f, 1f), floats)
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
    fun incrementalSaveReflectsEditOnReload() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dx = 10f))

        val out = ByteArrayOutputStream()
        doc.saveIncremental(out)
        doc.close()

        val reloaded = PDDocument.load(out.toByteArray())
        val moved = ContentStreamEngine.parse(reloaded.getPage(0))
            .leaves.first { it.kind == NodeKind.PATH }
        assertEquals(60f, moved.bounds!!.minX, 0.5f)
        reloaded.close()
    }

    @Test
    fun twoIncrementalEditsBothSurvive() {
        val doc = PDDocument.load(pathPdf())
        val page = doc.getPage(0)
        var parsed = ContentStreamEngine.parse(page)
        var path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dx = 10f))
        parsed = ContentStreamEngine.parse(page)
        path = parsed.leaves.first { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, parsed.tokens, path, EditRequest(dy = 5f))

        val out = ByteArrayOutputStream()
        doc.saveIncremental(out)
        doc.close()

        val reloaded = PDDocument.load(out.toByteArray())
        val p = ContentStreamEngine.parse(reloaded.getPage(0)).leaves.first { it.kind == NodeKind.PATH }
        assertEquals(60f, p.bounds!!.minX, 0.5f)
        assertEquals(55f, p.bounds!!.minY, 0.5f)
        reloaded.close()
    }

    @Test
    fun blockColorRestatesFillBeforeEachRun() {
        // BT /F1 12 Tf 0 0 0 rg (Hi) Tj ET   (black text)
        val tokens = listOf<Any>(
            Operator.getOperator("BT"),
            COSName.getPDFName("F1"), COSFloat(12f), Operator.getOperator("Tf"),
            COSFloat(0f), COSFloat(0f), COSFloat(0f), Operator.getOperator("rg"),
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)), Operator.getOperator("Tj"),
            Operator.getOperator("ET"),
        )
        val run = DrawNode(
            id = 2, kind = NodeKind.TEXT, label = "run", detail = "",
            startIndex = 8, endIndex = 9, bounds = null, colorArgb = (0xFF shl 24),
            raw = "", children = emptyList(),
        )
        val block = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "block", detail = "",
            startIndex = 0, endIndex = 10, bounds = null, colorArgb = (0xFF shl 24),
            raw = "", children = listOf(run),
        )
        val doc = PDDocument()
        doc.addPage(PDPage())
        val red = (0xFF shl 24) or 0xFF0000
        val result = ElementEditor.editElement(
            doc, doc.getPage(0), tokens, block, EditRequest(fillArgb = red),
        )
        doc.close()

        assertTrue(result is EditResult.Applied)
        val out = (result as EditResult.Applied).tokens
        val si = out.indexOfFirst { it is COSString }            // the (Hi) string
        assertEquals("rg", (out[si - 1] as Operator).name)        // restated fill
        assertEquals(1f, (out[si - 4] as COSFloat).floatValue(), 1e-4f)  // red
        assertEquals(0f, (out[si - 3] as COSFloat).floatValue(), 1e-4f)
        assertEquals(0f, (out[si - 2] as COSFloat).floatValue(), 1e-4f)
        assertEquals("q", (out.first { it is Operator } as Operator).name)
        assertEquals("Q", (out.last { it is Operator } as Operator).name)
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

    @Test
    fun textEditSubstitutesFontAndRestoresOriginal() {
        val tokens = listOf<Any>(
            COSName.getPDFName("F1"), COSFloat(12f), Operator.getOperator("Tf"),
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)), Operator.getOperator("Tj"),
        )
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 3, endIndex = 4, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "Hi",
            fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = "Hello"), PDType1Font.HELVETICA,
        )
        assertTrue("substitution should apply", result is EditResult.Applied)
        val out = (result as EditResult.Applied).tokens
        val tfAt = out.indices.filter { (out[it] as? Operator)?.name == "Tf" }
        assertEquals("original Tf plus switch and restore", 3, tfAt.size)
        assertEquals("original font restored after run", "F1", (out[tfAt[2] - 2] as COSName).name)
        val added = out[tfAt[1] - 2] as COSName
        assertTrue("substitute font added to resources", page.resources.getFont(added) != null)
        assertTrue("run still shows text", out.any { (it as? Operator)?.name == "Tj" })
        doc.close()
    }

    @Test
    fun textEditPreservesTjArraySpacing() {
        val textArray = com.tom_roush.pdfbox.cos.COSArray().apply {
            add(COSString("A".toByteArray(Charsets.ISO_8859_1)))
            add(COSFloat(-120f))
            add(COSString("B".toByteArray(Charsets.ISO_8859_1)))
        }
        val tokens = listOf<Any>(textArray, Operator.getOperator("TJ"))
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "AB",
            font = PDType1Font.HELVETICA, fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = "CD"),
        )

        assertTrue(result is EditResult.Applied)
        val out = (result as EditResult.Applied).tokens
        val rebuilt = out[0] as com.tom_roush.pdfbox.cos.COSArray
        assertEquals("TJ", (out[1] as Operator).name)
        assertEquals("C", String((rebuilt.getObject(0) as COSString).bytes, Charsets.ISO_8859_1))
        assertEquals(-120f, (rebuilt.getObject(1) as COSFloat).floatValue(), 0f)
        assertEquals("D", String((rebuilt.getObject(2) as COSString).bytes, Charsets.ISO_8859_1))
        doc.close()
    }

    @Test
    fun emptyTextRemovesTjSpacingAdjustments() {
        val textArray = com.tom_roush.pdfbox.cos.COSArray().apply {
            add(COSString("A".toByteArray(Charsets.ISO_8859_1)))
            add(COSFloat(-120f))
            add(COSString("B".toByteArray(Charsets.ISO_8859_1)))
        }
        val tokens = listOf<Any>(textArray, Operator.getOperator("TJ"))
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "AB",
            font = PDType1Font.HELVETICA, fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = ""),
        ) as EditResult.Applied

        val rebuilt = result.tokens[0] as com.tom_roush.pdfbox.cos.COSArray
        assertEquals(1, rebuilt.size())
        assertEquals(0, (rebuilt.getObject(0) as COSString).bytes.size)
        doc.close()
    }

    @Test
    fun undoFontSubstitutionRestoresPageResources() {
        val tokens = listOf<Any>(
            COSString("Hi".toByteArray(Charsets.ISO_8859_1)),
            Operator.getOperator("Tj"),
        )
        val run = DrawNode(
            id = 1, kind = NodeKind.TEXT, label = "Text", detail = "",
            startIndex = 0, endIndex = 1, bounds = null, colorArgb = null,
            raw = "", children = emptyList(), text = "Hi",
            fontResourceName = "F1", fontSize = 12f,
        )
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val before = ElementEditor.snapshot(page)

        val result = ElementEditor.editElement(
            doc, page, tokens, run, EditRequest(newText = "Hello"), PDType1Font.HELVETICA,
        )
        assertTrue(result is EditResult.Applied)
        assertTrue(page.cosObject.containsKey(COSName.RESOURCES))

        ElementEditor.restore(doc, page, before)

        assertTrue(!page.cosObject.containsKey(COSName.RESOURCES))
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
