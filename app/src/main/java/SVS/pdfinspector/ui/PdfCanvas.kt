package SVS.pdfinspector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput

data class LeafRect(val id: Int, val rect: Rect)

private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 12f

@Composable
fun PdfCanvas(
    bitmap: ImageBitmap,
    leaves: List<LeafRect>,
    selectedRect: Rect?,
    highlightColor: Color,
    backdropColor: Color,
    selectable: Boolean,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()

        var scale by remember(bitmap) { mutableFloatStateOf(1f) }
        var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

        LaunchedEffect(bitmap, viewportW, viewportH) {
            if (viewportW > 0f && bitmap.width > 0) {
                val fit = viewportW / bitmap.width
                scale = fit
                offset = Offset(0f, ((viewportH - bitmap.height * fit) / 2f).coerceAtLeast(0f))
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offset = centroid - (centroid - offset) * (newScale / scale) + pan
                        scale = newScale
                    }
                }
                .pointerInput(bitmap, leaves, selectable) {
                    detectTapGestures { tap ->
                        if (!selectable) return@detectTapGestures
                        val point = Offset((tap.x - offset.x) / scale, (tap.y - offset.y) / scale)
                        val hit = leaves.lastOrNull { it.rect.contains(point) }
                        onSelect(hit?.id)
                    }
                },
        ) {
            drawRect(color = backdropColor, size = size)
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawImage(image = bitmap, topLeft = Offset.Zero)
                selectedRect?.let { r ->
                    drawRect(color = highlightColor.copy(alpha = 0.18f), topLeft = r.topLeft, size = r.size)
                    drawRect(
                        color = highlightColor,
                        topLeft = r.topLeft,
                        size = r.size,
                        style = Stroke(width = 2f / scale),
                    )
                }
            }
        }
    }
}
