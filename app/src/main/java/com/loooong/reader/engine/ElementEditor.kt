package com.loooong.reader.engine

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlin.math.roundToInt

// long: 单个元素的页面空间编辑请求；默认以左下角缩放，画布直接操作可显式传入中心锚点并旋转。
class EditRequest(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val fillArgb: Int? = null,
    val strokeArgb: Int? = null,
    val newText: String? = null,
    val fontEntryId: String? = null,
)

class TextInsertRequest(
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float,
    val font: PDFont,
    val fillArgb: Int,
)

class ImageInsertRequest(
    val bytes: ByteArray,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
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
    class TextEncodeFailed(val chars: String? = null) : EditResult()
}

class StreamEditSnapshot internal constructor(
    val owner: StreamOwner,
    val content: ByteArray,
    internal val explicitResources: COSDictionary?,
    internal val formPath: List<PDFormXObject>,
)

typealias PageEditSnapshot = StreamEditSnapshot

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
        writeStream(
            document,
            page,
            ParsedStream(StreamOwner.Page(page), kept, page.resources),
            kept,
        )
        return kept
    }

    fun deleteNode(document: PDDocument, page: PDPage, node: DrawNode): List<Any> {
        val parsedStream = requireNotNull(node.stream) { "Element has no content-stream owner" }
        val kept = ArrayList<Any>(parsedStream.tokens.size)
        for (i in parsedStream.tokens.indices) {
            if (i < node.startIndex || i > node.endIndex) kept.add(parsedStream.tokens[i])
        }
        writeStream(document, page, parsedStream, kept)
        return kept
    }

    /** long: 多选删除一次性重写页面流，避免多个节点删除时旧 token 索引逐次失效。 */
    fun deleteNodes(document: PDDocument, page: PDPage, nodes: List<DrawNode>): EditResult {
        if (nodes.isEmpty()) return EditResult.NoChange
        val stream = nodes.first().stream ?: return EditResult.NoChange
        if (stream.owner !is StreamOwner.Page || nodes.any { it.stream !== stream }) return EditResult.NoChange
        val ranges = nodes.map { it.startIndex..it.endIndex }.sortedBy { it.first }
        if (ranges.any { it.first < 0 || it.last >= stream.tokens.size } ||
            ranges.zipWithNext().any { (left, right) -> left.last >= right.first }
        ) {
            return EditResult.NoChange
        }
        val kept = ArrayList<Any>(stream.tokens.size)
        var rangeIndex = 0
        for (index in stream.tokens.indices) {
            while (rangeIndex < ranges.size && index > ranges[rangeIndex].last) rangeIndex++
            if (rangeIndex >= ranges.size || index !in ranges[rangeIndex]) kept.add(stream.tokens[index])
        }
        writeStream(document, page, stream, kept)
        return EditResult.Applied(kept)
    }

    fun insertText(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        request: TextInsertRequest,
    ): EditResult {
        if (request.text.isEmpty() || request.fontSize <= 0f) return EditResult.NoChange
        val lines = request.text.split('\n')
        val encodedLines = try {
            lines.map { request.font.encode(it) }
        } catch (_: Exception) {
            return EditResult.TextEncodeFailed(unsupportedChars(request.font, request.text))
        }
        val resources = copyPageResources(page)
        val fontName = resources.add(request.font)
        val out = ArrayList<Any>(tokens.size + 18)
        out.addAll(tokens)
        out.add(Operator.getOperator("q"))
        out.add(Operator.getOperator("BT"))
        out.add(fontName)
        out.add(COSFloat(request.fontSize))
        out.add(Operator.getOperator("Tf"))
        out.add(COSFloat(request.fontSize))
        out.add(Operator.getOperator("TL"))
        appendColor(out, request.fillArgb, false)
        out.add(COSFloat(1f)); out.add(COSFloat(0f));
        out.add(COSFloat(0f)); out.add(COSFloat(1f))
        out.add(COSFloat(request.x)); out.add(COSFloat(request.y))
        out.add(Operator.getOperator("Tm"))
        encodedLines.forEachIndexed { index, bytes ->
            out.add(COSString(bytes))
            out.add(Operator.getOperator("Tj"))
            if (index != encodedLines.lastIndex) out.add(Operator.getOperator("T*"))
        }
        out.add(Operator.getOperator("ET"))
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, ParsedStream(StreamOwner.Page(page), tokens, resources), out)
        return EditResult.Applied(out)
    }

    fun insertImage(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        request: ImageInsertRequest,
    ): EditResult {
        if (request.bytes.isEmpty() || request.width <= 0f || request.height <= 0f) {
            return EditResult.NoChange
        }
        val image = PDImageXObject.createFromByteArray(document, request.bytes, "inserted-image")
        val resources = copyPageResources(page)
        val imageName = resources.add(image)
        val out = ArrayList<Any>(tokens.size + 12)
        out.addAll(tokens)
        out.add(Operator.getOperator("q"))
        out.add(COSFloat(request.width)); out.add(COSFloat(0f))
        out.add(COSFloat(0f)); out.add(COSFloat(request.height))
        out.add(COSFloat(request.x)); out.add(COSFloat(request.y))
        out.add(Operator.getOperator("cm"))
        out.add(imageName)
        out.add(Operator.getOperator("Do"))
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, ParsedStream(StreamOwner.Page(page), tokens, resources), out)
        return EditResult.Applied(out)
    }

    /**
     * long: 复制页面级路径、图片或完整文本对象时，重新绑定资源并以平移矩阵追加到内容流末尾。
     * 文本运行（BT/ET 内的单个 Tj/TJ）不应被单独包在 q/Q 中，调用方需改走 insertText。
     */
    fun pasteNode(
        document: PDDocument,
        page: PDPage,
        tokens: List<Any>,
        copiedTokens: List<Any>,
        sourceResources: PDResources?,
        dx: Float,
        dy: Float,
    ): EditResult {
        if (copiedTokens.isEmpty() || !dx.isFinite() || !dy.isFinite()) return EditResult.NoChange
        val resources = copyPageResources(page)
        val remapped = remapResourceNames(copiedTokens, sourceResources, resources)
        val out = ArrayList<Any>(tokens.size + remapped.size + 12)
        out.addAll(tokens)
        out.add(Operator.getOperator("q"))
        out.add(COSFloat(1f)); out.add(COSFloat(0f))
        out.add(COSFloat(0f)); out.add(COSFloat(1f))
        out.add(COSFloat(dx)); out.add(COSFloat(dy))
        out.add(Operator.getOperator("cm"))
        out.addAll(remapped)
        out.add(Operator.getOperator("Q"))
        writeStream(document, page, ParsedStream(StreamOwner.Page(page), tokens, resources), out)
        return EditResult.Applied(out)
    }

    private fun writeStream(
        document: PDDocument,
        page: PDPage,
        stream: ParsedStream,
        tokens: List<Any>,
    ) {
        when (val owner = stream.owner) {
            is StreamOwner.Page -> {
                val stream = PDStream(document)
                stream.createOutputStream(COSName.FLATE_DECODE).use { out ->
                    ContentStreamWriter(out).writeTokens(tokens)
                }
                commitPage(document, owner.page, stream)
            }
            is StreamOwner.Form -> {
                owner.form.cosObject.createOutputStream().use { out ->
                    ContentStreamWriter(out).writeTokens(tokens)
                }
                commitForm(document, page, stream.formPath.ifEmpty { listOf(owner.form) })
            }
        }
    }

    // 同时记录实际内容流及其显式资源，Form 内字体替换也必须能够完整撤销。
    fun snapshot(page: PDPage): PageEditSnapshot = snapshot(
        ParsedStream(StreamOwner.Page(page), emptyList(), page.resources),
    )

    fun snapshot(stream: ParsedStream): StreamEditSnapshot = StreamEditSnapshot(
        owner = stream.owner,
        content = readContent(stream.owner),
        explicitResources = ownerDictionary(stream.owner)
            .getDictionaryObject(COSName.RESOURCES) as? COSDictionary,
        formPath = stream.formPath,
    )

    fun snapshot(owner: StreamOwner): StreamEditSnapshot = StreamEditSnapshot(
        owner = owner,
        content = readContent(owner),
        explicitResources = ownerDictionary(owner)
            .getDictionaryObject(COSName.RESOURCES) as? COSDictionary,
        formPath = (owner as? StreamOwner.Form)?.let { listOf(it.form) } ?: emptyList(),
    )

    fun restore(document: PDDocument, page: PDPage, snapshot: StreamEditSnapshot) {
        // 必须通过 setter 同步 PDFBox 包装对象的资源缓存，直接改 COS 字典会继续返回旧资源。
        setResources(snapshot.owner, snapshot.explicitResources?.let(::PDResources))
        when (val owner = snapshot.owner) {
            is StreamOwner.Page -> {
                val stream = PDStream(document)
                stream.createOutputStream(COSName.FLATE_DECODE).use { out -> out.write(snapshot.content) }
                commitPage(document, owner.page, stream)
            }
            is StreamOwner.Form -> {
                owner.form.cosObject.createOutputStream().use { out -> out.write(snapshot.content) }
                commitForm(
                    document,
                    page,
                    snapshot.formPath.ifEmpty { listOf(owner.form) },
                )
            }
        }
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
        // A filled/stroked path always has a paint color (sampled from the page
        // when its color space is unresolved). Text only exposes a color when one
        // was set, so gate text color on that.
        val hasColor = node.colorArgb != null
        return EditCaps(
            canGeom = canGeom,
            canFill = pathFill || ((textObject || textRun) && hasColor),
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
        substituteFont: PDFont? = null,
        subScale: Float = 1f,
    ): EditResult {
        val editStream = ParsedStream(StreamOwner.Page(page), tokens, page.resources)
        val result = rebuild(tokens, node, request, editStream, substituteFont, subScale)
        if (result is EditResult.Applied) {
            writeStream(document, page, editStream, result.tokens)
        }
        return result
    }

    /** long: 对齐操作按 token 起点倒序改写，所有节点完成后只写流一次，形成单一撤销事务。 */
    fun editElements(
        document: PDDocument,
        page: PDPage,
        edits: List<Pair<DrawNode, EditRequest>>,
    ): EditResult {
        if (edits.isEmpty()) return EditResult.NoChange
        val source = edits.first().first.stream ?: return EditResult.NoChange
        if (source.owner !is StreamOwner.Page || edits.any { it.first.stream !== source }) {
            return EditResult.NoChange
        }
        var tokens = source.tokens
        var changed = false
        for ((node, request) in edits.sortedByDescending { it.first.startIndex }) {
            val currentStream = ParsedStream(source.owner, tokens, source.resources, source.formPath)
            when (val result = rebuild(tokens, node, request, currentStream, null, 1f)) {
                is EditResult.Applied -> {
                    tokens = result.tokens
                    changed = true
                }
                EditResult.NoChange -> Unit
                else -> return result
            }
        }
        if (!changed) return EditResult.NoChange
        writeStream(document, page, source, tokens)
        return EditResult.Applied(tokens)
    }

    /** long: 只移动已经验证为自包含的 q/Q 区间，调用方负责给出同层目标插入位置。 */
    fun reorderRange(
        document: PDDocument,
        page: PDPage,
        stream: ParsedStream,
        start: Int,
        end: Int,
        insertAt: Int,
    ): EditResult {
        if (stream.owner !is StreamOwner.Page || start !in stream.tokens.indices ||
            end !in stream.tokens.indices || start > end || insertAt !in 0..stream.tokens.size
        ) {
            return EditResult.NoChange
        }
        if (insertAt in (start + 1)..(end + 1)) return EditResult.NoChange
        val block = stream.tokens.subList(start, end + 1).toList()
        val out = ArrayList<Any>(stream.tokens.size)
        out.addAll(stream.tokens.subList(0, start))
        out.addAll(stream.tokens.subList(end + 1, stream.tokens.size))
        val adjusted = if (insertAt > end) insertAt - block.size else insertAt
        if (adjusted !in 0..out.size || adjusted == start) return EditResult.NoChange
        out.addAll(adjusted, block)
        writeStream(document, page, stream, out)
        return EditResult.Applied(out)
    }

    fun editElement(
        document: PDDocument,
        page: PDPage,
        node: DrawNode,
        request: EditRequest,
        substituteFont: PDFont? = null,
        subScale: Float = 1f,
    ): EditResult {
        val editStream = requireNotNull(node.stream) { "Element has no content-stream owner" }
        val result = rebuild(editStream.tokens, node, request, editStream, substituteFont, subScale)
        if (result is EditResult.Applied) {
            writeStream(document, page, editStream, result.tokens)
        }
        return result
    }

    private fun rebuild(
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        editStream: ParsedStream,
        substituteFont: PDFont?,
        subScale: Float,
    ): EditResult {
        val textObject = node.kind == NodeKind.TEXT && isOp(tokens.getOrNull(node.startIndex), "BT")
        val textRun = node.kind == NodeKind.TEXT && !textObject
        if (textRun) {
            return rebuildTextRun(tokens, node, request, editStream, substituteFont, subScale)
        }

        val wrappable = node.kind == NodeKind.PATH || node.kind == NodeKind.IMAGE || textObject
        val wantsGeom = request.dx != 0f || request.dy != 0f ||
            request.scaleX != 1f || request.scaleY != 1f || request.rotationDegrees != 0f
        val wantsColor = request.fillArgb != null || request.strokeArgb != null
        if (!wrappable || (!wantsGeom && !wantsColor)) return EditResult.NoChange

        val prefix = ArrayList<Any>()
        prefix.add(Operator.getOperator("q"))
        if (wantsGeom) {
            val m = node.ctm ?: return EditResult.Degenerate
            val b = node.bounds ?: return EditResult.Degenerate
            val inv = m.inverse() ?: return EditResult.Degenerate
            val pivotX = request.pivotX ?: b.minX
            val pivotY = request.pivotY ?: b.minY
            val t = Affine.scaleAbout(request.scaleX, request.scaleY, pivotX, pivotY)
                .then(Affine.rotateAbout(request.rotationDegrees, pivotX, pivotY))
                .then(Affine.translate(request.dx, request.dy))
            val c = m.then(t).then(inv)
            prefix.add(COSFloat(c.a)); prefix.add(COSFloat(c.b)); prefix.add(COSFloat(c.c))
            prefix.add(COSFloat(c.d)); prefix.add(COSFloat(c.e)); prefix.add(COSFloat(c.f))
            prefix.add(Operator.getOperator("cm"))
        }
        // Paths/images carry no color operator inside the wrapped range, so a new
        // color sits in the prefix. A text object DOES set colors inside BT/ET,
        // so its fill must be restated before each run to win; done in the body.
        if (!textObject) {
            request.fillArgb?.let { appendColor(prefix, it, false) }
            request.strokeArgb?.let { appendColor(prefix, it, true) }
        }

        val out = ArrayList<Any>(tokens.size + prefix.size + 8)
        out.addAll(tokens.subList(0, node.startIndex))
        out.addAll(prefix)
        val blockFill = if (textObject) request.fillArgb else null
        if (blockFill != null) {
            val runStarts = node.children.mapTo(HashSet()) { it.startIndex }
            for (i in node.startIndex..node.endIndex) {
                if (i in runStarts) appendColor(out, blockFill, false)
                out.add(tokens[i])
            }
        } else {
            out.addAll(tokens.subList(node.startIndex, node.endIndex + 1))
        }
        out.add(Operator.getOperator("Q"))
        out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
        return EditResult.Applied(out)
    }

    // Rewrites one show-text run. When substituteFont is given the run is
    // re-encoded with it: a Tf selecting the new resource is emitted before the
    // run and the original Tf restated after, since q/Q cannot wrap text.
    private fun rebuildTextRun(
        tokens: List<Any>,
        node: DrawNode,
        request: EditRequest,
        editStream: ParsedStream,
        substituteFont: PDFont?,
        subScale: Float,
    ): EditResult {
        val wantsColor = request.fillArgb != null
        val newText = request.newText
        if (!wantsColor && newText == null) return EditResult.NoChange

        val out = ArrayList<Any>(tokens.size + 8)
        out.addAll(tokens.subList(0, node.startIndex))
        request.fillArgb?.let { appendColor(out, it, false) }
        if (newText != null) {
            val font = substituteFont ?: node.font ?: return EditResult.TextEncodeFailed()
            val bytes = try {
                font.encode(newText)
            } catch (_: Exception) {
                return EditResult.TextEncodeFailed(unsupportedChars(font, newText))
            }
            val newName = if (substituteFont != null) {
                val res = copyResourcesForFontEdit(editStream)
                ensureFontResource(res, substituteFont)
            } else {
                null
            }
            if (newName != null) {
                out.add(newName)
                out.add(COSFloat(node.fontSize * subScale))
                out.add(Operator.getOperator("Tf"))
            }
            val opName = (tokens[node.endIndex] as? Operator)?.name
            if (opName == "TJ") {
                val original = tokens.getOrNull(node.endIndex - 1) as? COSArray
                val rebuilt = original?.let { rebuildTextArray(it, newText, font) }
                    ?: return EditResult.TextEncodeFailed()
                for (i in node.startIndex until node.endIndex - 1) out.add(tokens[i])
                out.add(rebuilt)
                out.add(tokens[node.endIndex])
            } else {
                for (i in node.startIndex until node.endIndex - 1) out.add(tokens[i])
                out.add(COSString(bytes))
                out.add(tokens[node.endIndex])
            }
            if (newName != null && !node.fontResourceName.isNullOrBlank()) {
                out.add(COSName.getPDFName(node.fontResourceName))
                out.add(COSFloat(node.fontSize))
                out.add(Operator.getOperator("Tf"))
            }
        } else {
            out.addAll(tokens.subList(node.startIndex, node.endIndex + 1))
        }
        out.addAll(tokens.subList(node.endIndex + 1, tokens.size))
        return EditResult.Applied(out)
    }

    private fun rebuildTextArray(original: COSArray, newText: String, font: PDFont): COSArray? {
        val stringIndexes = (0 until original.size())
            .filter { original.getObject(it) is COSString }
        if (stringIndexes.isEmpty()) return null

        val weights = stringIndexes.map {
            (original.getObject(it) as COSString).bytes.size.coerceAtLeast(1)
        }
        val totalWeight = weights.sum()
        val codePoints = newText.codePoints().toArray()
        var stringCursor = 0
        var codePointStart = 0
        var cumulativeWeight = 0
        val segments = ArrayList<ByteArray>(stringIndexes.size)

        for (ignored in stringIndexes) {
            cumulativeWeight += weights[stringCursor]
            // 按原字符串片段的占比分配新文字，让 TJ 中的字距调整仍落在相近位置。
            val codePointEnd = if (stringCursor == stringIndexes.lastIndex) {
                codePoints.size
            } else {
                (codePoints.size * cumulativeWeight.toFloat() / totalWeight)
                    .roundToInt()
                    .coerceIn(codePointStart, codePoints.size)
            }
            val segment = String(codePoints, codePointStart, codePointEnd - codePointStart)
            val encoded = try {
                font.encode(segment)
            } catch (_: Exception) {
                return null
            }
            segments.add(encoded)
            codePointStart = codePointEnd
            stringCursor++
        }

        if (segments.all { it.isEmpty() }) {
            return COSArray().apply { add(COSString(ByteArray(0))) }
        }

        val rebuilt = COSArray()
        stringCursor = 0
        for (i in 0 until original.size()) {
            val item = original.getObject(i)
            if (item is COSString) {
                val encoded = segments[stringCursor++]
                if (encoded.isNotEmpty()) rebuilt.add(COSString(encoded))
            } else {
                // 只保留两个有效文本片段之间的位移，空片段不能推动后续文字位置。
                val hasTextBefore = segments.take(stringCursor).any { it.isNotEmpty() }
                val hasTextAfter = segments.drop(stringCursor).any { it.isNotEmpty() }
                if (hasTextBefore && hasTextAfter) rebuilt.add(original.get(i))
            }
        }
        return rebuilt
    }

    private fun copyResourcesForFontEdit(stream: ParsedStream): PDResources {
        val inherited = stream.resources
        val copiedDictionary = if (inherited == null) {
            COSDictionary()
        } else {
            COSDictionary(inherited.cosObject).apply {
                // Font 子字典也要复制，否则向当前页添加字体仍会污染共享资源。
                val fonts = inherited.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary
                if (fonts != null) setItem(COSName.FONT, COSDictionary(fonts))
            }
        }
        return PDResources(copiedDictionary).also { setResources(stream.owner, it) }
    }

    private fun copyPageResources(page: PDPage): PDResources {
        val inherited = page.resources
        val copiedDictionary = if (inherited == null) {
            COSDictionary()
        } else {
            COSDictionary(inherited.cosObject).apply {
                listOf(COSName.FONT, COSName.XOBJECT).forEach { key ->
                    (inherited.cosObject.getDictionaryObject(key) as? COSDictionary)
                        ?.let { setItem(key, COSDictionary(it)) }
                }
            }
        }
        return PDResources(copiedDictionary).also { page.resources = it }
    }

    private fun remapResourceNames(
        copiedTokens: List<Any>,
        sourceResources: PDResources?,
        targetResources: PDResources,
    ): List<Any> {
        if (sourceResources == null) return copiedTokens
        val names = HashMap<String, COSName>()
        val out = ArrayList<Any>(copiedTokens.size)
        for (i in copiedTokens.indices) {
            val token = copiedTokens[i]
            if (token is COSName && i + 1 < copiedTokens.size) {
                val op = copiedTokens[i + 1] as? Operator
                val mapped = when (op?.name) {
                    "Tf" -> runCatching { sourceResources.getFont(token) }
                        .getOrNull()?.let { sourceFont ->
                            names.getOrPut(token.name) { targetResources.add(sourceFont) }
                        }
                    "Do" -> runCatching { sourceResources.getXObject(token) }
                        .getOrNull()?.let { xObject ->
                            names.getOrPut(token.name) {
                                when (xObject) {
                                    is PDImageXObject -> targetResources.add(xObject)
                                    is PDFormXObject -> targetResources.add(xObject)
                                    else -> token
                                }
                            }
                        }
                    else -> null
                }
                out.add(mapped ?: token)
            } else {
                out.add(token)
            }
        }
        return out
    }

    // Reuses an already-added resource when the same font is applied to several
    // runs, so repeated edits don't pile up duplicate /Font entries.
    private fun ensureFontResource(resources: PDResources, font: PDFont): COSName {
        for (name in resources.fontNames) {
            val existing = try {
                resources.getFont(name)
            } catch (_: Exception) {
                null
            }
            if (existing != null && existing.cosObject === font.cosObject) return name
        }
        return resources.add(font)
    }

    // The distinct characters the font cannot encode, for a useful message.
    private fun unsupportedChars(font: PDFont, text: String): String {
        val bad = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            try {
                font.encode(s)
            } catch (_: Exception) {
                if (s.isNotBlank()) bad.add(s)
            }
            i += Character.charCount(cp)
        }
        return bad.joinToString(" ")
    }

    private fun appendColor(out: MutableList<Any>, argb: Int, stroke: Boolean) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        out.add(COSFloat(r)); out.add(COSFloat(g)); out.add(COSFloat(b))
        out.add(Operator.getOperator(if (stroke) "RG" else "rg"))
    }

    // Swap the page's content stream and flag what changed so saveIncremental
    // appends just those objects instead of rewriting the whole file.
    //
    // setNeedToBeUpdated is a TRAVERSAL GATE, not only a write filter: the
    // incremental writer walks the object graph from the trailer and refuses to
    // descend into an unflagged object. So flagging only the page + new stream
    // leaves them unreachable and the increment comes out empty. The entire
    // chain catalog -> pages -> page -> stream must be flagged.
    //
    // Flags are never cleared by the writer and saveIncremental always re-reads
    // the original bytes, so flags left on across edits make changes accumulate
    // correctly. clearContentsFlag drops the superseded stream so orphaned
    // streams don't pile up in the cache.
    private fun commitPage(document: PDDocument, page: PDPage, stream: PDStream) {
        clearContentsFlag(page.cosObject.getDictionaryObject(COSName.CONTENTS))
        page.setContents(stream)
        stream.cosObject.setNeedToBeUpdated(true)
        markResources(page.resources)
        markPageChain(document, page)
    }

    private fun commitForm(
        document: PDDocument,
        page: PDPage,
        formPath: List<PDFormXObject>,
    ) {
        // 增量 writer 把更新标记当作遍历门；页资源、每层 XObject 字典和外层
        // Form 都必须打开，才能沿调用路径走到真正修改的共享 Form 流。
        markResources(page.resources)
        for (form in formPath) {
            form.cosObject.setNeedToBeUpdated(true)
            markResources(form.resources)
        }
        markPageChain(document, page)
    }

    private fun markResources(resources: PDResources?) {
        val dictionary = resources?.cosObject ?: return
        dictionary.setNeedToBeUpdated(true)
        (dictionary.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)
            ?.setNeedToBeUpdated(true)
    }

    private fun markPageChain(document: PDDocument, page: PDPage) {
        var dict: COSDictionary? = page.cosObject
        while (dict != null) {
            dict.setNeedToBeUpdated(true)
            dict = dict.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        document.documentCatalog.cosObject.setNeedToBeUpdated(true)
    }

    private fun readContent(owner: StreamOwner): ByteArray = when (owner) {
        is StreamOwner.Page -> owner.page.contents?.use { it.readBytes() } ?: ByteArray(0)
        is StreamOwner.Form -> owner.form.contents?.use { it.readBytes() } ?: ByteArray(0)
    }

    private fun ownerDictionary(owner: StreamOwner): COSDictionary = when (owner) {
        is StreamOwner.Page -> owner.page.cosObject
        is StreamOwner.Form -> owner.form.cosObject
    }

    private fun setResources(owner: StreamOwner, resources: PDResources?) {
        when (owner) {
            is StreamOwner.Page -> owner.page.resources = resources
            is StreamOwner.Form -> owner.form.resources = resources
        }
    }

    private fun clearContentsFlag(contents: COSBase?) {
        when (contents) {
            is COSStream -> contents.setNeedToBeUpdated(false)
            is COSArray -> for (i in 0 until contents.size()) {
                (contents.getObject(i) as? COSStream)?.setNeedToBeUpdated(false)
            }
            else -> {}
        }
    }

    private fun isOp(token: Any?, name: String): Boolean =
        token is Operator && token.name == name
}
