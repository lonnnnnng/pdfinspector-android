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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

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
    fitMode: FitMode,
    onUserTransform: () -> Unit,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        var showDebug by remember { mutableStateOf(false) }
        var mem by remember { mutableStateOf(MemStats(0, 0, 0)) }
        LaunchedEffect(showDebug) {
            while (showDebug) {
                mem = readMemStats()
                delay(1000)
            }
        }

        fun fitWidth() {
            if (viewportW > 0f && bitmap.width > 0) {
                val s = viewportW / bitmap.width
                scale = s
                offset = Offset(0f, ((viewportH - bitmap.height * s) / 2f).coerceAtLeast(0f))
            }
        }

        fun fitHeight() {
            if (viewportH > 0f && bitmap.height > 0) {
                val s = viewportH / bitmap.height
                scale = s
                offset = Offset(((viewportW - bitmap.width * s) / 2f).coerceAtLeast(0f), 0f)
            }
        }

        // Global zoom: kept across pages; only re-fit while a fit mode is active.
        LaunchedEffect(bitmap, viewportW, viewportH, fitMode) {
            when (fitMode) {
                FitMode.WIDTH -> fitWidth()
                FitMode.HEIGHT -> fitHeight()
                FitMode.NONE -> Unit
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .fourFingerTap { showDebug = !showDebug }
                .pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f || pan != Offset.Zero) onUserTransform()
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offset = centroid - (centroid - offset) * (newScale / scale) + pan
                        scale = newScale
                    }
                }
                .pointerInput(bitmap, leaves) {
                    detectTapGestures { tap ->
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

        if (showDebug) {
            DebugOverlay(
                bitmap = bitmap,
                scale = scale,
                mem = mem,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}
