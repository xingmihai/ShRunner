package com.shrunner.app.ui.scripts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrunner.app.R
import com.shrunner.app.data.Script
import com.shrunner.app.system.Shortcuts
import com.shrunner.app.ui.common.EmptyState
import com.shrunner.app.ui.common.ErrorState
import com.shrunner.app.ui.common.LoadingState
import com.shrunner.app.ui.common.UiState
import com.shrunner.app.ui.common.formatDate
import com.shrunner.app.ui.common.formatSize
import com.shrunner.app.ui.glass.GlassAlert
import com.shrunner.app.ui.glass.GlassSheet
import com.shrunner.app.ui.glass.alertButton
import com.shrunner.app.ui.glass.bottomBarSpace
import com.shrunner.app.ui.glass.glassBody
import com.shrunner.app.ui.glass.glassBodyEmphasized
import com.shrunner.app.ui.glass.glassSecondaryColor
import io.github.neoruaa.glasmio.glass.GlassEmphasis
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import kotlinx.coroutines.launch

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
    val bottom = bottomBarSpace()
    val title = stringResource(if (favoritesOnly) R.string.nav_favorites else R.string.nav_scripts)

    val genericError = stringResource(R.string.error_generic)
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it ?: genericError) } }
    fun say(text: String) = scope.launch { snackbar.showSnackbar(text) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri) { name -> say(context.getString(R.string.imported, name)) }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(s.message, vm::retry)
            is UiState.Success -> {
                val items = if (favoritesOnly) s.data.filter { it.favorite } else s.data
                GlasmioPinnedListPage(title = title, bottomPadding = bottom) {
                    if (!favoritesOnly) {
                        item(key = "actions") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                GlasmioButton(
                                    onClick = { showCreate = true },
                                    modifier = Modifier.weight(1f),
                                    emphasis = GlassEmphasis.Prominent,
                                ) { Text(stringResource(R.string.btn_new_script)) }
                                GlasmioButton(
                                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.btn_import)) }
                            }
                        }
                    }
                    if (items.isEmpty()) {
                        item(key = "empty") {
                            if (favoritesOnly) {
                                EmptyState(
                                    Icons.Outlined.StarBorder,
                                    stringResource(R.string.favorites_empty_title),
                                    stringResource(R.string.favorites_empty_msg),
                                    Modifier.fillMaxWidth(),
                                )
                            } else {
                                EmptyState(
                                    Icons.Outlined.Description,
                                    stringResource(R.string.scripts_empty_title),
                                    stringResource(R.string.scripts_empty_msg),
                                    Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        item(key = "list") {
                            GlasmioGroupedList {
                                items.forEachIndexed { index, script ->
                                    ScriptRow(
                                        script = script,
                                        showTopSeparator = index > 0,
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
    }

    NameSheet(
        visible = showCreate,
        title = stringResource(R.string.dialog_new_title),
        initial = "",
        confirmLabel = stringResource(R.string.action_create),
        onDismiss = { showCreate = false },
        onConfirm = { name ->
            showCreate = false
            vm.create(name, onOpen)
        },
    )
    NameSheet(
        visible = renaming != null,
        title = stringResource(R.string.dialog_rename_title),
        initial = renaming?.name.orEmpty(),
        confirmLabel = stringResource(R.string.menu_rename),
        onDismiss = { renaming = null },
        onConfirm = { name ->
            renaming?.let { vm.rename(it.name, name) }
            renaming = null
        },
    )
    val pendingDelete = deleting
    GlassAlert(
        visible = pendingDelete != null,
        title = stringResource(R.string.delete_title, pendingDelete?.name.orEmpty()),
        message = stringResource(R.string.delete_msg),
        buttons = listOf(
            alertButton(stringResource(R.string.action_cancel), { deleting = null }, cancel = true),
            alertButton(stringResource(R.string.action_delete), {
                pendingDelete?.let { vm.delete(it.name) }
                deleting = null
            }),
        ),
        onDismiss = { deleting = null },
    )
}

@Composable
private fun ScriptRow(
    script: Script,
    showTopSeparator: Boolean,
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
    GlasmioListRow(
        (if (script.favorite) "★ " else "") + script.name,
        subtitle = "${formatSize(script.sizeBytes)} · ${formatDate(context, script.lastModified)}",
        onClick = onOpen,
        showTopSeparator = showTopSeparator,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GlasmioIconButton(onClick = onRun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.cd_run, script.name))
                }
                GlasmioMenu(
                    expanded = menu,
                    onExpandedChange = { menu = it },
                    itemCount = 6,
                    anchor = { open ->
                        GlasmioIconButton(onClick = open) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more))
                        }
                    },
                ) { backdrop, close ->
                    GlasmioMenuItem(stringResource(R.string.menu_edit), { onOpen(); close() }, systemName = null, backdrop = backdrop)
                    GlasmioMenuItem(stringResource(R.string.menu_rename), { onRename(); close() }, systemName = null, backdrop = backdrop)
                    GlasmioMenuItem(
                        stringResource(if (script.favorite) R.string.cd_unfavorite else R.string.cd_favorite),
                        { onToggleFavorite(); close() }, systemName = null, backdrop = backdrop,
                    )
                    GlasmioMenuItem(stringResource(R.string.menu_pin), { onPin(); close() }, systemName = null, backdrop = backdrop)
                    GlasmioMenuItem(stringResource(R.string.menu_tile), { onTile(); close() }, systemName = null, backdrop = backdrop)
                    GlasmioMenuItem(
                        stringResource(R.string.action_delete), { onDelete(); close() },
                        systemName = null, destructive = true, backdrop = backdrop,
                    )
                }
            }
        },
    )
}

@Composable
private fun NameSheet(
    visible: Boolean,
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        var text by remember(initial, visible) { mutableStateOf(initial) }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = glassBodyEmphasized())
            GlasmioTextField(text, { text = it }, placeholder = stringResource(R.string.label_file_name))
            Text(
                stringResource(R.string.hint_sh_auto),
                style = glassBody().copy(fontSize = 13.sp),
                color = glassSecondaryColor(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlasmioButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                GlasmioButton(
                    onClick = { if (text.isNotBlank()) onConfirm(text) },
                    modifier = Modifier.weight(1f),
                    emphasis = GlassEmphasis.Prominent,
                ) { Text(confirmLabel) }
            }
        }
    }
}
