package SVS.pdfinspector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealContainer,
    onPrimaryContainer = Teal,
    secondary = TealLight,
    tertiary = Amber,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    primaryContainer = Teal,
    secondary = TealContainer,
    tertiary = Amber,
)

@Composable
fun InspectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
