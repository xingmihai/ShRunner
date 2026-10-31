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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

enum class Stream { STDOUT, STDERR, SYSTEM }

data class OutputLine(val text: String, val stream: Stream, val seq: Long)

data class RunRequest(
    val scriptId: String,
    val scriptName: String,
    val scriptPath: String,
    val args: List<String> = emptyList(),
    val workDir: String? = null,
    val env: Map<String, String> = emptyMap(),
    val useRoot: Boolean = false,
    val timeoutMs: Long = 0L,
    val dumpEnv: Boolean = false,
    val autoAnswers: List<String> = emptyList()
)

/**
 * Shell 执行引擎。
 *
 * 执行顺序（root 模式）：
 * 1. 上次成功的方式（记住了就先用它）；
 * 2. 持久 root shell（大脚本唯一可靠路径）；
 * 3. su -c / su 0 两种一次性方式；
 * 4. 最后回退非 root。
 */
object ShellEngine {

    private const val MAX_LINES = 5000
    private const val EXIT_TIMEOUT = 137
    private val PREF_SUCCESS = "shrunner_last_ok_mode"

    private val seq = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var currentProcess: Process? = null

    @Volatile
    private var currentSession: RootSession? = null

    @Volatile
    private var answerWatcher: ((String) -> Unit)? = null

    @Volatile
    private var lastOkMode: ShellEnv.SuMode? = null

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

    fun rememberMode(prefs: android.content.SharedPreferences) {
        lastOkMode = prefs.getString(PREF_SUCCESS, null)?.let { runCatching { ShellEnv.SuMode.valueOf(it) }.getOrNull() }
    }

    private fun saveMode(mode: ShellEnv.SuMode, prefs: android.content.SharedPreferences?) {
        lastOkMode = mode
        prefs?.edit()?.putString(PREF_SUCCESS, mode.name)?.apply()
    }

    var modePrefs: android.content.SharedPreferences? = null

    fun run(request: RunRequest) {
        scope.launch {
            mutex.withLock { runInternal(request) }
        }
    }

    private suspend fun runInternal(request: RunRequest) {
        _lines.value = emptyList()
        val started = System.currentTimeMillis()
        _state.value = RunState.Running(request.scriptName, started)
        appendSystem("$ ${if (request.useRoot) "root" else "sh"} ${request.scriptName}")

        var lastExit = -1
        var succeeded = false
        try {
            val original = File(request.scriptPath)
            val stat = ShellEnv.stat(original)
            appendSystem(
                "体检：${"%.1fMB".format(stat.sizeBytes / 1048576.0)} · " +
                    (if (stat.isBinary) "含二进制" else "纯文本") + " · shebang「${stat.shebang}」"
            )

            // 大脚本零拷贝：shebang 正确且路径 ASCII 时直接用原文件
            val useSession = request.useRoot &&
                (lastOkMode == ShellEnv.SuMode.SESSION || lastOkMode == null)
            val scriptPath = resolveScriptPath(request, original, useSession)

            if (request.useRoot) {
                val probe = ShellEnv.probeRoot()
                if (!probe.ok) appendSystem("root 不可用（${probe.detail}），改普通执行")
            }

            // 1) 优先用上次成功的方式
            val ordered = orderedModes(request.useRoot)
            for ((index, mode) in ordered.withIndex()) {
                if (index > 0) appendSystem("换用：${mode.label}")
                val result = execute(request, scriptPath, mode)
                lastExit = result.first
                if (result.second) {
                    succeeded = true
                    if (request.useRoot && mode != ShellEnv.SuMode.SESSION) saveMode(mode, modePrefs)
                    else if (request.useRoot) saveMode(mode, modePrefs)
                    break
                }
            }

            appendSystem("退出码 $lastExit${exitHint(lastExit)}")
            _state.value = RunState.Finished(request.scriptName, lastExit, System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            val message = t.message ?: t::class.java.simpleName
            appendSystem("执行失败：$message")
            _state.value = RunState.Failed(request.scriptName, message)
        } finally {
            answerWatcher = null
            runCatching { currentProcess?.destroy() }
            currentProcess = null
            currentSession = null
        }
    }

    /**
     * 决定实际执行哪个文件：
     * 1. 应用读得到 → 需要暂存才暂存，否则直接用原文件（大脚本零拷贝）；
     * 2. 应用读不到（/sdcard 等无存储权限路径）→ 用 root shell 复制一份再执行。
     */
    private suspend fun resolveScriptPath(
        request: RunRequest,
        original: File,
        useSession: Boolean
    ): String {
        val readable = runCatching { original.isFile && original.canRead() }.getOrDefault(false)
        if (!readable) {
            appendSystem("应用无权直接读取该文件，先用 root 复制副本")
            val staged = ShellEnv.stageFileFor(request.scriptId)
            val error = ShellCopy.copy(original.absolutePath, staged.absolutePath)
            if (error != null) throw IllegalStateException(error)
            return ShellEnv.canonical(staged)
        }
        if (ShellEnv.needsStaging(original, useSession)) {
            val staged = runCatching { ShellEnv.stageScript(original, request.scriptId) }
                .getOrDefault(original)
            if (staged.path != original.path) appendSystem("已暂存副本：${staged.name}")
            return ShellEnv.canonical(staged)
        }
        return ShellEnv.canonical(original)
    }

    /** 候选方式顺序：上次成功的 → 持久会话 → su -c → su 0 → 非 root。 */
    private fun orderedModes(useRoot: Boolean): List<ShellEnv.SuMode> {
        if (!useRoot) return listOf(ShellEnv.SuMode.SU_C_SH)   // 占位：下面会按非 root 处理
        val list = mutableListOf<ShellEnv.SuMode>()
        ShellEnv.preferredMode?.let { list += it }
        lastOkMode?.let { if (it !in list) list += it }
        listOf(ShellEnv.SuMode.SESSION, ShellEnv.SuMode.SU_C_SH, ShellEnv.SuMode.SU_0_SH, ShellEnv.SuMode.SU_STDIN)
            .forEach { if (it !in list) list += it }
        return list
    }

    /** 返回 (退出码, 是否真的执行成功)。 */
    private suspend fun execute(
        request: RunRequest,
        scriptPath: String,
        mode: ShellEnv.SuMode
    ): Pair<Int, Boolean> {
        if (!request.useRoot) return executePlain(request, scriptPath)

        val probe = ShellEnv.probeRoot()
        val su = probe.suPath ?: return 127 to false
        val shell = ShellEnv.shellPath()

        if (mode == ShellEnv.SuMode.SESSION) {
            val session = RootSession.open(su) ?: return 127 to false
            currentSession = session
            return try {
                session.exec(ShellEnv.envPrologue(), 8000)
                request.env.filterKeys { it != "PATH" }.takeIf { it.isNotEmpty() }?.let { extra ->
                    session.exec(
                        extra.entries.joinToString(" ") { (k, v) -> "export $k=" + ShellEnv.singleQuote(v) },
                        8000
                    )
                }
                if (request.dumpEnv) {
                    session.exec("env | sort", 8000) { line -> if (line.isNotBlank()) appendSystem("  $line") }
                }
                request.workDir?.let { session.exec("cd " + ShellEnv.singleQuote(it), 8000) }

                // 预填输入：把答案一次性塞进管道再启动脚本。
                // 不能用「看到提问再回答」，脚本往往在我们写入前就读了，拿到 EOF 直接判定为取消，
                // 迟到的答案还会被 shell 当成命令执行（y: not found）。
                val command = buildScriptCommand(shell, scriptPath, request.args, request.autoAnswers)
                if (request.autoAnswers.isNotEmpty()) {
                    appendSystem("已预填输入：${request.autoAnswers.joinToString(" / ")}")
                }

                val timeout = if (request.timeoutMs > 0) request.timeoutMs else 600_000
                val code = session.exec(command, timeout) { line ->
                    if (line.isNotEmpty()) append(line, Stream.STDOUT)
                }
                val exit = code ?: return EXIT_TIMEOUT to false
                exit to (exit == 0)
            } finally {
                answerWatcher = null
                currentSession = null
                session.close()
            }
        }

        // 一次性方式
        val inner = buildScriptCommand(shell, scriptPath, request.args, request.autoAnswers)
        val plan = ShellEnv.buildCommand(mode, su, shell, scriptPath, request.args, inner)
        val process = startProcess(request, plan)
        currentProcess = process
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        var lines = 0
        val finished = withTimeoutOrNull(effectiveTimeout(request)) {
            while (true) {
                val line = reader.readLine() ?: break
                lines++
                append(line, Stream.STDOUT)
            }
            process.waitFor()
        }
        if (finished == null) {
            process.destroy()
            return EXIT_TIMEOUT to false
        }
        // 输出过少基本等于没跑起来
        return finished to (finished == 0 && lines > 0)
    }

    private suspend fun executePlain(request: RunRequest, scriptPath: String): Pair<Int, Boolean> {
        // 同样预填：把答案写进 shell 命令，由 sh 负责喂给脚本
        val shell = ShellEnv.shellPath()
        val inner = buildScriptCommand(shell, scriptPath, request.args, request.autoAnswers)
        if (request.autoAnswers.isNotEmpty()) {
            appendSystem("已预填输入：${request.autoAnswers.joinToString(" / ")}")
        }
        val argv = mutableListOf(shell, "-c", inner)
        val plan = ShellEnv.CommandPlan(argv, null, inner)
        val process = startProcess(request, plan)
        currentProcess = process
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        var lines = 0
        val finished = withTimeoutOrNull(effectiveTimeout(request)) {
            while (true) {
                val line = reader.readLine() ?: break
                lines++
                append(line, Stream.STDOUT)
            }
            process.waitFor()
        }
        if (finished == null) {
            process.destroy()
            return EXIT_TIMEOUT to false
        }
        return finished to true
    }

    /**
     * 构造执行脚本的 shell 命令。
     * 有预设答案时用 printf 管道预填：脚本读取时答案已在管道里，不会读到 EOF。
     */
    private fun buildScriptCommand(
        shell: String,
        scriptPath: String,
        args: List<String>,
        answers: List<String>
    ): String {
        val run = buildList {
            add(shell)
            add(ShellEnv.singleQuote(scriptPath))
            args.forEach { add(ShellEnv.singleQuote(it)) }
        }.joinToString(" ")
        if (answers.isEmpty()) return run
        val printfArgs = answers.joinToString(" ") { ShellEnv.singleQuote(it) }
        val feed = "printf '%s\\n' $printfArgs"
        // 优先套 pty：部分脚本只认终端输入，直接管道会被当成空输入而取消
        return if (ShellEnv.hasPty()) {
            "$feed | script -qec $run /dev/null"
        } else {
            "$feed | $run"
        }
    }

    private fun effectiveTimeout(request: RunRequest): Long =
        if (request.timeoutMs > 0) request.timeoutMs else Long.MAX_VALUE

    private fun startProcess(request: RunRequest, plan: ShellEnv.CommandPlan): Process {
        val builder = ProcessBuilder(plan.argv)
        request.workDir?.let { if (File(it).isDirectory) builder.directory(File(it)) }
        builder.redirectErrorStream(true)
        val env = builder.environment()
        ShellEnv.baseEnv().forEach { (k, v) -> env[k] = v }
        request.workDir?.let { env["HOME"] = it }
        request.env.forEach { (k, v) -> env[k] = v }
        val process = builder.start()
        plan.stdin?.let { text ->
            runCatching {
                process.outputStream.bufferedWriter().use { w -> w.write(text); w.flush() }
            }
        }
        return process
    }

    /** 把用户输入送给正在运行的脚本。 */
    fun sendInput(text: String): Boolean {
        currentSession?.let { return it.sendInput(text) }
        val process = currentProcess ?: return false
        return runCatching {
            process.outputStream.bufferedWriter().use { w ->
                w.write(text)
                if (!text.endsWith("\n")) w.write("\n")
                w.flush()
            }
            true
        }.getOrDefault(false)
    }

    fun appendEcho(text: String) = append(text, Stream.SYSTEM)

    private fun looksLikePrompt(line: String): Boolean {
        val text = line.trim()
        if (text.isEmpty()) return false
        val patterns = listOf(
            Regex("""\[\d+\s*/\s*\d+\].*[:：]\s*$"""),
            Regex("""[:：]\s*$"""),
            Regex("""\?\s*$"""),
            Regex("""\(y/n\)|\[y/N\]|\[Y/n\]""", RegexOption.IGNORE_CASE),
            Regex("""请输入|请选择|请确认|Please enter|Please select|Please choose""")
        )
        return patterns.any { it.containsMatchIn(text) }
    }

    private fun exitHint(code: Int): String = when (code) {
        EXIT_TIMEOUT -> "（超时终止）"
        126 -> "（无执行权限）"
        127 -> "（命令或解释器未找到）"
        0 -> ""
        else -> "（脚本自身返回）"
    }

    fun stop() {
        runCatching { currentProcess?.destroy() }
        currentProcess = null
        appendSystem("已停止")
    }

    fun clear() {
        _lines.value = emptyList()
        _state.value = RunState.Idle
    }

    private fun append(text: String, stream: Stream) {
        val line = OutputLine(text, stream, seq.incrementAndGet())
        val current = _lines.value
        _lines.value = if (current.size >= MAX_LINES) {
            current.drop(current.size - MAX_LINES + 1) + line
        } else current + line
    }

    private fun appendSystem(text: String) = append("[ShRunner] $text", Stream.SYSTEM)
}
