package com.loooong.reader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.loooong.reader.engine.Bounds

// Maps PDF user-space bounds onto the rendered page bitmap (pixels). Rotation
// 0 is exact; 90/180/270 are best-effort and may be slightly off on the canvas
// highlight, but tree selection and deletion never depend on this mapping.
class PageTransform(
    private val cropX: Float,
    private val cropY: Float,
    private val cropW: Float,
    private val cropH: Float,
    private val rotation: Int,
    private val scale: Float,
) {
    fun toRect(b: Bounds): Rect {
        val xs = floatArrayOf(b.minX, b.maxX, b.maxX, b.minX)
        val ys = floatArrayOf(b.minY, b.minY, b.maxY, b.maxY)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        val rot = ((rotation % 360) + 360) % 360
        for (i in 0..3) {
            val u = xs[i] - cropX
            val v = ys[i] - cropY
            val dx: Float
            val dy: Float
            when (rot) {
                90 -> { dx = v; dy = u }
                180 -> { dx = cropW - u; dy = v }
                270 -> { dx = cropH - v; dy = cropW - u }
                else -> { dx = u; dy = cropH - v }
            }
            val bx = dx * scale
            val by = dy * scale
            if (bx < left) left = bx
            if (by < top) top = by
            if (bx > right) right = bx
            if (by > bottom) bottom = by
        }
        return Rect(left, top, right, bottom)
    }

    // long: 画布拖动量先去掉渲染缩放，再按页面旋转反算到 PDF 用户空间，保证视觉方向与落盘方向一致。
    fun toUserDelta(bitmapDelta: Offset): Offset {
        val dx = bitmapDelta.x / scale
        val dy = bitmapDelta.y / scale
        return when (((rotation % 360) + 360) % 360) {
            90 -> Offset(dy, dx)
            180 -> Offset(-dx, dy)
            270 -> Offset(-dy, -dx)
            else -> Offset(dx, -dy)
        }
    }

    // long: 页面映射到屏幕后方向发生镜像，因此屏幕顺时针角度需要反号才能写回 PDF 坐标。
    fun toUserRotation(screenDegrees: Float): Float = -screenDegrees
}
