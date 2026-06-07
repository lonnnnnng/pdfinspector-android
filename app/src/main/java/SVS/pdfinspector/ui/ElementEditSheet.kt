package SVS.pdfinspector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import SVS.pdfinspector.EditTarget
import SVS.pdfinspector.engine.EditRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementEditSheet(
    target: EditTarget,
    onApply: (EditRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val caps = target.caps
    val id = target.node.id
    var x by remember(id) { mutableStateOf(fmt(target.x)) }
    var y by remember(id) { mutableStateOf(fmt(target.y)) }
    var w by remember(id) { mutableStateOf(fmt(target.w)) }
    var h by remember(id) { mutableStateOf(fmt(target.h)) }
    var fill by remember(id) { mutableStateOf(target.fillArgb?.let(::argbToHex) ?: "") }
    var stroke by remember(id) { mutableStateOf(target.strokeArgb?.let(::argbToHex) ?: "") }
    var text by remember(id) { mutableStateOf(target.text ?: "") }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                target.node.label,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (caps.canGeom) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Position", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField("X", x, { x = it }, Modifier.weight(1f))
                        NumberField("Y", y, { y = it }, Modifier.weight(1f))
                    }
                    Text(
                        "PDF points, origin bottom-left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField("W", w, { w = it }, Modifier.weight(1f))
                        NumberField("H", h, { h = it }, Modifier.weight(1f))
                    }
                }
            }

            if (caps.canFill) {
                OutlinedTextField(
                    value = fill,
                    onValueChange = { fill = it },
                    label = { Text("Fill color  #RRGGBB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (caps.canStroke) {
                OutlinedTextField(
                    value = stroke,
                    onValueChange = { stroke = it },
                    label = { Text("Stroke color  #RRGGBB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (caps.canText) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Experimental: text is re-encoded with the element's font. " +
                        "Characters the font lacks will fail and the edit is skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    onApply(
                        buildRequest(target, x, y, w, h, fill, stroke, text),
                    )
                }) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun buildRequest(
    target: EditTarget,
    x: String,
    y: String,
    w: String,
    h: String,
    fill: String,
    stroke: String,
    text: String,
): EditRequest {
    val caps = target.caps
    val nx = x.toFloatOrNull() ?: target.x
    val ny = y.toFloatOrNull() ?: target.y
    val nw = w.toFloatOrNull() ?: target.w
    val nh = h.toFloatOrNull() ?: target.h
    val parsedFill = if (caps.canFill) parseHex(fill) else null
    val parsedStroke = if (caps.canStroke) parseHex(stroke) else null
    return EditRequest(
        dx = if (caps.canGeom) nx - target.x else 0f,
        dy = if (caps.canGeom) ny - target.y else 0f,
        scaleX = if (caps.canGeom && target.w != 0f && nw > 0f) nw / target.w else 1f,
        scaleY = if (caps.canGeom && target.h != 0f && nh > 0f) nh / target.h else 1f,
        fillArgb = if (parsedFill != null && parsedFill != target.fillArgb) parsedFill else null,
        strokeArgb = if (parsedStroke != null && parsedStroke != target.strokeArgb) parsedStroke else null,
        newText = if (caps.canText && text != (target.text ?: "")) text else null,
    )
}

private fun fmt(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else String.format("%.2f", v)

private fun argbToHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

private fun parseHex(s: String): Int? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    val v = t.toIntOrNull(16) ?: return null
    return (0xFF shl 24) or v
}
