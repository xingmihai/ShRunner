package com.xingmihai.shrunner.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingmihai.shrunner.engine.ShellEngine
import com.xingmihai.shrunner.engine.ShellEnv
import com.xingmihai.shrunner.engine.Stream
import com.xingmihai.shrunner.ui.theme.TerminalBackground
import com.xingmihai.shrunner.ui.theme.TerminalForeground
import com.xingmihai.shrunner.util.AnsiParser
import com.xingmihai.shrunner.util.copyToClipboard
import kotlinx.coroutines.delay

private fun rootStatus(): String = ShellEnv.cachedRoot()?.let { probe ->
    if (probe.ok) "可用 · ${probe.mode?.label}" else "不可用 · ${probe.detail.take(28)}"
} ?: "未探测"

@Composable
fun ConsoleScreen(
    useRoot: Boolean,
    onUseRootChange: (Boolean) -> Unit,
    rootMode: ShellEnv.SuMode?,
    onRootModeChange: (ShellEnv.SuMode?) -> Unit,
    onReprobe: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lines by ShellEngine.lines.collectAsStateWithLifecycle()
    val state by ShellEngine.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    // 运行中每秒刷新一次耗时
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        val running = state as? ShellEngine.RunState.Running ?: return@LaunchedEffect
        while (true) {
            elapsedMs = System.currentTimeMillis() - running.startedAt
            delay(1000)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (state) {
                    is ShellEngine.RunState.Idle -> "空闲"
                    is ShellEngine.RunState.Running -> "运行中 · ${elapsedMs / 1000}s"
                    is ShellEngine.RunState.Finished -> "结束 · 退出码 ${(state as ShellEngine.RunState.Finished).exitCode} · 用时 ${(state as ShellEngine.RunState.Finished).durationMs} ms"
                    is ShellEngine.RunState.Failed -> "失败 · ${(state as ShellEngine.RunState.Failed).message}"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = useRoot, onCheckedChange = onUseRootChange)
            Text("root")
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(if (rootMode == null) "自动" else rootMode.label)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("自动探测") },
                        onClick = { onRootModeChange(null); expanded = false }
                    )
                    ShellEnv.SuMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = { onRootModeChange(mode); expanded = false }
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rootStatus(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReprobe) { Text("重新探测") }
        }

        if (state is ShellEngine.RunState.Running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStop) { Text("停止") }
            OutlinedButton(onClick = onClear) { Text("清空") }
            OutlinedButton(onClick = {
                val text = lines.joinToString("\n") { it.text }
                copyToClipboard(context, "ShRunner 输出", text)
                Toast.makeText(context, "已复制输出", Toast.LENGTH_SHORT).show()
            }) { Text("复制") }
        }

        Text(
            text = "shell: ${ShellEnv.shellPath()} · root: ${rootStatus()} · 工具箱: ${ShellEnv.toolboxSummary()}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TerminalBackground)
                .padding(8.dp)
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = "还没有输出，回到「脚本」页运行一个脚本试试",
                    color = Color(0xFF8A8F98),
                    fontSize = 12.sp
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(items = lines, key = { it.seq }) { line ->
                        val annotated = remember(line.text) { AnsiParser.parse(line.text) }
                        Text(
                            text = annotated,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = when (line.stream) {
                                    Stream.SYSTEM -> Color(0xFF8A8F98)
                                    Stream.STDERR -> Color(0xFFEF5350)
                                    Stream.STDOUT -> TerminalForeground
                                }
                            ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
