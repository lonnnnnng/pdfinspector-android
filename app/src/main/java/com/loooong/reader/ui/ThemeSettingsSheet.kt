package com.loooong.reader.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronLeft
import com.loooong.reader.ui.theme.Accent
import com.loooong.reader.ui.theme.Palettes
import com.loooong.reader.ui.theme.ThemeMode
import com.loooong.reader.ui.theme.ThemeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(theme: ThemeState, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(TablerIcons.ChevronLeft, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
            item { SectionLabel("外观") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("主题", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = ThemeMode.entries
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = theme.mode == mode,
                                onClick = { theme.updateMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            ) { Text(mode.label) }
                        }
                    }
                }
            }

            val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (dynamicAvailable) {
                item {
                    ListItem(
                        headlineContent = { Text("动态配色") },
                        supportingContent = { Text("跟随系统壁纸配色") },
                        trailingContent = {
                            Switch(
                                checked = theme.dynamic,
                                onCheckedChange = theme::updateDynamic,
                            )
                        },
                    )
                }
            }

            item {
                val accentEnabled = !(theme.dynamic && dynamicAvailable)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "强调色",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.alpha(if (accentEnabled) 1f else 0.5f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Accent.entries.forEach { accent ->
                            Swatch(
                                label = accent.label,
                                color = Palettes.swatch(accent),
                                selected = theme.accent == accent,
                                enabled = accentEnabled,
                                onClick = { theme.updateAccent(accent) },
                            )
                        }
                    }
                    if (!accentEnabled) {
                        Text(
                            "关闭动态配色后可以选择强调色。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            item { SectionLabel("更新") }
            item { OnlineUpdateSection() }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Swatch(
    label: String,
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
            .semantics {
                contentDescription = "$label 强调色"
                this.selected = selected
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = TablerIcons.Check,
                contentDescription = null,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
    }
}
