package SVS.pdfinspector.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7DF8DD),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8DE),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFF426277),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC7E7FF),
    onTertiaryContainer = Color(0xFF001E2E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF7),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FBF7),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F1),
    surfaceContainer = Color(0xFFE9EFEB),
    surfaceContainerHigh = Color(0xFFE3E9E6),
    surfaceContainerHighest = Color(0xFFDEE4E0),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C3),
    inverseSurface = Color(0xFF2B322F),
    inverseOnSurface = Color(0xFFECF2EE),
    inversePrimary = Color(0xFF5DDBC1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DDBC1),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005144),
    onPrimaryContainer = Color(0xFF7DF8DD),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1C352F),
    secondaryContainer = Color(0xFF324B45),
    onSecondaryContainer = Color(0xFFCCE8DE),
    tertiary = Color(0xFFAACBE3),
    onTertiary = Color(0xFF0F3447),
    tertiaryContainer = Color(0xFF294A5F),
    onTertiaryContainer = Color(0xFFC7E7FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF303634),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4945),
    inverseSurface = Color(0xFFDEE4E0),
    inverseOnSurface = Color(0xFF2B322F),
    inversePrimary = Color(0xFF006B5B),
)

@Composable
fun InspectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
