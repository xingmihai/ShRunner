package com.shrunner.app.ui.run

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.shrunner.app.R
import com.shrunner.app.ui.common.dangerLabelRes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shrunner.app.data.RunStatus
import com.shrunner.app.ui.common.ErrorState
import com.shrunner.app.ui.common.LoadingState
import kotlinx.coroutines.flow.filter

private val ConsoleBg = Color(0xFF0E1116)
private val ConsoleFg = Color(0xFFD7DEE7)
private val ConsoleDim = Color(0xFF7D8896)
private val ConsoleErr = Color(0xFFFF8A80)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(onBack: () -> Unit, vm: RunViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val phase = vm.phase
    val running = phase is RunPhase.Running
    var confirmLeave by remember { mutableStateOf(false) }

    BackHandler(enabled = running) { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (running) confirmLeave = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (vm.lines.isNotEmpty()) {
                        IconButton(onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("ShRunner output", vm.outputText()))
                        }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_output)) }
                    }
                },
            )
        },
    ) { padding ->
        when (phase) {
            RunPhase.Loading -> LoadingState(Modifier.padding(padding).fillMaxSize())
            is RunPhase.Error -> ErrorState(phase.message, vm::prepare, Modifier.padding(padding).fillMaxSize())
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                StatusRow(phase)
                Console(
                    lines = vm.lines,
                    placeholder = stringResource(if (phase is RunPhase.Ready) R.string.run_ready_placeholder else R.string.run_no_output),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (running) {
                        Button(
                            onClick = vm::stop,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                            Text("  ${stringResource(R.string.action_stop)}")
                        }
                    } else {
                        Button(onClick = vm::requestRun, modifier = Modifier.fillMaxWidth()) {
                            val again = phase is RunPhase.Finished
                            Icon(if (again) Icons.Filled.Replay else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Text("  ${stringResource(if (again) R.string.action_run_again else R.string.action_run)}")
                        }
                    }
                }
            }
        }
    }

    vm.pendingConfirm?.let { hits ->
        AlertDialog(
            onDismissRequest = vm::decline,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.danger_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.danger_msg),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LazyColumn(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(ConsoleBg),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(hits) { hit ->
                            Column {
                                Text(stringResource(dangerLabelRes(hit.ruleId)), color = ConsoleErr, fontSize = 12.sp)
                                Text(
                                    "${hit.lineNumber}: ${hit.line}",
                                    color = ConsoleFg, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirm) { Text(stringResource(R.string.action_run_anyway)) } },
            dismissButton = { TextButton(onClick = vm::decline) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.leave_title)) },
            text = { Text(stringResource(R.string.leave_msg)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.stop(); onBack() }) { Text(stringResource(R.string.action_stop_leave)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_keep_running)) } },
        )
    }
}

@Composable
private fun StatusRow(phase: RunPhase) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                RunPhase.Running -> Text(stringResource(R.string.status_running), style = MaterialTheme.typography.labelLarge)
                is RunPhase.Finished -> {
                    val ok = phase.status == RunStatus.COMPLETED
                    Icon(
                        if (ok) Icons.Filled.CheckCircle else Icons.Outlined.ErrorOutline, null,
                        Modifier.size(18.dp),
                        tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                    val label = when (phase.status) {
                        RunStatus.COMPLETED -> stringResource(R.string.status_completed)
                        RunStatus.FAILED -> stringResource(R.string.status_failed)
                        RunStatus.STOPPED -> stringResource(R.string.status_stopped)
                        RunStatus.DECLINED -> stringResource(R.string.status_declined)
                    }
                    Text(
                        if (phase.exitCode != null) stringResource(R.string.status_with_exit, label, phase.exitCode) else label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                else -> Text(stringResource(R.string.status_ready), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (phase == RunPhase.Running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun Console(lines: List<OutLine>, placeholder: String, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Follow new output only while the user is at the bottom; scrolling up pauses following.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { follow = !listState.canScrollForward }
    }
    LaunchedEffect(lines.size) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Box(modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(ConsoleBg)) {
        if (lines.isEmpty()) {
            Text(
                placeholder, color = ConsoleDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
                items(lines) { line ->
                    Text(
                        line.text.ifEmpty { " " },
                        color = if (line.isError) ConsoleErr else ConsoleFg,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
