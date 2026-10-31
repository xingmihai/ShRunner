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
            process = startProcess(request)
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
                appendSystem("[ShRunner] 进程退出，退出码 $finished")
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
        val prefix = if (request.useRoot) "su -c sh" else "sh"
        val args = if (request.args.isEmpty()) "" else " " + request.args.joinToString(" ")
        return "$ ${prefix} ${request.scriptPath}$args"
    }

    private fun startProcess(request: RunRequest): Process {
        val shell = ShellEnv.shellPath()
        val command = mutableListOf<String>()
        if (request.useRoot) {
            val su = ShellEnv.suPath() ?: error("未找到可用的 su，无法以 root 执行")
            command += su
            command += "-c"
            command += "$shell \"${request.scriptPath}\""
        } else {
            command += shell
            command += request.scriptPath
        }
        command += request.args

        val builder = ProcessBuilder(command)
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
        return builder.start()
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
