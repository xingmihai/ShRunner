package com.shrunner.app.ui.scripts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrunner.app.data.Script
import com.shrunner.app.system.Shortcuts
import com.shrunner.app.ui.common.EmptyState
import com.shrunner.app.ui.common.ErrorState
import com.shrunner.app.ui.common.LoadingState
import com.shrunner.app.ui.common.UiState
import com.shrunner.app.ui.common.formatDate
import com.shrunner.app.ui.common.formatSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    favoritesOnly: Boolean,
    onOpen: (String) -> Unit,
    onRun: (String) -> Unit,
    vm: ScriptsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Script?>(null) }
    var deleting by remember { mutableStateOf<Script?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    fun say(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri) { name -> say("Imported $name") }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0), // the bottom bar + top bar already handle system insets
        topBar = {
            TopAppBar(
                title = { Text(if (favoritesOnly) "Favorites" else "Scripts") },
                actions = {
                    if (!favoritesOnly) {
                        IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = "Import script")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!favoritesOnly) {
                FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New script")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> LoadingState()
                is UiState.Error -> ErrorState(s.message, vm::retry)
                is UiState.Success -> {
                    val items = if (favoritesOnly) s.data.filter { it.favorite } else s.data
                    if (items.isEmpty()) {
                        if (favoritesOnly) {
                            EmptyState(
                                Icons.Outlined.StarBorder, "No favorites yet",
                                "Star a script and it shows up here and when you long-press the app icon.",
                            )
                        } else {
                            EmptyState(
                                Icons.Outlined.Description, "No scripts yet",
                                "Create a new script or import an existing .sh file.",
                            ) {
                                Button(onClick = { showCreate = true }) { Text("New script") }
                                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import .sh file") }
                            }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                            items(items, key = { it.name }) { script ->
                                ScriptRow(
                                    script = script,
                                    onOpen = { onOpen(script.name) },
                                    onRun = { onRun(script.name) },
                                    onToggleFavorite = { vm.toggleFavorite(script) },
                                    onRename = { renaming = script },
                                    onDelete = { deleting = script },
                                    onPin = {
                                        if (!Shortcuts.requestPin(context, script.name)) {
                                            say("This launcher doesn't support pinned shortcuts.")
                                        }
                                    },
                                    onTile = {
                                        vm.setTileTarget(script.name)
                                        Shortcuts.requestAddTile(context)
                                        say("Quick Settings tile will open ${script.name}. Add the ShRunner tile if you haven't yet.")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "New script", initial = "", confirmLabel = "Create",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                vm.create(name, onOpen)
            },
        )
    }
    renaming?.let { script ->
        NameDialog(
            title = "Rename script", initial = script.name, confirmLabel = "Rename",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                vm.rename(script.name, name)
            },
        )
    }
    deleting?.let { script ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${script.name}?") },
            text = { Text("This can't be undone. Past runs stay in History.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; vm.delete(script.name) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScriptRow(
    script: Script,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onTile: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
        headlineContent = { Text(script.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${formatSize(script.sizeBytes)} · ${formatDate(context, script.lastModified)}") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRun) { Icon(Icons.Filled.PlayArrow, contentDescription = "Run ${script.name}") }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (script.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (script.favorite) "Remove from favorites" else "Add to favorites",
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Add to home screen") }, onClick = { menu = false; onPin() })
                        DropdownMenuItem(text = { Text("Set as Quick Settings tile") }, onClick = { menu = false; onTile() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("File name") },
                supportingText = { Text(".sh is added automatically") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
