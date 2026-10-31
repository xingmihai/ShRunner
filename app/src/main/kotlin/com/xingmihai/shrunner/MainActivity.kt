package com.xingmihai.shrunner

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xingmihai.shrunner.ui.ShRunnerRoot
import com.xingmihai.shrunner.ui.theme.ShRunnerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ 起强制边到边，Android 17 上不可关闭
        enableEdgeToEdge()
        setContent {
            ShRunnerTheme {
                ShRunnerRoot(initialScriptId = intent?.getStringExtra(EXTRA_SCRIPT_ID))
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    companion object {
        const val ACTION_RUN = "com.xingmihai.shrunner.RUN"
        const val EXTRA_SCRIPT_ID = "script_id"
    }
}

/** Android 17 判定：大屏自适应、本地网络权限等行为变更从这一级开始生效。 */
val isAndroid17OrAbove: Boolean
    get() = Build.VERSION.SDK_INT >= 37
