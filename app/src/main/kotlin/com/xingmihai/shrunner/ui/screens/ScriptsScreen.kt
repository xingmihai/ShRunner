package com.xingmihai.shrunner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingmihai.shrunner.data.ScriptEntry
import com.xingmihai.shrunner.data.formatTime
import com.xingmihai.shrunner.ui.ScriptListViewModel

@Composable
fun ScriptsScreen(
    viewModel: ScriptListViewModel,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onRun: (ScriptEntry) -> Unit,
    onDelete: (ScriptEntry) -> Unit,
    onFavorite: (ScriptEntry) -> Unit,
    onShortcut: (ScriptEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val scripts by viewModel.scripts.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("导入 .sh") }
            Button(onClick = onCreate, modifier = Modifier.weight(1f)) { Text("新建脚本") }
        }

        if (scripts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有脚本", style = MaterialTheme.typography.titleMedium)
                Text(
                    "点上方「导入 .sh」从手机里选择脚本，或新建一个",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = scripts, key = { it.id }) { entry ->
                    ScriptCard(
                        entry = entry,
                        onEdit = { onEdit(entry.id) },
                        onRun = { onRun(entry) },
                        onDelete = { onDelete(entry) },
                        onFavorite = { onFavorite(entry) },
                        onShortcut = { onShortcut(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptCard(
    entry: ScriptEntry,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onShortcut: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onFavorite) {
                    Icon(
                        imageVector = if (entry.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "收藏"
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "删除")
                }
            }
            Text(
                text = buildString {
                    append("更新于 ").append(formatTime(entry.updatedAt))
                    append(" · ").append(entry.sizeBytes / 1024 + 1).append(" KB")
                    if (entry.lastRunAt > 0L) {
                        append(" · 上次 ").append(formatTime(entry.lastRunAt))
                        entry.lastExitCode?.let { append(" (exit $it)") }
                    }
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onRun) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Text("运行", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.padding(start = 8.dp)) { Text("编辑") }
                IconButton(onClick = onShortcut) {
                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = "桌面快捷方式")
                }
            }
        }
    }
}
