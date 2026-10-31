package com.xingmihai.shrunner.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 输出来源，控制台据此着色。 */
enum class Stream { STDOUT, STDERR, SYSTEM }

data class OutputLine(
    val text: String,
    val stream: Stream,
    val seq: Long
)

/** 一次执行请求。 */
data class RunRequest(
    val scriptId: String,
    val scriptName: String,
    val scriptPath: String,
    val args: List<String> = emptyList(),
    val workDir: String? = null,
    val env: Map<String, String> = emptyMap(),
    val useRoot: Boolean = false,
    val timeoutMs: Long = 0L // 0 表示不限制
)

/**
 * Shell 执行引擎（进程内单例）。
 * 设计要点：
 * 1. 通过 ProcessBuilder 调用系统 sh，合并 stdout/stderr，避免两条流互锁；
 * 2. 逐行回调，UI 实时追加；
 * 3. 单飞锁保证同时只有一个脚本在跑，支持超时与手动停止。
 */
object ShellEngine {

    private const val MAX_LINES = 5000
    private const val EXIT_TIMEOUT = 137

    private val seq = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var currentProcess: Process? = null

    private val _lines = MutableStateFlow<List<OutputLine>>(emptyList())
    val lines: StateFlow<List<OutputLine>> = _lines.asStateFlow()

    sealed interface RunState {
        data object Idle : RunState
        data class Running(val scriptName: String, val startedAt: Long) : RunState
        data class Finished(val scriptName: String, val exitCode: Int, val durationMs: Long) : RunState
        data class Failed(val scriptName: String, val message: String) : RunState
    }

    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    fun run(request: RunRequest) {
        scope.launch {
            mutex.withLock {
                runInternal(request)
            }
        }
    }

    private suspend fun runInternal(request: RunRequest) {
        clearOutputLocked()
        val started = System.currentTimeMillis()
        _state.value = RunState.Running(request.scriptName, started)
        appendSystem(buildBanner(request))

        var process: Process? = null
        try {
            // 先修一遍换行符/shebang，再暂存到纯 ASCII 路径执行
            val staged = runCatching { ShellEnv.stageScript(File(request.scriptPath), request.scriptId) }
                .getOrDefault(File(request.scriptPath))
            val scriptPath = ShellEnv.canonical(staged)
            if (scriptPath != ShellEnv.canonical(File(request.scriptPath))) {
                appendSystem("[ShRunner] 已暂存到：$scriptPath")
            }
            if (request.useRoot) {
                val probe = ShellEnv.probeRoot()
                if (!probe.ok) {
                    appendSystem("[ShRunner] root 探测失败：${probe.detail}，本次按非 root 执行")
                    probe.tried.take(3).forEach { appendSystem("[ShRunner] 尝试记录：$it") }
                } else {
                    appendSystem("[ShRunner] root 就绪：${probe.suPath} · ${probe.mode?.label}")
                }
            }

            process = startProcess(request, scriptPath)
            currentProcess = process

            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            val finished = withTimeoutOrNull(effectiveTimeout(request)) {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line, Stream.STDOUT)
                }
                process.waitFor()
            }

            if (finished == null) {
                process.destroy()
                appendSystem("[ShRunner] 执行超时（${request.timeoutMs} ms），已终止进程")
                _state.value = RunState.Finished(request.scriptName, EXIT_TIMEOUT, System.currentTimeMillis() - started)
            } else {
                appendSystem("[ShRunner] 进程退出，退出码 $finished${exitHint(finished)}")
                _state.value = RunState.Finished(request.scriptName, finished, System.currentTimeMillis() - started)
            }
        } catch (t: Throwable) {
            val message = t.message ?: t::class.java.simpleName
            appendSystem("[ShRunner] 执行失败：$message")
            _state.value = RunState.Failed(request.scriptName, message)
        } finally {
            runCatching { process?.destroy() }
            currentProcess = null
        }
    }

    private fun effectiveTimeout(request: RunRequest): Long =
        if (request.timeoutMs <= 0L) Long.MAX_VALUE else request.timeoutMs

    private fun buildBanner(request: RunRequest): String {
        val prefix = if (request.useRoot) "root" else "sh"
        val args = if (request.args.isEmpty()) "" else " " + request.args.joinToString(" ")
        return "$ ${prefix} ${request.scriptPath}$args"
    }

    /** 退出码含义提示，方便直接看懂 126 / 127 这类报错。 */
    private fun exitHint(code: Int): String = when (code) {
        EXIT_TIMEOUT -> "（执行超时被终止）"
        126 -> "（无执行权限，检查 chmod / SELinux）"
        127 -> "（命令或解释器未找到，检查 shebang 与路径）"
        0 -> ""
        else -> "（脚本自身返回）"
    }

    private fun planCommand(request: RunRequest, scriptPath: String): ShellEnv.CommandPlan {
        val shell = ShellEnv.shellPath()
        if (!request.useRoot) {
            val argv = mutableListOf(shell, scriptPath)
            argv.addAll(request.args)
            return ShellEnv.CommandPlan(argv, null, argv.joinToString(" "))
        }
        val probe = ShellEnv.probeRoot()
        if (!probe.ok || probe.suPath == null || probe.mode == null) {
            // root 探测没通过就降级为普通执行，至少脚本能跑起来
            appendSystem("[ShRunner] 已回退为普通（非 root）执行")
            val argv = mutableListOf(shell, scriptPath)
            argv.addAll(request.args)
            return ShellEnv.CommandPlan(argv, null, argv.joinToString(" "))
        }
        return ShellEnv.buildCommand(probe.mode, probe.suPath, shell, scriptPath, request.args)
    }

    private fun startProcess(request: RunRequest, scriptPath: String): Process {
        val plan = planCommand(request, scriptPath)
        appendSystem("[ShRunner] 实际命令：" + plan.display)

        val builder = ProcessBuilder(plan.argv)
        val workDir = request.workDir
        if (!workDir.isNullOrBlank() && File(workDir).isDirectory) {
            builder.directory(File(workDir))
        }
        // 合并 stderr 到 stdout，避免双流读取造成的管道阻塞
        builder.redirectErrorStream(true)
        val env = builder.environment()
        env["PATH"] = ShellEnv.defaultPath()
        env["HOME"] = request.workDir ?: "/data/local/tmp"
        env["TERM"] = "xterm-256color"
        request.env.forEach { (k, v) -> env[k] = v }

        val process = builder.start()
        // stdin 模式：把命令写进去后立刻关闭，让 su 开始执行
        plan.stdin?.let { text ->
            runCatching {
                process.outputStream.bufferedWriter().use { w ->
                    w.write(text)
                    w.flush()
                }
            }
        }
        return process
    }

    /** 手动停止当前执行。 */
    fun stop() {
        runCatching { currentProcess?.destroy() }
        currentProcess = null
        appendSystem("[ShRunner] 已收到停止指令")
    }

    fun clear() {
        _lines.value = emptyList()
        _state.value = RunState.Idle
    }

    private fun clearOutputLocked() {
        _lines.value = emptyList()
    }

    private fun append(text: String, stream: Stream) {
        val line = OutputLine(text, stream, seq.incrementAndGet())
        val current = _lines.value
        _lines.value = if (current.size >= MAX_LINES) {
            current.drop(current.size - MAX_LINES + 1) + line
        } else {
            current + line
        }
    }

    private fun appendSystem(text: String) = append(text, Stream.SYSTEM)
}
