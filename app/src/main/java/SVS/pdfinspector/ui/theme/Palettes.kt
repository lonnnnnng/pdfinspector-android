package SVS.pdfinspector.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// long: 中性表面保持稳定，只替换强调色角色，避免编辑器工具区因主题切换产生层级漂移。
private val BaseLight = lightColorScheme(
    background = Color(0xFFF6F8F5),
    onBackground = Color(0xFF1A1D1B),
    surface = Color(0xFFF6F8F5),
    onSurface = Color(0xFF1A1D1B),
    surfaceVariant = Color(0xFFE1E7E2),
    onSurfaceVariant = Color(0xFF454B47),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3EF),
    surfaceContainer = Color(0xFFEAEEEA),
    surfaceContainerHigh = Color(0xFFE4E9E5),
    surfaceContainerHighest = Color(0xFFDDE4DF),
    outline = Color(0xFF747B76),
    outlineVariant = Color(0xFFC3CBC5),
    inverseSurface = Color(0xFF2E322F),
    inverseOnSurface = Color(0xFFF0F2EF),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val BaseDark = darkColorScheme(
    background = Color(0xFF111512),
    onBackground = Color(0xFFE1E7E2),
    surface = Color(0xFF111512),
    onSurface = Color(0xFFE1E7E2),
    surfaceVariant = Color(0xFF404842),
    onSurfaceVariant = Color(0xFFC0C9C2),
    surfaceContainerLowest = Color(0xFF0B0F0C),
    surfaceContainerLow = Color(0xFF181D19),
    surfaceContainer = Color(0xFF1C211D),
    surfaceContainerHigh = Color(0xFF262C27),
    surfaceContainerHighest = Color(0xFF313732),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404842),
    inverseSurface = Color(0xFFE1E7E2),
    inverseOnSurface = Color(0xFF2D322E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val tertiary: Color,
    val inversePrimary: Color,
)

private fun rolesFor(accent: Accent, dark: Boolean): AccentRoles = when (accent) {
    Accent.TEAL -> if (!dark) AccentRoles(
        Color(0xFF174C3C), Color(0xFFFFFFFF), Color(0xFFD5E8DF), Color(0xFF09291F),
        Color(0xFF52665D), Color(0xFFA5661A), Color(0xFF9BD3BD),
    ) else AccentRoles(
        Color(0xFF9BD3BD), Color(0xFF00382C), Color(0xFF1D4E3F), Color(0xFFB7EFDA),
        Color(0xFFB7CBBF), Color(0xFFF2B866), Color(0xFF174C3C),
    )
    Accent.BLUE -> if (!dark) AccentRoles(
        Color(0xFF215FA6), Color(0xFFFFFFFF), Color(0xFFD6E3FF), Color(0xFF001B3D),
        Color(0xFF565F71), Color(0xFF705574), Color(0xFFA9C7FF),
    ) else AccentRoles(
        Color(0xFFA9C7FF), Color(0xFF00315F), Color(0xFF004787), Color(0xFFD6E3FF),
        Color(0xFFBEC6DC), Color(0xFFDCBCE0), Color(0xFF215FA6),
    )
    Accent.VIOLET -> if (!dark) AccentRoles(
        Color(0xFF6750A4), Color(0xFFFFFFFF), Color(0xFFEADDFF), Color(0xFF21005D),
        Color(0xFF625B71), Color(0xFF7D5260), Color(0xFFD0BCFF),
    ) else AccentRoles(
        Color(0xFFD0BCFF), Color(0xFF381E72), Color(0xFF4F378B), Color(0xFFEADDFF),
        Color(0xFFCCC2DC), Color(0xFFEFB8C8), Color(0xFF6750A4),
    )
    Accent.GREEN -> if (!dark) AccentRoles(
        Color(0xFF3B6939), Color(0xFFFFFFFF), Color(0xFFBCF0B4), Color(0xFF002105),
        Color(0xFF52634F), Color(0xFF38656A), Color(0xFFA1D399),
    ) else AccentRoles(
        Color(0xFFA1D399), Color(0xFF0A390F), Color(0xFF235024), Color(0xFFBCF0B4),
        Color(0xFFB9CCB4), Color(0xFFA0CFD4), Color(0xFF3B6939),
    )
    Accent.AMBER -> if (!dark) AccentRoles(
        Color(0xFF855300), Color(0xFFFFFFFF), Color(0xFFFFDDB3), Color(0xFF2A1800),
        Color(0xFF6F5B40), Color(0xFF51643F), Color(0xFFFFB95C),
    ) else AccentRoles(
        Color(0xFFFFB95C), Color(0xFF472A00), Color(0xFF653E00), Color(0xFFFFDDB3),
        Color(0xFFDDC2A1), Color(0xFFB6CCA0), Color(0xFF855300),
    )
}

object Palettes {
    fun scheme(accent: Accent, dark: Boolean): ColorScheme {
        val base = if (dark) BaseDark else BaseLight
        val r = rolesFor(accent, dark)
        return base.copy(
            primary = r.primary,
            onPrimary = r.onPrimary,
            primaryContainer = r.primaryContainer,
            onPrimaryContainer = r.onPrimaryContainer,
            secondary = r.secondary,
            onSecondary = if (dark) BaseDark.background else Color.White,
            secondaryContainer = lerp(base.surface, r.secondary, if (dark) 0.24f else 0.14f),
            onSecondaryContainer = base.onSurface,
            tertiary = r.tertiary,
            onTertiary = if (dark) BaseDark.background else Color.White,
            tertiaryContainer = lerp(base.surface, r.tertiary, if (dark) 0.24f else 0.14f),
            onTertiaryContainer = base.onSurface,
            inversePrimary = r.inversePrimary,
        )
    }

    fun swatch(accent: Accent): Color = rolesFor(accent, false).primary
}
