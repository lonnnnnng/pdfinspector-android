package SVS.pdfinspector.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.IOException

// Page-space edit for one element: translate by dx,dy, scale by scaleX,scaleY
// about the element's lower-left, recolor, and/or replace text.
class EditRequest(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val fillArgb: Int? = null,
    val strokeArgb: Int? = null,
    val newText: String? = null,
)

// What the edit form may offer for a given node.
class EditCaps(
    val canGeom: Boolean,
    val canFill: Boolean,
    val canStroke: Boolean,
    val canText: Boolean,
)

sealed class EditResult {
    class Applied(val tokens: List<Any>) : EditResult()
    object NoChange : EditResult()
    object Degenerate : EditResult()
    object TextEncodeFailed : EditResult()
}

object ElementEditor {

    // Drops tokens [start, end] and rewrites the page content stream. Everything
    // outside the range is preserved byte-equivalent through ContentStreamWriter.
    fun deleteRange(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        start: Int,
        end: Int,
    ): List<Any> {
        val kept = ArrayList<Any>(tokens.size)
        for (i in tokens.indices) {
            if (i < start || i > end) kept.add(tokens[i])
        }
        writeStream(document, page, kept)
        return kept
    }

    // Captures the page's current content stream so an edit can be reverted.
    fun snapshot(page: PDPage): ByteArray? = page.contents?.use { it.readBytes() }

    fun restore(document: PDDocument, page: PDPage, content: ByteArray) {
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { out -> out.write(content) }
        page.setContents(stream)
    }

    // Geometry needs a q/cm/Q wrapper, illegal inside a text object, so it is
    // offered on paths, images and whole text objects (BT..ET) but never runs.
    fun capabilities(tokens: List<Any>, node: DrawNode): EditCaps {
        val textObject = node.kind == NodeKind.TEXT && isOp(tokens.getOrNull(node.startIndex), "BT")
        val textRun = node.kind == NodeKind.TEXT && !textObject
        val path = node.kind == NodeKind.PATH
        val image = node.kind == NodeKind.IMAGE
        val wrappable = path || image || textObject
        val canGeom = wrappable && node.bounds != null && node.ctm?.inverse() != null
        val paint = (tokens.getOrNull(node.endIndex) as? Operator)?.name ?: ""
        val pathFill = path && (paint == "f" || paint == "F" || paint == "f*" ||
            paint.startsWith("B") || paint.startsWith("b"))
        val pathStroke = path && (paint == "S" || paint == "s" ||
            paint.startsWith("B") || paint.startsWith("b"))
        return EditCaps(
            canGeom = canGeom,
            canFill = pathFill || textObject || textRun,
            canStroke = pathStroke,
            canText = textRun,
        )
    }

    fun editElement(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
    ): EditResult {
        val result = rebuild(tokens, node, request)
        if (result is EditResult.Applied) writeStream(document, page, result.tokens)
        return result
    }

    private fun rebuild(tokens: List<Any>, node: DrawNode, request: EditRequest): EditResult {
        val textObject = node.kind == NodeKind.TEXT && isOp(tokens.getOrNull(node.startIndex), "BT")
        val textRun = node.kind == NodeKind.TEXT && !textObject
        if (textRun) return rebuildTextRun(tokens, node, request)

        val wrappable = node.kind == NodeKind.PATH || node.kind == NodeKind.IMAGE || textObject
        val wantsGeom = request.dx != 0f || request.dy != 0f ||
            request.scaleX != 1f || request.scaleY != 1f
        val wantsColor = request.fillArgb != null || request.strokeArgb != null
        if (!wrappable || (!wantsGeom && !wantsColor)) return EditResult.NoChange

        val prefix = ArrayList<Any>()
        prefix.add(Operator.getOperator("q"))
        if (wantsGeom) {
            val m = node.ctm ?: return EditResult.Degenerate
            val b = node.bounds ?: return EditResult.Degenerate
            val inv = m.inverse() ?: return EditResult.Degenerate
            val t = Affine.scaleAbout(request.scaleX, request.scaleY, b.minX, b.minY)
                .then(Affine.translate(request.dx, request.dy))
            val c = m.then(t).then(inv)
            prefix.add(COSFloat(c.a)); prefix.add(COSFloat(c.b)); prefix.add(COSFloat(c.c))
            prefix.add(COSFloat(c.d)); prefix.add(COSFloat(c.e)); prefix.add(COSFloat(c.f))
            prefix.add(Operator.getOperator("cm"))
        }
        request.fillArgb?.let { appendColor(prefix, it, false) }
        request.strokeArgb?.let { appendColor(prefix, it, true) }

        val out = ArrayList<Any>(tokens.size + prefix.size + 1)
        out.addAll(tokens.subList(0, node.startIndex))
        out.addAll(prefix)
        out.addAll(tokens.subList(node.startIndex, node.endIndex + 1))
        out.add(Operator.getOperator("Q"))
        out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
        return EditResult.Applied(out)
    }

    private fun rebuildTextRun(tokens: List<Any>, node: DrawNode, request: EditRequest): EditResult {
        val wantsColor = request.fillArgb != null
        val newText = request.newText
        if (!wantsColor && newText == null) return EditResult.NoChange

        val out = ArrayList<Any>(tokens.size + 4)
        out.addAll(tokens.subList(0, node.startIndex))
        request.fillArgb?.let { appendColor(out, it, false) }
        if (newText != null) {
            val font = node.font ?: return EditResult.TextEncodeFailed
            val bytes = try {
                font.encode(newText)
            } catch (_: IOException) {
                return EditResult.TextEncodeFailed
            } catch (_: IllegalArgumentException) {
                return EditResult.TextEncodeFailed
            } catch (_: Exception) {
                return EditResult.TextEncodeFailed
            }
            for (i in node.startIndex until node.endIndex - 1) out.add(tokens[i])
            out.add(COSString(bytes))
            val opName = (tokens[node.endIndex] as? Operator)?.name
            out.add(if (opName == "TJ") Operator.getOperator("Tj") else tokens[node.endIndex])
        } else {
            out.addAll(tokens.subList(node.startIndex, node.endIndex + 1))
        }
        out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
        return EditResult.Applied(out)
    }

    private fun appendColor(out: MutableList<Any>, argb: Int, stroke: Boolean) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        out.add(COSFloat(r)); out.add(COSFloat(g)); out.add(COSFloat(b))
        out.add(Operator.getOperator(if (stroke) "RG" else "rg"))
    }

    private fun writeStream(document: PDDocument, page: PDPage, tokens: List<Any>) {
        val stream = PDStream(document)
        stream.createOutputStream(COSName.FLATE_DECODE).use { out ->
            ContentStreamWriter(out).writeTokens(tokens)
        }
        page.setContents(stream)
    }

    private fun isOp(token: Any?, name: String): Boolean =
        token is Operator && token.name == name
}
