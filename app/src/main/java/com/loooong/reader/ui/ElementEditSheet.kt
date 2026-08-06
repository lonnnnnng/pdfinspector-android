package com.loooong.reader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loooong.reader.AUTO_FONT_ID
import com.loooong.reader.EditTarget
import com.loooong.reader.FontOption
import com.loooong.reader.FontSource
import com.loooong.reader.engine.EditRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementEditSheet(
    target: EditTarget,
    onApply: (EditRequest) -> Unit,
    onImportFont: (Uri) -> Unit,
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
    var useFallback by remember(id) { mutableStateOf(true) }
    var fontId by remember(id) { mutableStateOf<String?>(AUTO_FONT_ID) }
    val xInvalid = caps.canGeom && parseFiniteFloat(x) == null
    val yInvalid = caps.canGeom && parseFiniteFloat(y) == null
    val widthInvalid = caps.canGeom && parsePositiveFloat(w) == null
    val heightInvalid = caps.canGeom && parsePositiveFloat(h) == null
    val fillInvalid = caps.canFill && fill.isNotBlank() && parseHex(fill) == null
    val strokeInvalid = caps.canStroke && stroke.isNotBlank() && parseHex(stroke) == null
    val formValid = !xInvalid && !yInvalid && !widthInvalid && !heightInvalid &&
        !fillInvalid && !strokeInvalid

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? -> if (uri != null) onImportFont(uri) }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                target.node.label,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (target.editsSharedForm) {
                Text(
                    "编辑此表单对象会同步更新所有引用位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (caps.canGeom) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("位置", style = MaterialTheme.typography.labelLarge)
                    AdaptiveNumberPair(
                        firstLabel = "X",
                        firstValue = x,
                        firstInvalid = xInvalid,
                        onFirstChange = { x = it },
                        secondLabel = "Y",
                        secondValue = y,
                        secondInvalid = yInvalid,
                        onSecondChange = { y = it },
                        errorMessage = "请输入有效数字",
                    )
                    Text(
                        "单位为 PDF 点，原点位于左下角",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("尺寸", style = MaterialTheme.typography.labelLarge)
                    AdaptiveNumberPair(
                        firstLabel = "宽",
                        firstValue = w,
                        firstInvalid = widthInvalid,
                        onFirstChange = { w = it },
                        secondLabel = "高",
                        secondValue = h,
                        secondInvalid = heightInvalid,
                        onSecondChange = { h = it },
                        errorMessage = "请输入大于 0 的数字",
                    )
                }
            }

            if (caps.canFill) {
                OutlinedTextField(
                    value = fill,
                    onValueChange = { fill = it },
                    label = { Text("填充颜色  #RRGGBB") },
                    singleLine = true,
                    isError = fillInvalid,
                    supportingText = if (fillInvalid) {
                        { Text("请输入 #RRGGBB 格式的颜色") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (caps.canStroke) {
                OutlinedTextField(
                    value = stroke,
                    onValueChange = { stroke = it },
                    label = { Text("描边颜色  #RRGGBB") },
                    singleLine = true,
                    isError = strokeInvalid,
                    supportingText = if (strokeInvalid) {
                        { Text("请输入 #RRGGBB 格式的颜色") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val cs = target.colorSpace
            if ((caps.canFill || caps.canStroke) && cs != null && cs != "RGB") {
                Text(
                    "色彩空间：${localizedColorSpace(cs)}。编辑后将以 RGB 保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (caps.canText) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("文本") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = useFallback,
                            role = Role.Checkbox,
                            onValueChange = { on ->
                                useFallback = on
                                if (on && fontId == null) fontId = AUTO_FONT_ID
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = useFallback,
                        onCheckedChange = null,
                    )
                    Text("使用备用字体")
                }
                if (useFallback) {
                    FontPicker(
                        options = target.fontOptions,
                        selectedId = fontId ?: AUTO_FONT_ID,
                        onSelect = { fontId = it },
                        onAddCustom = { importLauncher.launch("*/*") },
                    )
                }
                Text(
                    if (useFallback) {
                        "自动选择与原字体最接近的字体，也可以从列表中选择或添加自定义字体。"
                    } else {
                        "使用元素原字体重新编码，仅在识别到完全匹配的字体时自动替换。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    enabled = formValid,
                    onClick = {
                        onApply(
                            buildRequest(
                                target, x, y, w, h, fill, stroke, text,
                                if (useFallback) fontId ?: AUTO_FONT_ID else null,
                            ),
                        )
                    },
                ) { Text("应用") }
            }
        }
    }
}

@Composable
private fun AdaptiveNumberPair(
    firstLabel: String,
    firstValue: String,
    firstInvalid: Boolean,
    onFirstChange: (String) -> Unit,
    secondLabel: String,
    secondValue: String,
    secondInvalid: Boolean,
    onSecondChange: (String) -> Unit,
    errorMessage: String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // long: 分屏和手机横屏可用宽度不足时改为单列，避免字段标签与错误文案互相挤压。
        if (maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(firstLabel, firstValue, onFirstChange, firstInvalid, errorMessage)
                NumberField(secondLabel, secondValue, onSecondChange, secondInvalid, errorMessage)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    firstLabel,
                    firstValue,
                    onFirstChange,
                    firstInvalid,
                    errorMessage,
                    Modifier.weight(1f),
                )
                NumberField(
                    secondLabel,
                    secondValue,
                    onSecondChange,
                    secondInvalid,
                    errorMessage,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    errorMessage: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError) ({ Text(errorMessage) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontPicker(
    options: List<FontOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onAddCustom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (selectedId == AUTO_FONT_ID) {
        "自动（匹配原字体）"
    } else {
        options.firstOrNull { it.id == selectedId }?.let(::fontLabel) ?: "自动（匹配原字体）"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("备用字体") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("自动（匹配原字体）") },
                onClick = { onSelect(AUTO_FONT_ID); expanded = false },
            )
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(fontLabel(opt)) },
                    onClick = { onSelect(opt.id); expanded = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("添加自定义字体") },
                onClick = { expanded = false; onAddCustom() },
            )
        }
    }
}

private fun fontLabel(o: FontOption): String = when (o.source) {
    FontSource.SYSTEM -> "${o.displayName}（系统）"
    FontSource.CUSTOM -> "${o.displayName}（自定义）"
    FontSource.BUNDLED -> o.displayName
}

private fun localizedColorSpace(value: String): String = when {
    value == "Gray" -> "灰度"
    value == "Pattern" -> "图案"
    value == "Color space" -> "未知色彩空间"
    value.startsWith("Separation") -> value.replaceFirst("Separation", "专色")
    else -> value
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
    fontId: String?,
): EditRequest {
    val caps = target.caps
    val nx = x.toFloatOrNull() ?: target.x
    val ny = y.toFloatOrNull() ?: target.y
    val nw = w.toFloatOrNull() ?: target.w
    val nh = h.toFloatOrNull() ?: target.h
    val parsedFill = if (caps.canFill) parseHex(fill) else null
    val parsedStroke = if (caps.canStroke) parseHex(stroke) else null
    val textChanged = caps.canText && text != (target.text ?: "")
    val fontChosen = caps.canText && fontId != null
    return EditRequest(
        dx = if (caps.canGeom) nx - target.x else 0f,
        dy = if (caps.canGeom) ny - target.y else 0f,
        scaleX = if (caps.canGeom && target.w != 0f && nw > 0f) nw / target.w else 1f,
        scaleY = if (caps.canGeom && target.h != 0f && nh > 0f) nh / target.h else 1f,
        fillArgb = if (parsedFill != null && parsedFill != target.fillArgb) parsedFill else null,
        strokeArgb = if (parsedStroke != null && parsedStroke != target.strokeArgb) parsedStroke else null,
        newText = if (textChanged || fontChosen) text else null,
        fontEntryId = if (fontChosen) fontId else null,
    )
}

private fun fmt(v: Float): String =
    if (v == v.toLong().toFloat()) {
        v.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", v)
    }

private fun argbToHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

private fun parseHex(s: String): Int? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    val v = t.toIntOrNull(16) ?: return null
    return (0xFF shl 24) or v
}

private fun parseFiniteFloat(value: String): Float? =
    value.toFloatOrNull()?.takeIf { it.isFinite() }

private fun parsePositiveFloat(value: String): Float? =
    parseFiniteFloat(value)?.takeIf { it > 0f }
