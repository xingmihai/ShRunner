package com.xingmihai.shrunner.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingmihai.shrunner.data.ScriptConfig
import com.xingmihai.shrunner.data.ScriptEntry
import com.xingmihai.shrunner.data.ScriptRepository
import com.xingmihai.shrunner.engine.RunRequest
import com.xingmihai.shrunner.engine.RunnerService
import com.xingmihai.shrunner.engine.ShellEngine
import com.xingmihai.shrunner.engine.ShellEnv
import com.xingmihai.shrunner.ui.components.ScriptSettingsDialog
import com.xingmihai.shrunner.ui.screens.ConsoleScreen
import com.xingmihai.shrunner.ui.screens.EditorScreen
import com.xingmihai.shrunner.ui.screens.FileBrowserScreen
import com.xingmihai.shrunner.ui.screens.ScriptsScreen
import com.xingmihai.shrunner.ui.screens.SettingsScreen
import com.xingmihai.shrunner.ui.theme.ShRunnerTheme
import com.xingmihai.shrunner.util.DangerScan
import com.xingmihai.shrunner.util.displayNameOf
import com.xingmihai.shrunner.util.requestScriptShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

enum class ShTab(val label: String) {
    SCRIPTS("脚本"), FILES("文件"), CONSOLE("输出"), SETTINGS("设置")
}

@Composable
fun ShRunnerRoot(initialScriptId: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val repo = remember { ScriptRepository.get(app) }
    val runPrefs = remember { com.xingmihai.shrunner.data.RunPrefs.get(context) }
    val scriptConfig = remember { ScriptConfig.get(context) }
    val listVm: ScriptListViewModel = viewModel()
    val editorVm: EditorViewModel = viewModel()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(ShTab.SCRIPTS) }
    var editingId by remember { mutableStateOf<String?>(initialScriptId) }
    var useRoot by remember { mutableStateOf(runPrefs.useRoot) }
    var pendingRun by remember { mutableStateOf<PendingRun?>(null) }
    var risks by remember { mutableStateOf<List<String>>(emptyList()) }
    var settingsFor by remember { mutableStateOf<ScriptEntry?>(null) }

    LaunchedEffect(Unit) {
        val enginePrefs = context.getSharedPreferences("engine", 0)
        ShellEngine.modePrefs = enginePrefs
        ShellEngine.rememberMode(enginePrefs)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayNameOf(context, uri)
            listVm.import(uri, name)
            Toast.makeText(context, "已导入：$name", Toast.LENGTH_SHORT).show()
        }
    }

    /** 统一的运行入口：仓库里的脚本与外部路径都走这里。 */
    fun startRun(id: String, name: String, path: String) {
        val cfg = scriptConfig.of(id)
        val root = scriptConfig.useRoot(id, useRoot)
        RunnerService.start(context)
        ShellEngine.run(
            RunRequest(
                scriptId = id,
                scriptName = name,
                scriptPath = path,
                workDir = cfg.workDir.ifBlank { File(path).parent ?: repo.directory().absolutePath },
                useRoot = root,
                env = scriptConfig.envMap(id),
                dumpEnv = runPrefs.dumpEnv,
                // 脚本单独配置优先，没配置时回退全局默认
                autoAnswers = scriptConfig.answers(id).takeIf { it.isNotEmpty() }
                    ?: runPrefs.answers()
            )
        )
        tab = ShTab.CONSOLE
    }

    fun requestRun(id: String, name: String, path: String) {
        val hits = DangerScan.scan(path)
        if (hits.isEmpty()) startRun(id, name, path) else {
            pendingRun = PendingRun(id, name, path)
            risks = hits
        }
    }

    // 运行结束记录退出码
    LaunchedEffect(Unit) {
        ShellEngine.state.collect { state ->
            if (state is ShellEngine.RunState.Finished) {
                runCatching { repo.recordRun(state.scriptName, state.exitCode) }
                listVm.refresh()
            }
        }
    }

    LaunchedEffect(initialScriptId) {
        if (!initialScriptId.isNullOrBlank()) {
            repo.find(initialScriptId)?.let { requestRun(it.id, it.name, it.file.absolutePath) }
        }
    }

    if (risks.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { risks = emptyList(); pendingRun = null },
            title = { Text("确认执行？") },
            text = { Text("脚本中包含高风险操作：\n\n" + risks.joinToString("\n") { "· $it" }) },
            confirmButton = {
                TextButton(onClick = {
                    val p = pendingRun
                    risks = emptyList()
                    pendingRun = null
                    if (p != null) startRun(p.id, p.name, p.path)
                }) { Text("仍然执行") }
            },
            dismissButton = { TextButton(onClick = { risks = emptyList(); pendingRun = null }) { Text("取消") } }
        )
    }

    settingsFor?.let { entry ->
        ScriptSettingsDialog(
            title = entry.name,
            initial = scriptConfig.of(entry.id),
            onDismiss = { settingsFor = null },
            onConfirm = { cfg ->
                scriptConfig.save(entry.id, cfg)
                settingsFor = null
            }
        )
    }

    // 编辑器作为独立全屏页，保存后返回列表
    if (editingId != null) {
        ShRunnerTheme {
            EditorScreen(
                viewModel = editorVm,
                scriptId = editingId,
                onSaved = { id ->
                    editingId = null
                    listVm.refresh()
                },
                onRun = { id ->
                    repo.find(id)?.let { requestRun(it.id, it.name, it.file.absolutePath) }
                    editingId = null
                }
            )
        }
        return
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
                                        ShTab.FILES -> Icons.Default.Folder
                                        ShTab.CONSOLE -> Icons.Default.Code
                                        ShTab.SETTINGS -> Icons.Default.Settings
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
                        },
                        onEdit = { id -> editingId = id },
                        onRun = { entry -> requestRun(entry.id, entry.name, entry.file.absolutePath) },
                        onDelete = { entry -> listVm.delete(entry.id) },
                        onFavorite = { entry -> listVm.toggleFavorite(entry.id) },
                        onShortcut = { entry -> requestScriptShortcut(context, entry.id, entry.name) },
                        onSettings = { entry -> settingsFor = entry }
                    )

                    ShTab.FILES -> FileBrowserScreen(
                        onRun = { path ->
                            val file = File(path)
                            // 若该路径已导入过，沿用导入条目的 id，保证脚本配置（自动应答等）能命中
                            val id = repo.findByName(file.name)?.id ?: file.nameWithoutExtension
                            requestRun(id, file.name, file.absolutePath)
                        },
                        onImport = { path ->
                            scope.launch {
                                val (entry, error) = repo.importPath(path)
                                if (entry != null) {
                                    listVm.refresh()
                                    Toast.makeText(context, "已导入：${entry.name}", Toast.LENGTH_SHORT).show()
                                    tab = ShTab.SCRIPTS
                                } else {
                                    Toast.makeText(
                                        context,
                                        "导入失败：${error ?: "未知原因"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    )

                    ShTab.CONSOLE -> ConsoleScreen(
                        useRoot = useRoot,
                        onUseRootChange = {
                            useRoot = it
                            runPrefs.useRoot = it
                        },
                        onOpenAnswers = { id ->
                            repo.find(id)?.let { settingsFor = it }
                                ?: Toast.makeText(context, "该脚本不在列表里，请先导入", Toast.LENGTH_SHORT).show()
                        },
                        onStop = { ShellEngine.stop() },
                        onClear = { ShellEngine.clear() }
                    )

                    ShTab.SETTINGS -> SettingsScreen(
                        prefs = runPrefs,
                        useRoot = useRoot,
                        onUseRootChange = {
                            useRoot = it
                            runPrefs.useRoot = it
                        },
                        onReprobe = {
                            ShellEnv.resetProbe()
                            scope.launch(Dispatchers.IO) { ShellEnv.probeRoot(force = true) }
                        }
                    )
                }
            }
        }
    }
}

private data class PendingRun(val id: String, val name: String, val path: String)
