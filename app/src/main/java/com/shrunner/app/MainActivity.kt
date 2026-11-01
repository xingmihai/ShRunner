package com.shrunner.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shrunner.app.system.Shortcuts
import com.shrunner.app.ui.AppRoot
import com.shrunner.app.ui.theme.ShRunnerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var launchScript by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch; otherwise rotation would re-open the run screen.
        if (savedInstanceState == null) launchScript = scriptFrom(intent)
        setContent {
            ShRunnerTheme {
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
