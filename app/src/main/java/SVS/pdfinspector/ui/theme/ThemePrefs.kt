package SVS.pdfinspector.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

enum class Accent(val label: String) {
    TEAL("墨绿色"),
    BLUE("蓝色"),
    VIOLET("紫色"),
    GREEN("绿色"),
    AMBER("琥珀色"),
}

class ThemeState(
    initialMode: ThemeMode,
    initialDynamic: Boolean,
    initialAccent: Accent,
    private val prefs: SharedPreferences,
) {
    var mode by mutableStateOf(initialMode)
        private set
    var dynamic by mutableStateOf(initialDynamic)
        private set
    var accent by mutableStateOf(initialAccent)
        private set

    fun updateMode(value: ThemeMode) {
        mode = value
        prefs.edit().putString(KEY_MODE, value.name).apply()
    }

    fun updateDynamic(value: Boolean) {
        dynamic = value
        prefs.edit().putBoolean(KEY_DYNAMIC, value).apply()
    }

    fun updateAccent(value: Accent) {
        accent = value
        prefs.edit().putString(KEY_ACCENT, value.name).apply()
    }

    companion object {
        const val PREFS = "theme_prefs"
        const val KEY_MODE = "mode"
        const val KEY_DYNAMIC = "dynamic"
        const val KEY_ACCENT = "accent"
    }
}

@Composable
fun rememberThemeState(): ThemeState {
    val context = LocalContext.current
    return remember {
        val prefs = context.getSharedPreferences(ThemeState.PREFS, Context.MODE_PRIVATE)
        val mode = runCatching {
            ThemeMode.valueOf(prefs.getString(ThemeState.KEY_MODE, ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        val accent = runCatching {
            Accent.valueOf(prefs.getString(ThemeState.KEY_ACCENT, Accent.TEAL.name)!!)
        }.getOrDefault(Accent.TEAL)
        // long: 首次启动先展示应用品牌色；用户仍可在设置中主动启用系统动态配色。
        val dynamic = prefs.getBoolean(ThemeState.KEY_DYNAMIC, false)
        ThemeState(mode, dynamic, accent, prefs)
    }
}
