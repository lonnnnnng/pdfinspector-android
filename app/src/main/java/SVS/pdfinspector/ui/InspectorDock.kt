package SVS.pdfinspector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun InspectorDock(
    dock: Dock,
    transparent: Boolean,
    sizeDp: Dp,
    onResizePx: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val color =
        if (transparent) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
        else MaterialTheme.colorScheme.surfaceContainer
    val shape =
        if (dock == Dock.BOTTOM) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        else RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)

    if (dock == Dock.BOTTOM) {
        Column(modifier) {
            ResizeHandle(dock, onResizePx)
            Surface(
                modifier = Modifier.fillMaxWidth().height(sizeDp),
                color = color,
                shape = shape,
                tonalElevation = 1.dp,
                shadowElevation = 3.dp,
            ) { content() }
        }
    } else {
        Row(modifier) {
            ResizeHandle(dock, onResizePx)
            Surface(
                modifier = Modifier.width(sizeDp).fillMaxHeight(),
                color = color,
                shape = shape,
                tonalElevation = 1.dp,
                shadowElevation = 3.dp,
            ) { content() }
        }
    }
}

@Composable
private fun ResizeHandle(dock: Dock, onResizePx: (Float) -> Unit) {
    val grip = MaterialTheme.colorScheme.outlineVariant
    if (dock == Dock.BOTTOM) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .semantics { contentDescription = "调整检查器面板大小" }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dy ->
                        change.consume()
                        onResizePx(dy)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(grip))
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(40.dp)
                .semantics { contentDescription = "调整检查器面板大小" }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dx ->
                        change.consume()
                        onResizePx(dx)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 4.dp, height = 36.dp).clip(RoundedCornerShape(2.dp)).background(grip))
        }
    }
}
