package com.shrunner.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.shrunner.app.R
import com.shrunner.app.ui.common.ErrorState
import com.shrunner.app.ui.common.LoadingState
import com.shrunner.app.ui.common.UiState
import com.shrunner.app.ui.glass.GlassAlert
import com.shrunner.app.ui.glass.GlassTopBar
import com.shrunner.app.ui.glass.alertButton
import com.shrunner.app.ui.glass.glassContentColor
import io.github.neoruaa.glasmio.ui.GlasmioIconButton

@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onRun: (String) -> Unit,
    vm: EditorViewModel = hiltViewModel(),
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val genericError = stringResource(R.string.error_generic)
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it ?: genericError) } }

    BackHandler(enabled = vm.dirty) { confirmDiscard = true }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = if (vm.dirty) "${vm.name} •" else vm.name,
                onBack = { if (vm.dirty) confirmDiscard = true else onBack() },
            ) {
                if (vm.dirty) {
                    GlasmioIconButton(onClick = { vm.save() }) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.action_save))
                    }
                }
                if (vm.loadState is UiState.Success) {
                    GlasmioIconButton(onClick = { if (vm.dirty) vm.save { onRun(vm.name) } else onRun(vm.name) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.action_run))
                    }
                }
            }
            when (val s = vm.loadState) {
                UiState.Loading -> LoadingState(Modifier.weight(1f).fillMaxWidth())
                is UiState.Error -> ErrorState(s.message, vm::load, Modifier.weight(1f).fillMaxWidth())
                is UiState.Success -> {
                    // Whole area is tappable/scrollable: min height = viewport height.
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().imePadding()) {
                        val minHeight = maxHeight
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            BasicTextField(
                                value = vm.text,
                                onValueChange = vm::onTextChange,
                                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = minHeight - 32.dp),
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = glassContentColor(),
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    GlassAlert(
        visible = confirmDiscard,
        title = stringResource(R.string.discard_title),
        message = stringResource(R.string.discard_msg, vm.name),
        buttons = listOf(
            alertButton(stringResource(R.string.action_cancel), { confirmDiscard = false }, cancel = true),
            alertButton(stringResource(R.string.action_discard), { confirmDiscard = false; onBack() }),
            alertButton(stringResource(R.string.action_save), { confirmDiscard = false; vm.save { onBack() } }),
        ),
        onDismiss = { confirmDiscard = false },
    )
}
