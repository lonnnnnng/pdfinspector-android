package SVS.pdfinspector.engine

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject

enum class NodeKind { GROUP, TEXT, PATH, IMAGE }

// token 索引只能解释为所属内容流中的位置；Form 路径同时保留增量保存时
// 从页面资源走到目标共享流所需的精确遍历链。
sealed class StreamOwner {
    class Page(val page: PDPage) : StreamOwner()
    class Form(val form: PDFormXObject) : StreamOwner()
}

class ParsedStream(
    val owner: StreamOwner,
    val tokens: List<Any>,
    val resources: PDResources?,
    val formPath: List<PDFormXObject> = emptyList(),
)

// One node in the inspector tree, mapped to a contiguous run of content-stream
// tokens [startIndex, endIndex] so it can be highlighted, deleted, or rewritten.
// ctm is the CTM active at the node, used to map page-space edits into the local
// matrix of a q/cm/Q wrapper; font is the run's font, used to re-encode text.
class DrawNode(
    val id: Int,
    val kind: NodeKind,
    val label: String,
    val detail: String,
    val startIndex: Int,
    val endIndex: Int,
    val bounds: Bounds?,
    val colorArgb: Int?,
    val raw: String,
    val children: List<DrawNode>,
    val text: String? = null,
    val ctm: Affine? = null,
    val font: PDFont? = null,
    val fontResourceName: String? = null,
    val fontSize: Float = 0f,
    val colorSpace: String? = null,
    val stream: ParsedStream? = null,
)

class ParsedPage(
    val pageStream: ParsedStream,
    val root: DrawNode,
    val leaves: List<DrawNode>,
) {
    val tokens: List<Any> get() = pageStream.tokens
}

fun findNode(node: DrawNode, id: Int?): DrawNode? {
    if (id == null) return null
    if (node.id == id) return node
    for (c in node.children) findNode(c, id)?.let { return it }
    return null
}

fun collectGroupIds(node: DrawNode, into: MutableSet<Int> = HashSet()): Set<Int> {
    if (node.kind == NodeKind.GROUP) into.add(node.id)
    for (c in node.children) collectGroupIds(c, into)
    return into
}
