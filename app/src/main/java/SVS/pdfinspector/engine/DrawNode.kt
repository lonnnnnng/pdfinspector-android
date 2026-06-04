package SVS.pdfinspector.engine

enum class NodeKind { GROUP, TEXT, PATH, IMAGE }

// One node in the inspector tree, mapped to a contiguous run of content-stream
// tokens [startIndex, endIndex] so it can be highlighted, deleted, or rewritten.
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
)

class ParsedPage(
    val tokens: List<Any>,
    val root: DrawNode,
    val leaves: List<DrawNode>,
)
