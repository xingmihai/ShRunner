package com.xingmihai.shrunner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingmihai.shrunner.data.RunPrefs
import com.xingmihai.shrunner.engine.ShellEnv
import com.xingmihai.shrunner.util.NetCheck
import kotlinx.coroutines.launch

/** 设置页：常用项在前，排查用的工具收进「高级」区域。 */
@Composable
fun SettingsScreen(
    prefs: RunPrefs,
    useRoot: Boolean,
    onUseRootChange: (Boolean) -> Unit,
    onReprobe: () -> Unit,
    modifier: Modifier = Modifier
) {
    var envText by remember { mutableStateOf(prefs.customEnv) }
    var dumpEnv by remember { mutableStateOf(prefs.dumpEnv) }
    var verbose by remember { mutableStateOf(prefs.verbose) }
    var globalAnswers by remember { mutableStateOf(prefs.autoAnswers) }
    var advanced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var netResult by remember { mutableStateOf<List<String>>(emptyList()) }
    var netRunning by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("默认用 root 执行", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = useRoot, onCheckedChange = onUseRootChange)
        }
        Text(
            "单个脚本可在脚本设置里单独覆盖这个开关。",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        Text("全局环境变量", style = MaterialTheme.typography.titleMedium)
        Text(
            "一行一个 KEY=VALUE，对所有脚本生效。脚本自己的设置优先。",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = envText,
            onValueChange = { envText = it; prefs.customEnv = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            placeholder = { Text("http_proxy=http://host:port", fontSize = 12.sp) }
        )

        HorizontalDivider()

        Text("全局自动应答", style = MaterialTheme.typography.titleMedium)
        Text(
            "脚本没有单独配置时使用。一行一个，按顺序回答每次提问。",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = globalAnswers,
            onValueChange = { globalAnswers = it; prefs.autoAnswers = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            placeholder = { Text("y", fontSize = 12.sp) }
        )

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("高级", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "收起" else "展开")
                Icon(
                    imageVector = if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
        }

        if (advanced) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("打印执行环境", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = dumpEnv, onCheckedChange = { dumpEnv = it; prefs.dumpEnv = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("打印实际命令", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = verbose, onCheckedChange = { verbose = it; prefs.verbose = it })
            }

            HorizontalDivider()

            Text("运行环境", style = MaterialTheme.typography.titleMedium)
            val probe = ShellEnv.cachedRoot()
            InfoLine("shell", ShellEnv.shellPath())
            InfoLine("root", if (probe?.ok == true) "可用 · ${probe.mode?.label}" else (probe?.detail ?: "未探测"))
            InfoLine("工具箱", ShellEnv.toolboxSummary())
            OutlinedButton(onClick = onReprobe) { Text("重新探测") }

            HorizontalDivider()

            Text("网络检测", style = MaterialTheme.typography.titleMedium)
            Text(
                "脚本卡在「检查更新中」后崩溃时，多半是它请求境外接口失败。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    scope.launch {
                        netRunning = true
                        netResult = NetCheck.run()
                        netRunning = false
                    }
                },
                enabled = !netRunning
            ) { Text(if (netRunning) "检测中…" else "开始检测") }
            if (netResult.isNotEmpty()) {
                Text(
                    text = netResult.joinToString("\n"),
                    style = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.35f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
