package com.shrunner.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrunner.app.data.SettingsStore
import com.shrunner.app.data.ThemeMode
import com.shrunner.app.i18n.LocaleHelper
import com.shrunner.app.system.Shortcuts
import com.shrunner.app.ui.AppRoot
import com.shrunner.app.ui.theme.ShRunnerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsStore
    private var launchScript by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        // Chinese by default; English only if the user chose it in Settings.
        super.attachBaseContext(LocaleHelper.wrap(newBase, SettingsStore.readLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch; otherwise rotation would re-open the run screen.
        if (savedInstanceState == null) launchScript = scriptFrom(intent)
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle()
            val dynamic by settings.dynamicColor.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Keep status/navigation bar icon colors in sync with the chosen theme, not the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            ShRunnerTheme(themeMode = mode, dynamicColor = dynamic) {
                AppRoot(launchScript = launchScript, onLaunchConsumed = { launchScript = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchScript = scriptFrom(intent)
    }

    private fun scriptFrom(intent: Intent?): String? =
        if (intent?.action == Shortcuts.ACTION_RUN) intent.getStringExtra(Shortcuts.EXTRA_SCRIPT) else null
}
