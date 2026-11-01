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
import androidx.compose.ui.res.stringResource
import com.shrunner.app.R
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

    val genericError = stringResource(R.string.error_generic)
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it ?: genericError) } }
    fun say(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri) { name -> say(context.getString(R.string.imported, name)) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0), // the bottom bar + top bar already handle system insets
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (favoritesOnly) R.string.nav_favorites else R.string.nav_scripts)) },
                actions = {
                    if (!favoritesOnly) {
                        IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = stringResource(R.string.cd_import))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!favoritesOnly) {
                FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_new_script))
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
                                Icons.Outlined.StarBorder, stringResource(R.string.favorites_empty_title),
                                stringResource(R.string.favorites_empty_msg),
                            )
                        } else {
                            EmptyState(
                                Icons.Outlined.Description, stringResource(R.string.scripts_empty_title),
                                stringResource(R.string.scripts_empty_msg),
                            ) {
                                Button(onClick = { showCreate = true }) { Text(stringResource(R.string.btn_new_script)) }
                                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.btn_import)) }
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
                                            say(context.getString(R.string.pin_unsupported))
                                        }
                                    },
                                    onTile = {
                                        vm.setTileTarget(script.name)
                                        Shortcuts.requestAddTile(context)
                                        say(context.getString(R.string.tile_set, script.name))
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
            title = stringResource(R.string.dialog_new_title), initial = "", confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                vm.create(name, onOpen)
            },
        )
    }
    renaming?.let { script ->
        NameDialog(
            title = stringResource(R.string.dialog_rename_title), initial = script.name, confirmLabel = stringResource(R.string.menu_rename),
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
            title = { Text(stringResource(R.string.delete_title, script.name)) },
            text = { Text(stringResource(R.string.delete_msg)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; vm.delete(script.name) }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
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
                IconButton(onClick = onRun) { Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.cd_run, script.name)) }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (script.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(if (script.favorite) R.string.cd_unfavorite else R.string.cd_favorite),
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_edit)) }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_rename)) }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_pin)) }, onClick = { menu = false; onPin() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_tile)) }, onClick = { menu = false; onTile() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
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
                label = { Text(stringResource(R.string.label_file_name)) },
                supportingText = { Text(stringResource(R.string.hint_sh_auto)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
