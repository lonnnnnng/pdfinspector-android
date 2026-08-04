package com.loooong.reader.engine

import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ContentStreamEngineTest {

    @Test
    fun parsesTextImageAndPath() {
        val doc = PDDocument.load(samplePdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)

        val kinds = parsed.leaves.map { it.kind }
        assertTrue("expected a text node", kinds.contains(NodeKind.TEXT))
        assertTrue("expected a path node", kinds.contains(NodeKind.PATH))

        val text = parsed.leaves.first { it.kind == NodeKind.TEXT }
        assertTrue("text label should carry preview", text.label.contains("Hello World"))
        assertTrue("token range should be ordered", text.startIndex <= text.endIndex)
        assertTrue("text should have bounds", text.bounds != null)

        doc.close()
    }

    @Test
    fun deletingTextRewritesTheStream() {
        val doc = PDDocument.load(samplePdf())
        val page = doc.getPage(0)
        val parsed = ContentStreamEngine.parse(page)
        val text = parsed.leaves.first { it.kind == NodeKind.TEXT }

        val kept = ElementEditor.deleteRange(
            doc, page, parsed.tokens, text.startIndex, text.endIndex,
        )
        assertTrue("tokens should shrink", kept.size < parsed.tokens.size)

        val reparsed = ContentStreamEngine.parse(page)
        val stillThere = reparsed.leaves.any {
            it.kind == NodeKind.TEXT && it.label.contains("Hello World")
        }
        assertTrue("deleted text should be gone after re-parse", !stillThere)
        assertTrue("the path should survive", reparsed.leaves.any { it.kind == NodeKind.PATH })

        doc.close()
    }

    @Test
    fun textRunCarriesFontResourceAndSize() {
        val doc = PDDocument.load(samplePdf())
        val parsed = ContentStreamEngine.parse(doc.getPage(0))
        val textObj = parsed.leaves.first { it.kind == NodeKind.TEXT }
        val run = textObj.children.first { it.kind == NodeKind.TEXT }
        assertEquals("F1", run.fontResourceName)
        assertEquals(24f, run.fontSize, 1e-4f)
        doc.close()
    }

    @Test
    fun parsesFormChildrenWithFormMatrixAndCallerCtm() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 20 re f\n").apply {
            setMatrix(AffineTransform(1f, 0f, 0f, 1f, 3f, 4f))
        }
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(doc, page, "q 2 0 0 3 100 200 cm /Fm Do Q\n")

        val parsed = ContentStreamEngine.parse(page)
        val formNode = parsed.root.children.single().children.single()
        val path = formNode.children.single { it.kind == NodeKind.PATH }

        assertEquals(NodeKind.GROUP, formNode.kind)
        assertTrue(formNode.stream?.owner is StreamOwner.Page)
        assertTrue(path.stream?.owner is StreamOwner.Form)
        assertEquals(106f, path.bounds!!.minX, 1e-4f)
        assertEquals(212f, path.bounds!!.minY, 1e-4f)
        assertEquals(126f, path.bounds!!.maxX, 1e-4f)
        assertEquals(272f, path.bounds!!.maxY, 1e-4f)
        doc.close()
    }

    @Test
    fun expandsTheSameFormAtEachCallSite() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 10 re f\n")
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(
            doc,
            page,
            "q 1 0 0 1 0 0 cm /Fm Do Q q 1 0 0 1 50 0 cm /Fm Do Q\n",
        )

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(2, paths.size)
        assertEquals(0f, paths[0].bounds!!.minX, 1e-4f)
        assertEquals(50f, paths[1].bounds!!.minX, 1e-4f)
        assertTrue(paths[0].id != paths[1].id)
        val firstOwner = paths[0].stream!!.owner as StreamOwner.Form
        val secondOwner = paths[1].stream!!.owner as StreamOwner.Form
        assertTrue(firstOwner.form.cosObject === secondOwner.form.cosObject)
        doc.close()
    }

    @Test
    fun stopsRecursiveFormCycleAndKeepsReachablePaths() {
        val doc = PDDocument()
        val page = addPage(doc)
        val formA = addForm(doc, "0 0 10 10 re f /B Do\n")
        val formB = addForm(doc, "20 0 10 10 re f /A Do\n")
        formA.resources = PDResources().apply { put(COSName.getPDFName("B"), formB) }
        formB.resources = PDResources().apply { put(COSName.getPDFName("A"), formA) }
        page.resources.put(COSName.getPDFName("A"), formA)
        setPageContent(doc, page, "/A Do\n")

        val parsed = ContentStreamEngine.parse(page)
        assertEquals(2, parsed.leaves.count { it.kind == NodeKind.PATH })
        val forms = flatten(parsed.root).filter { it.label == "表单对象" }
        assertEquals(3, forms.size)
        assertTrue(forms.any { it.detail.contains("递归引用") && it.children.isEmpty() })
        doc.close()
    }

    @Test
    fun formWithoutResourcesInheritsCallerXObjects() {
        val doc = PDDocument()
        val page = addPage(doc)
        val inner = addForm(doc, "0 0 10 10 re f\n")
        val outer = addForm(doc, "/Inner Do\n").apply { resources = null }
        page.resources.put(COSName.getPDFName("Outer"), outer)
        page.resources.put(COSName.getPDFName("Inner"), inner)
        setPageContent(doc, page, "/Outer Do\n")

        val paths = ContentStreamEngine.parse(page).leaves.filter { it.kind == NodeKind.PATH }
        assertEquals(1, paths.size)
        assertTrue(paths.single().stream?.owner is StreamOwner.Form)
        doc.close()
    }

    @Test
    fun editsAndRestoresAPathInsideFormStream() {
        val doc = PDDocument()
        val page = addPage(doc)
        val form = addForm(doc, "0 0 10 10 re f\n")
        page.resources.put(COSName.getPDFName("Fm"), form)
        setPageContent(doc, page, "/Fm Do\n")
        var path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        val before = ElementEditor.snapshot(path.stream!!)

        val result = ElementEditor.editElement(doc, page, path, EditRequest(dx = 5f))
        assertTrue(result is EditResult.Applied)
        path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        assertEquals(5f, path.bounds!!.minX, 1e-4f)

        ElementEditor.restore(doc, page, before)
        path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        assertEquals(0f, path.bounds!!.minX, 1e-4f)
        doc.close()
    }

    @Test
    fun incrementalSavePersistsFormStreamEdit() {
        val seed = PDDocument()
        val seedPage = addPage(seed)
        val innerForm = addForm(seed, "0 0 10 10 re f\n")
        val outerForm = addForm(seed, "/Inner Do\n")
        outerForm.resources.put(COSName.getPDFName("Inner"), innerForm)
        seedPage.resources.put(COSName.getPDFName("Outer"), outerForm)
        setPageContent(seed, seedPage, "/Outer Do\n")
        val original = ByteArrayOutputStream().also(seed::save).toByteArray()
        seed.close()

        val doc = PDDocument.load(original)
        val page = doc.getPage(0)
        val path = ContentStreamEngine.parse(page).leaves.single { it.kind == NodeKind.PATH }
        ElementEditor.editElement(doc, page, path, EditRequest(dx = 7f))
        val saved = ByteArrayOutputStream()
        doc.saveIncremental(saved)
        doc.close()

        val reloaded = PDDocument.load(saved.toByteArray())
        val moved = ContentStreamEngine.parse(reloaded.getPage(0))
            .leaves.single { it.kind == NodeKind.PATH }
        assertEquals(7f, moved.bounds!!.minX, 1e-4f)
        reloaded.close()
    }

    private fun addPage(doc: PDDocument): PDPage = PDPage(PDRectangle(300f, 300f)).also {
        it.resources = PDResources()
        doc.addPage(it)
    }

    private fun addForm(doc: PDDocument, content: String): PDFormXObject = PDFormXObject(doc).also {
        it.bBox = PDRectangle(0f, 0f, 100f, 100f)
        it.resources = PDResources()
        writeContent(it.contentStream, content)
    }

    private fun setPageContent(doc: PDDocument, page: PDPage, content: String) {
        val stream = PDStream(doc)
        writeContent(stream, content)
        page.setContents(stream)
    }

    private fun writeContent(stream: PDStream, content: String) {
        stream.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
    }

    private fun flatten(root: DrawNode): List<DrawNode> {
        val nodes = ArrayList<DrawNode>()
        fun visit(node: DrawNode) {
            for (child in node.children) {
                nodes.add(child)
                visit(child)
            }
        }
        visit(root)
        return nodes
    }

    private fun samplePdf(): ByteArray {
        val content =
            "BT /F1 24 Tf 100 700 Td (Hello World) Tj ET\n1 0 0 rg\n50 50 200 100 re f\n"
        val length = content.toByteArray(Charsets.ISO_8859_1).size
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length $length >>\nstream\n${content}endstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
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
