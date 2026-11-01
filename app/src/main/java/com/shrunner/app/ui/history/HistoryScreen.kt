package com.shrunner.app.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrunner.app.data.HistoryEntry
import com.shrunner.app.data.RunStatus
import com.shrunner.app.ui.common.EmptyState
import com.shrunner.app.ui.common.ErrorState
import com.shrunner.app.ui.common.LoadingState
import com.shrunner.app.ui.common.UiState
import com.shrunner.app.ui.common.formatDate
import com.shrunner.app.ui.common.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    val s = state
                    if (s is UiState.Success && s.data.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear history")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> LoadingState()
                is UiState.Error -> ErrorState(s.message, vm::retry)
                is UiState.Success ->
                    if (s.data.isEmpty()) {
                        EmptyState(
                            Icons.Filled.History, "No runs yet",
                            "Every run, stop and declined warning is logged here.",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(s.data, key = { it.id }) { HistoryCard(it) }
                        }
                    }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("The audit log will be erased. Approved dangerous commands stay approved.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry) {
    val context = LocalContext.current
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val (label, color) = when (entry.status) {
        RunStatus.COMPLETED -> "Completed" to MaterialTheme.colorScheme.tertiary
        RunStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        RunStatus.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        RunStatus.DECLINED -> "Declined" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.scriptName, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(label, color = color, style = MaterialTheme.typography.labelLarge)
            }
            val meta = buildList {
                add(formatDate(context, entry.startedAt))
                if (entry.status != RunStatus.DECLINED) add(formatDuration(entry.durationMs))
                entry.exitCode?.let { add("exit $it") }
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.dangerLabels.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Flagged: ${entry.dangerLabels}", style = MaterialTheme.typography.bodySmall)
                }
            }
            AnimatedVisibility(expanded) {
                Text(
                    entry.outputTail.ifEmpty { "(no output captured)" },
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
