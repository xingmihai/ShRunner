package com.xingmihai.shrunner.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingmihai.shrunner.engine.ShellEngine
import com.xingmihai.shrunner.engine.Stream
import com.xingmihai.shrunner.ui.theme.TerminalBackground
import com.xingmihai.shrunner.ui.theme.TerminalForeground
import com.xingmihai.shrunner.util.AnsiParser
import com.xingmihai.shrunner.util.copyToClipboard
import kotlinx.coroutines.delay

@Composable
fun ConsoleScreen(
    useRoot: Boolean,
    onUseRootChange: (Boolean) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lines by ShellEngine.lines.collectAsStateWithLifecycle()
    val state by ShellEngine.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

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
                    is ShellEngine.RunState.Finished ->
                        "结束 · 退出码 ${(state as ShellEngine.RunState.Finished).exitCode} · ${(state as ShellEngine.RunState.Finished).durationMs} ms"
                    is ShellEngine.RunState.Failed -> "失败 · ${(state as ShellEngine.RunState.Failed).message}"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = useRoot, onCheckedChange = onUseRootChange)
            Text("root", style = MaterialTheme.typography.bodySmall)
        }

        if (state is ShellEngine.RunState.Running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStop) { Text("停止") }
            OutlinedButton(onClick = onClear) { Text("清空") }
            OutlinedButton(onClick = {
                copyToClipboard(context, "ShRunner 输出", lines.joinToString("\n") { it.text })
                Toast.makeText(context, "已复制输出", Toast.LENGTH_SHORT).show()
            }) { Text("复制") }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(top = 8.dp)
                .background(TerminalBackground)
                .padding(8.dp)
        ) {
            if (lines.isEmpty()) {
                Text("还没有输出", color = Color(0xFF8A8F98), fontSize = 12.sp)
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("脚本在等输入？在这里输入后回车") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotBlank() && ShellEngine.sendInput(input)) {
                            ShellEngine.appendEcho(input)
                            input = ""
                        }
                    }
                )
            )
            Button(
                onClick = {
                    if (input.isNotBlank() && ShellEngine.sendInput(input)) {
                        ShellEngine.appendEcho(input)
                        input = ""
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("发送") }
        }
    }
}
