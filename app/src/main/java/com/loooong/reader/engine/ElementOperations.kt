package com.loooong.reader.engine

enum class AlignmentAction {
    LEFT,
    HORIZONTAL_CENTER,
    RIGHT,
    TOP,
    VERTICAL_CENTER,
    BOTTOM,
    DISTRIBUTE_HORIZONTAL,
    DISTRIBUTE_VERTICAL,
}

enum class LayerAction { FORWARD, BACKWARD, TO_FRONT, TO_BACK }

data class ElementTranslation(val id: Int, val dx: Float, val dy: Float)

object ElementAlignment {
    /** long: 所有对齐均使用 PDF 页面坐标的视觉外接框，页面旋转只影响显示，不参与这里的几何计算。 */
    fun compute(nodes: List<DrawNode>, action: AlignmentAction): List<ElementTranslation> {
        val items = nodes.mapNotNull { node -> node.bounds?.let { node to it } }
        if (items.size < 2) return emptyList()
        val minX = items.minOf { it.second.minX }
        val maxX = items.maxOf { it.second.maxX }
        val minY = items.minOf { it.second.minY }
        val maxY = items.maxOf { it.second.maxY }
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f

        val translations = when (action) {
            AlignmentAction.LEFT -> items.map { (node, b) -> ElementTranslation(node.id, minX - b.minX, 0f) }
            AlignmentAction.HORIZONTAL_CENTER -> items.map { (node, b) ->
                ElementTranslation(node.id, centerX - (b.minX + b.maxX) / 2f, 0f)
            }
            AlignmentAction.RIGHT -> items.map { (node, b) -> ElementTranslation(node.id, maxX - b.maxX, 0f) }
            AlignmentAction.TOP -> items.map { (node, b) -> ElementTranslation(node.id, 0f, maxY - b.maxY) }
            AlignmentAction.VERTICAL_CENTER -> items.map { (node, b) ->
                ElementTranslation(node.id, 0f, centerY - (b.minY + b.maxY) / 2f)
            }
            AlignmentAction.BOTTOM -> items.map { (node, b) -> ElementTranslation(node.id, 0f, minY - b.minY) }
            AlignmentAction.DISTRIBUTE_HORIZONTAL -> distribute(items, horizontal = true)
            AlignmentAction.DISTRIBUTE_VERTICAL -> distribute(items, horizontal = false)
        }
        return translations.filter { kotlin.math.abs(it.dx) > EPSILON || kotlin.math.abs(it.dy) > EPSILON }
    }

    private fun distribute(
        items: List<Pair<DrawNode, Bounds>>,
        horizontal: Boolean,
    ): List<ElementTranslation> {
        if (items.size < 3) return emptyList()
        val sorted = if (horizontal) items.sortedBy { it.second.minX } else items.sortedBy { it.second.minY }
        val first = sorted.first().second
        val last = sorted.last().second
        val span = if (horizontal) last.maxX - first.minX else last.maxY - first.minY
        val occupied = sorted.sumOf {
            (if (horizontal) it.second.width else it.second.height).toDouble()
        }.toFloat()
        val gap = (span - occupied) / (sorted.size - 1)
        var cursor = if (horizontal) first.minX else first.minY
        return sorted.mapIndexed { index, (node, bounds) ->
            val delta = if (index == 0 || index == sorted.lastIndex) {
                0f
            } else if (horizontal) {
                cursor - bounds.minX
            } else {
                cursor - bounds.minY
            }
            cursor += (if (horizontal) bounds.width else bounds.height) + gap
            if (horizontal) ElementTranslation(node.id, delta, 0f)
            else ElementTranslation(node.id, 0f, delta)
        }
    }

    private const val EPSILON = 0.001f
}
