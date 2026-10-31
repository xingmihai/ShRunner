package com.xingmihai.shrunner.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingmihai.shrunner.data.ScriptEntry
import com.xingmihai.shrunner.data.ScriptRepository
import com.xingmihai.shrunner.engine.RunRequest
import com.xingmihai.shrunner.engine.RunnerService
import com.xingmihai.shrunner.engine.ShellEngine
import com.xingmihai.shrunner.ui.screens.ConsoleScreen
import com.xingmihai.shrunner.ui.screens.EditorScreen
import com.xingmihai.shrunner.ui.screens.ScriptsScreen
import com.xingmihai.shrunner.ui.theme.ShRunnerTheme
import com.xingmihai.shrunner.util.DangerScan
import com.xingmihai.shrunner.util.displayNameOf

enum class ShTab(val label: String) { SCRIPTS("脚本"), EDITOR("编辑"), CONSOLE("输出") }

@Composable
fun ShRunnerRoot(initialScriptId: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val repo = remember { ScriptRepository.get(app) }
    val listVm: ScriptListViewModel = viewModel()
    val editorVm: EditorViewModel = viewModel()

    var tab by remember { mutableStateOf(ShTab.SCRIPTS) }
    var editingId by remember { mutableStateOf(initialScriptId) }
    var pendingEntry by remember { mutableStateOf<ScriptEntry?>(null) }
    var risks by remember { mutableStateOf<List<String>>(emptyList()) }
    var useRoot by remember { mutableStateOf(false) }
    var rootMode by remember { mutableStateOf<com.xingmihai.shrunner.engine.ShellEnv.SuMode?>(null) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayNameOf(context, uri)
            listVm.import(uri, name)
            Toast.makeText(context, "已导入：$name", Toast.LENGTH_SHORT).show()
        }
    }

    fun startRun(entry: ScriptEntry) {
        RunnerService.start(context)
        ShellEngine.run(
            RunRequest(
                scriptId = entry.id,
                scriptName = entry.name,
                scriptPath = entry.file.absolutePath,
                workDir = repo.directory().absolutePath,
                useRoot = useRoot
            )
        )
        tab = ShTab.CONSOLE
    }

    fun requestRun(entry: ScriptEntry) {
        val hits = DangerScan.scan(repo.readText(entry.id))
        if (hits.isEmpty()) {
            startRun(entry)
        } else {
            pendingEntry = entry
            risks = hits
        }
    }

    // 记录每次运行的退出码，用于列表展示
    LaunchedEffect(Unit) {
        ShellEngine.state.collect { state ->
            if (state is ShellEngine.RunState.Finished) {
                repo.recordRun(state.scriptName, state.exitCode)
                listVm.refresh()
            }
        }
    }

    // 桌面快捷方式 / 外部 Intent 直接运行
    LaunchedEffect(initialScriptId) {
        if (!initialScriptId.isNullOrBlank()) {
            repo.find(initialScriptId)?.let(::startRun)
        }
    }

    if (risks.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { risks = emptyList(); pendingEntry = null },
            title = { Text("确认执行？") },
            text = { Text("脚本中包含高风险操作：\n\n" + risks.joinToString("\n") { "· $it" }) },
            confirmButton = {
                TextButton(onClick = {
                    val entry = pendingEntry
                    risks = emptyList()
                    pendingEntry = null
                    if (entry != null) startRun(entry)
                }) { Text("仍然执行") }
            },
            dismissButton = {
                TextButton(onClick = { risks = emptyList(); pendingEntry = null }) { Text("取消") }
            }
        )
    }

    ShRunnerTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    ShTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                Icon(
                                    imageVector = when (item) {
                                        ShTab.SCRIPTS -> Icons.Default.Description
                                        ShTab.EDITOR -> Icons.Default.Edit
                                        ShTab.CONSOLE -> Icons.Default.Code
                                    },
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    ShTab.SCRIPTS -> ScriptsScreen(
                        viewModel = listVm,
                        onImport = { importLauncher.launch(arrayOf("text/x-sh", "application/x-sh", "text/plain", "*/*")) },
                        onCreate = {
                            val id = listVm.create("script_${System.currentTimeMillis() / 1000}")
                            editingId = id
                            tab = ShTab.EDITOR
                        },
                        onEdit = { id -> editingId = id; tab = ShTab.EDITOR },
                        onRun = { entry -> requestRun(entry) },
                        onDelete = { entry -> listVm.delete(entry.id) },
                        onFavorite = { entry -> listVm.toggleFavorite(entry.id) },
                        onShortcut = { entry ->
                            com.xingmihai.shrunner.util.requestScriptShortcut(context, entry.id, entry.name)
                        }
                    )
                    ShTab.EDITOR -> EditorScreen(
                        viewModel = editorVm,
                        scriptId = editingId,
                        onSaved = { id -> editingId = id; listVm.refresh() },
                        onRun = { id -> repo.find(id)?.let { requestRun(it) } }
                    )
                    ShTab.CONSOLE -> ConsoleScreen(
                        useRoot = useRoot,
                        onUseRootChange = { useRoot = it },
                        rootMode = rootMode,
                        onRootModeChange = { mode ->
                            rootMode = mode
                            com.xingmihai.shrunner.engine.ShellEnv.preferredMode = mode
                            com.xingmihai.shrunner.engine.ShellEnv.resetProbe()
                            scope.launch(Dispatchers.IO) {
                                com.xingmihai.shrunner.engine.ShellEnv.probeRoot(force = true)
                            }
                        },
                        onReprobe = {
                            com.xingmihai.shrunner.engine.ShellEnv.resetProbe()
                            scope.launch(Dispatchers.IO) {
                                com.xingmihai.shrunner.engine.ShellEnv.probeRoot(force = true)
                            }
                        },
                        onDiagnose = {
                            val id = editingId
                            scope.launch(Dispatchers.IO) {
                                com.xingmihai.shrunner.data.ScriptRepository.get(app).find(id ?: "")
                                    ?.let { ShellEngine.diagnose(it.file.absolutePath) }
                                    ?: ShellEngine.diagnose(null)
                            }
                        },
                        onStop = { ShellEngine.stop() },
                        onClear = { ShellEngine.clear() }
                    )
                }
            }
        }
    }
}
