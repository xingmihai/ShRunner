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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shrunner.app.R
import com.shrunner.app.data.DangerHit
import com.shrunner.app.data.RunStatus
import com.shrunner.app.ui.common.ErrorState
import com.shrunner.app.ui.common.LoadingState
import com.shrunner.app.ui.common.dangerLabelRes
import com.shrunner.app.ui.glass.GlassAlert
import com.shrunner.app.ui.glass.GlassSheet
import com.shrunner.app.ui.glass.GlassTopBar
import com.shrunner.app.ui.glass.alertButton
import com.shrunner.app.ui.glass.glassBody
import com.shrunner.app.ui.glass.glassBodyEmphasized
import com.shrunner.app.ui.glass.glassSecondaryColor
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import kotlinx.coroutines.flow.filter

private val ConsoleBg = Color(0xFF0E1116)
private val ConsoleFg = Color(0xFFD7DEE7)
private val ConsoleDim = Color(0xFF7D8896)
private val ConsoleErr = Color(0xFFFF8A80)

@Composable
fun RunScreen(onBack: () -> Unit, vm: RunViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val phase = vm.phase
    val running = phase is RunPhase.Running
    var confirmLeave by remember { mutableStateOf(false) }

    BackHandler(enabled = running) { confirmLeave = true }

    Column(Modifier.fillMaxSize()) {
        GlassTopBar(title = vm.name, onBack = { if (running) confirmLeave = true else onBack() }) {
            if (vm.lines.isNotEmpty()) {
                GlasmioIconButton(onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("ShRunner output", vm.outputText()))
                }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.cd_copy_output)) }
            }
        }
        when (phase) {
            RunPhase.Loading -> LoadingState(Modifier.weight(1f).fillMaxWidth())
            is RunPhase.Error -> ErrorState(phase.message, vm::prepare, Modifier.weight(1f).fillMaxWidth())
            else -> {
                StatusRow(phase)
                Console(
                    lines = vm.lines,
                    placeholder = stringResource(
                        if (phase is RunPhase.Ready) R.string.run_ready_placeholder else R.string.run_no_output,
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    if (running) {
                        GlasmioButton(
                            onClick = vm::stop,
                            modifier = Modifier.fillMaxWidth(),
                            size = GlasmioButtonSize.Large,
                        ) { Text(stringResource(R.string.action_stop)) }
                    } else {
                        GlasmioButton(
                            onClick = vm::requestRun,
                            modifier = Modifier.fillMaxWidth(),
                            emphasis = GlassEmphasis.Prominent,
                            size = GlasmioButtonSize.Large,
                        ) {
                            Text(stringResource(if (phase is RunPhase.Finished) R.string.action_run_again else R.string.action_run))
                        }
                    }
                }
            }
        }
    }

    // Keep the last list so the sheet's content doesn't vanish during its dismiss animation.
    var shownHits by remember { mutableStateOf<List<DangerHit>>(emptyList()) }
    vm.pendingConfirm?.let { shownHits = it }
    GlassSheet(visible = vm.pendingConfirm != null, onDismiss = vm::decline) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.danger_title), style = glassBodyEmphasized())
            Text(stringResource(R.string.danger_msg), style = glassBody(), color = glassSecondaryColor())
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(ConsoleBg)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shownHits.forEach { hit ->
                    Column {
                        Text(stringResource(dangerLabelRes(hit.ruleId)), color = ConsoleErr, fontSize = 12.sp)
                        Text(
                            "${hit.lineNumber}: ${hit.line}",
                            color = ConsoleFg, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlasmioButton(onClick = vm::decline, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                GlasmioButton(
                    onClick = vm::confirm,
                    modifier = Modifier.weight(1f),
                    emphasis = GlassEmphasis.Prominent,
                ) { Text(stringResource(R.string.action_run_anyway)) }
            }
        }
    }

    GlassAlert(
        visible = confirmLeave,
        title = stringResource(R.string.leave_title),
        message = stringResource(R.string.leave_msg),
        buttons = listOf(
            alertButton(stringResource(R.string.action_keep_running), { confirmLeave = false }, cancel = true),
            alertButton(stringResource(R.string.action_stop_leave), { confirmLeave = false; vm.stop(); onBack() }),
        ),
        onDismiss = { confirmLeave = false },
    )
}

@Composable
private fun StatusRow(phase: RunPhase) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                RunPhase.Running -> Text(stringResource(R.string.status_running), style = glassBodyEmphasized())
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
                        style = glassBodyEmphasized(),
                    )
                }
                else -> Text(stringResource(R.string.status_ready), style = glassBody(), color = glassSecondaryColor())
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
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { follow = !listState.canScrollForward }
    }
    LaunchedEffect(lines.size) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Box(modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(ConsoleBg)) {
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
