package com.xingmihai.shrunner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingmihai.shrunner.data.RunPrefs

/**
 * 执行环境配置：自定义环境变量与常用开关。
 * 脚本里的原生程序常因为缺环境变量（PATH / TMPDIR / LD_LIBRARY_PATH）
 * 或联网检查拿到异常结果而崩溃，这里可以补上。
 */
@Composable
fun EnvScreen(prefs: RunPrefs, modifier: Modifier = Modifier) {
    var envText by remember { mutableStateOf(prefs.customEnv) }
    var skipUpdate by remember { mutableStateOf(prefs.skipUpdateCheck) }
    var closeStdin by remember { mutableStateOf(prefs.closeStdin) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("执行环境", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "跳过更新检查",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = skipUpdate,
                onCheckedChange = { skipUpdate = it; prefs.skipUpdateCheck = it }
            )
        }
        Text(
            "注入 SKIP_UPDATE_CHECK / NO_UPDATE_CHECK / DISABLE_UPDATE_CHECK / AUTO_UPDATE 等变量。" +
                "脚本卡在「检查更新中」然后崩溃（如 stoi 报错）时打开它。",
            style = MaterialTheme.typography.bodySmall
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "关闭标准输入",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Switch(
                checked = closeStdin,
                onCheckedChange = { closeStdin = it; prefs.closeStdin = it }
            )
        }
        Text(
            "让脚本读到的输入恒为空。脚本不需要交互、却一直卡在等待输入时打开。",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        Text("自定义环境变量", style = MaterialTheme.typography.titleMedium)
        Text(
            "一行一个，格式 KEY=VALUE。例如 http_proxy=http://127.0.0.1:7890",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = envText,
            onValueChange = { envText = it; prefs.customEnv = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            minLines = 6,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            placeholder = { Text("http_proxy=http://host:port", fontSize = 12.sp) }
        )
    }
}
