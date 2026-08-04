package com.loooong.reader

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.loooong.reader.ui.EbookApp
import com.loooong.reader.ui.theme.InspectorTheme
import com.loooong.reader.ui.theme.rememberThemeState

class EbookActivity : FragmentActivity() {
    private val viewModel: EbookViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // long: Readium Navigator 依赖运行时 Publication，系统不能用默认构造器恢复它；旋转后由 ViewModel 重新挂载并恢复 Locator。
        super.onCreate(null)
        enableEdgeToEdge()
        val initialUri = intent?.data
        setContent {
            val themeState = rememberThemeState()
            InspectorTheme(
                mode = themeState.mode,
                dynamicColor = themeState.dynamic,
                accent = themeState.accent,
            ) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let { viewModel.openUri(this, it) } }
                EbookApp(
                    activity = this,
                    viewModel = viewModel,
                    initialUri = initialUri,
                    onChooseFile = {
                        launcher.launch(
                            arrayOf("application/epub+zip", "text/plain", "application/octet-stream"),
                        )
                    },
                )
            }
        }
    }
}
