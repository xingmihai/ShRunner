package com.xingmihai.shrunner.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xingmihai.shrunner.engine.FileBrowser
import kotlinx.coroutines.launch

/**
 * 本地文件浏览：直接翻到 .sh 就地执行，不必先导入。
 * root 下能看到全部路径，非 root 只能看到应用可访问的位置。
 */
@Composable
fun FileBrowserScreen(
    onRun: (String) -> Unit,
    onImport: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(FileBrowser.roots().first()) }
    var entries by remember { mutableStateOf<List<FileBrowser.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<FileBrowser.Entry?>(null) }

    fun load(dir: String) {
        scope.launch {
            loading = true
            error = null
            val result = runCatching { FileBrowser.list(dir) }.getOrElse { emptyList() }
            entries = result
            error = if (result.isEmpty()) "该目录为空或无权访问" else null
            loading = false
        }
    }

    LaunchedEffect(path) { load(path) }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            OutlinedButton(
                onClick = {
                    val parent = path.trimEnd('/').substringBeforeLast('/')
                    if (parent.isNotBlank() && parent != path) path = parent
                }
            ) { Text("上级") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FileBrowser.roots().forEach { root ->
                AssistChip(
                    onClick = { path = root },
                    label = { Text(root.substringAfterLast('/').ifBlank { "/" }, style = MaterialTheme.typography.bodySmall) }
                )
            }
        }

        when {
            loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            error != null -> Text(
                text = error ?: "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(items = entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDir) path = entry.path else pending = entry
                        },
                        onRun = { pending = entry }
                    )
                }
            }
        }
    }

    pending?.let { entry ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(entry.name) },
            text = { Text("要直接执行这个脚本，还是先导入到脚本列表？") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onRun(entry.path)
                }) { Text("直接执行") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    onImport(entry.path)
                }) { Text("导入") }
            }
        )
    }
}

@Composable
private fun FileRow(
    entry: FileBrowser.Entry,
    onClick: () -> Unit,
    onRun: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (!entry.isDir && entry.name.endsWith(".sh")) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            if (!entry.isDir) {
                Text(
                    text = "${entry.size / 1024} KB",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (!entry.isDir && entry.name.endsWith(".sh")) {
            IconButton(onClick = onRun) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "执行")
            }
        }
    }
}
