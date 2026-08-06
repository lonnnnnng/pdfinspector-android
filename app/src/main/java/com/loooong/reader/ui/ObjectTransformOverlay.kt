package com.loooong.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowsDiagonal2
import compose.icons.tablericons.RotateClockwise2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val HANDLE_TOUCH_SIZE = 48.dp
private val HANDLE_VISUAL_SIZE = 32.dp

@Composable
internal fun ObjectTransformOverlay(
    objectId: Int,
    rect: Rect,
    canvasScale: Float,
    canvasOffset: Offset,
    color: Color,
    enabled: Boolean,
    onCommit: (translationBitmap: Offset, scale: Float, screenRotationDegrees: Float) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthPx = (rect.width * canvasScale).coerceAtLeast(1f)
    val heightPx = (rect.height * canvasScale).coerceAtLeast(1f)
    val minTouchPx = with(density) { HANDLE_TOUCH_SIZE.toPx() }
    val touchWidthPx = max(widthPx, minTouchPx)
    val touchHeightPx = max(heightPx, minTouchPx)
    val centerX = rect.center.x * canvasScale + canvasOffset.x
    val centerY = rect.center.y * canvasScale + canvasOffset.y
    val leftPx = centerX - touchWidthPx / 2f
    val topPx = centerY - touchHeightPx / 2f
    val referencePx = max(widthPx, heightPx).coerceAtLeast(minTouchPx)
    val widthDp = with(density) { widthPx.toDp() }
    val heightDp = with(density) { heightPx.toDp() }

    var translation by remember(objectId, rect) { mutableStateOf(Offset.Zero) }
    var scaleDrag by remember(objectId, rect) { mutableStateOf(Offset.Zero) }
    var previewScale by remember(objectId, rect) { mutableStateOf(1f) }
    var previewRotation by remember(objectId, rect) { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (leftPx + translation.x).roundToInt(),
                    (topPx + translation.y).roundToInt(),
                )
            }
            .size(
                width = with(density) { touchWidthPx.toDp() },
                height = with(density) { touchHeightPx.toDp() },
            )
            .graphicsLayer {
                transformOrigin = TransformOrigin.Center
                scaleX = previewScale
                scaleY = previewScale
                rotationZ = previewRotation
            }
            .semantics {
                role = Role.Button
                contentDescription = "已选元素，拖动可移动"
            }
            .pointerInput(objectId, enabled, canvasScale) {
                if (enabled) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            translation += amount
                        },
                        onDragEnd = {
                            val delta = translation / canvasScale
                            translation = Offset.Zero
                            if (delta != Offset.Zero) onCommit(delta, 1f, 0f)
                        },
                        onDragCancel = { translation = Offset.Zero },
                    )
                }
            },
    ) {
        if (!enabled) return@Box

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(widthDp, heightDp)
                .border(2.dp, color),
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -(heightDp / 2f + 14.dp))
                .width(2.dp)
                .height(28.dp)
                .background(color),
        )

        TransformHandle(
            description = "旋转元素",
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -(heightDp / 2f + 40.dp)),
            icon = { Icon(TablerIcons.RotateClockwise2, contentDescription = null) },
            onDrag = { amount -> previewRotation += amount.x * 0.6f },
            onDragEnd = {
                val rotation = previewRotation
                previewRotation = 0f
                if (abs(rotation) >= 0.1f) onCommit(Offset.Zero, 1f, rotation)
            },
            onDragCancel = { previewRotation = 0f },
        )

        TransformHandle(
            description = "等比缩放元素",
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = widthDp / 2f, y = heightDp / 2f),
            icon = { Icon(TablerIcons.ArrowsDiagonal2, contentDescription = null) },
            onDrag = { amount ->
                scaleDrag += amount
                previewScale = (
                    1f + (scaleDrag.x + scaleDrag.y) / (referencePx * 1.4f)
                ).coerceIn(0.15f, 8f)
            },
            onDragEnd = {
                val scale = previewScale
                scaleDrag = Offset.Zero
                previewScale = 1f
                if (abs(scale - 1f) >= 0.001f) onCommit(Offset.Zero, scale, 0f)
            },
            onDragCancel = {
                scaleDrag = Offset.Zero
                previewScale = 1f
            },
        )
    }
}

@Composable
private fun BoxScope.TransformHandle(
    description: String,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(HANDLE_TOUCH_SIZE)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .pointerInput(description) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(HANDLE_VISUAL_SIZE),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 3.dp,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
            }
        }
    }
}
