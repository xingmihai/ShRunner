package com.shrunner.app.ui.history

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrunner.app.R
import com.shrunner.app.data.HistoryEntry
import com.shrunner.app.data.RunStatus
import com.shrunner.app.ui.common.EmptyState
import com.shrunner.app.ui.common.ErrorState
import com.shrunner.app.ui.common.LoadingState
import com.shrunner.app.ui.common.UiState
import com.shrunner.app.ui.common.dangerLabelRes
import com.shrunner.app.ui.common.formatDate
import com.shrunner.app.ui.common.formatDuration
import com.shrunner.app.ui.glass.GlassAlert
import com.shrunner.app.ui.glass.GlassSheet
import com.shrunner.app.ui.glass.alertButton
import com.shrunner.app.ui.glass.bottomBarSpace
import com.shrunner.app.ui.glass.glassBody
import com.shrunner.app.ui.glass.glassBodyEmphasized
import com.shrunner.app.ui.glass.glassSecondaryColor
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow

@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bottom = bottomBarSpace()
    var confirmClear by remember { mutableStateOf(false) }
    // `shown` outlives `sheetVisible` so the sheet keeps its content while it animates out.
    var shown by remember { mutableStateOf<HistoryEntry?>(null) }
    var sheetVisible by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(s.message, vm::retry)
            is UiState.Success -> GlasmioPinnedListPage(title = stringResource(R.string.nav_history), bottomPadding = bottom) {
                if (s.data.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            Icons.Filled.History,
                            stringResource(R.string.history_empty_title),
                            stringResource(R.string.history_empty_msg),
                            Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    item(key = "clear") {
                        GlasmioButton(
                            onClick = { confirmClear = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(stringResource(R.string.cd_clear_history)) }
                    }
                    item(key = "list") {
                        GlasmioGroupedList {
                            s.data.forEachIndexed { index, entry ->
                                GlasmioListRow(
                                    entry.scriptName,
                                    subtitle = entryMeta(context, entry) + if (entry.dangerRules.isNotEmpty()) " · ⚠" else "",
                                    detail = statusLabel(entry.status),
                                    onClick = { shown = entry; sheetVisible = true },
                                    showTopSeparator = index > 0,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    GlassSheet(visible = sheetVisible, onDismiss = { sheetVisible = false }) {
        shown?.let { entry ->
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(entry.scriptName, style = glassBodyEmphasized())
                Text(
                    statusLabel(entry.status) + " · " + entryMeta(context, entry),
                    style = glassBody(), color = glassSecondaryColor(),
                )
                if (entry.dangerRules.isNotEmpty()) {
                    val flagged = entry.dangerRules.split(",").filter { it.isNotEmpty() }
                        .joinToString("; ") { context.getString(dangerLabelRes(it)) }
                    Text(stringResource(R.string.flagged, flagged), style = glassBody())
                }
                Text(
                    entry.outputTail.ifEmpty { stringResource(R.string.no_output_captured) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1116)).padding(12.dp),
                    color = Color(0xFFD7DEE7), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }

    GlassAlert(
        visible = confirmClear,
        title = stringResource(R.string.clear_title),
        message = stringResource(R.string.clear_msg),
        buttons = listOf(
            alertButton(stringResource(R.string.action_cancel), { confirmClear = false }, cancel = true),
            alertButton(stringResource(R.string.action_clear), { confirmClear = false; vm.clear() }),
        ),
        onDismiss = { confirmClear = false },
    )
}

private fun entryMeta(context: Context, entry: HistoryEntry): String = buildList {
    add(formatDate(context, entry.startedAt))
    if (entry.status != RunStatus.DECLINED) add(formatDuration(entry.durationMs))
    entry.exitCode?.let { add(context.getString(R.string.history_exit, it)) }
}.joinToString(" · ")

@Composable
private fun statusLabel(status: RunStatus): String = stringResource(
    when (status) {
        RunStatus.COMPLETED -> R.string.status_completed
        RunStatus.FAILED -> R.string.status_failed
        RunStatus.STOPPED -> R.string.status_stopped
        RunStatus.DECLINED -> R.string.status_declined
    },
)
