package com.xingmihai.shrunner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingmihai.shrunner.data.ScriptConfig

/** 单个脚本的运行设置：自动应答、工作目录、环境变量、root 开关。 */
@Composable
fun ScriptSettingsDialog(
    title: String,
    initial: ScriptConfig.Config,
    onDismiss: () -> Unit,
    onConfirm: (ScriptConfig.Config) -> Unit
) {
    var answers by remember { mutableStateOf(initial.autoAnswers) }
    var workDir by remember { mutableStateOf(initial.workDir) }
    var env by remember { mutableStateOf(initial.env) }
    var root by remember { mutableStateOf(initial.rootOverride) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("自动应答", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "一行一个，识别到提问时按顺序自动填入。只对这个脚本生效。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = answers,
                    onValueChange = { answers = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    placeholder = { Text("1", fontSize = 12.sp) }
                )

                HorizontalDivider()

                Text("工作目录", style = MaterialTheme.typography.bodyMedium)
                Text("留空 = 脚本所在目录。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = workDir,
                    onValueChange = { workDir = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    placeholder = { Text("留空 = 脚本所在目录", fontSize = 12.sp) }
                )

                HorizontalDivider()

                Text("该脚本的环境变量", style = MaterialTheme.typography.bodyMedium)
                Text("一行一个 KEY=VALUE，覆盖全局设置。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = env,
                    onValueChange = { env = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )

                HorizontalDivider()

                Text("root 开关", style = MaterialTheme.typography.bodyMedium)
                val options = listOf("" to "跟随全局", "on" to "开", "off" to "关")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = root == value,
                            onClick = { root = value },
                            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = options.size
                            )
                        ) { Text(label, fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    ScriptConfig.Config(
                        autoAnswers = answers,
                        workDir = workDir,
                        env = env,
                        rootOverride = root
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
