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

        try {
            // 先修一遍换行符/shebang，再暂存到纯 ASCII 路径执行
            val staged = runCatching { ShellEnv.stageScript(File(request.scriptPath), request.scriptId) }
                .getOrDefault(File(request.scriptPath))
            val scriptPath = ShellEnv.canonical(staged)
            if (scriptPath != ShellEnv.canonical(File(request.scriptPath))) {
                appendSystem("[ShRunner] 已暂存到：$scriptPath")
            }
            appendSystem(describeScript(staged, File(request.scriptPath)))

            if (request.useRoot) {
                val probe = ShellEnv.probeRoot()
                if (!probe.ok) {
                    appendSystem("[ShRunner] root 探测失败：${probe.detail}，本次按非 root 执行")
                    probe.tried.take(3).forEach { appendSystem("[ShRunner] 尝试记录：$it") }
                } else {
                    appendSystem("[ShRunner] root 就绪：${probe.suPath} · ${probe.mode?.label}")
                }
            }

            // 逐个候选方案尝试：退出 127 或出现 su 解码错误时自动换下一种
            val plans = candidatePlans(request, scriptPath)
            val sizeBytes = runCatching { File(scriptPath).length() }.getOrDefault(0L)
            if (request.useRoot && sizeBytes > 2 * 1024 * 1024) {
                appendSystem("[ShRunner] 脚本超过 2MB（${sizeBytes / 1024 / 1024}MB），su -c 有体积上限，优先用持久 root shell")
                val code = runViaRootSession(request, scriptPath)
                if (code != null && code == 0) {
                    appendSystem("[ShRunner] 进程退出，退出码 $code")
                    _state.value = RunState.Finished(
                        request.scriptName, code, System.currentTimeMillis() - started
                    )
                    return
                }
                appendSystem("[ShRunner] 持久 shell 未成功（退出 ${code ?: "未知"}），继续尝试其他方式")
            }
            var lastExit = -1
            var succeeded = false
            for ((index, plan) in plans.withIndex()) {
                if (index > 0) {
                    appendSystem("[ShRunner] 上一种方式失败（退出 $lastExit），自动换用：${plan.display}")
                }
                val result = execute(request, plan)
                lastExit = result.first
                val noOutput = result.third <= 1     // 只回了一行（多半是报错本身）
                val looksBroken = result.second || noOutput
                if (lastExit == 0 || (lastExit != 127 && !looksBroken)) {
                    succeeded = true
                    break
                }
                if (looksBroken) {
                    appendSystem("[ShRunner] 判定该方式未真正执行（输出 ${result.third} 行），继续尝试")
                }
            }

            // 所有一次性方案都失败 → 改用持久 root shell：脚本路径不经 su 参数解析
            if (!succeeded && request.useRoot) {
                val code = runViaRootSession(request, scriptPath)
                if (code != null) {
                    lastExit = code
                    succeeded = code == 0
                    if (!succeeded) {
                        // 整条执行也异常，最后再试逐条（二进制脚本跳过，逐行会破坏结构）
                        val report = ShellEnv.inspectStaged(staged, File(request.scriptPath))
                        val lineCode = if (report.isBinary) {
                            appendSystem("[ShRunner] 脚本含二进制数据，跳过逐行执行（会破坏结构）")
                            null
                        } else {
                            runLineByLine(request, staged)
                        }
                        if (lineCode != null) {
                            lastExit = lineCode
                            succeeded = lineCode == 0
                        }
                    }
                }
            }

            appendSystem("[ShRunner] 进程退出，退出码 $lastExit${exitHint(lastExit)}")
            _state.value = RunState.Finished(request.scriptName, lastExit, System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            val message = t.message ?: t::class.java.simpleName
            appendSystem("[ShRunner] 执行失败：$message")
            _state.value = RunState.Failed(request.scriptName, message)
        } finally {
            runCatching { currentProcess?.destroy() }
            currentProcess = null
        }
    }

    /** 返回 (退出码, 是否出现解码类错误, 输出行数)。 */
    private suspend fun execute(
        request: RunRequest,
        plan: ShellEnv.CommandPlan
    ): Triple<Int, Boolean, Int> {
        appendSystem("[ShRunner] 实际命令：" + plan.display)
        val process = startProcess(request, plan)
        currentProcess = process
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        var sawDecodeError = false
        var lines = 0
        val finished = withTimeoutOrNull(effectiveTimeout(request)) {
            while (true) {
                val line = reader.readLine() ?: break
                lines++
                if (line.contains("decompress", true) || line.contains("not gzip", true) ||
                    line.contains("No such file or directory", true)
                ) {
                    sawDecodeError = true
                }
                append(line, Stream.STDOUT)
            }
            process.waitFor()
        }
        if (finished == null) {
            process.destroy()
            appendSystem("[ShRunner] 执行超时（${request.timeoutMs} ms），已终止进程")
            return Triple(EXIT_TIMEOUT, sawDecodeError, lines)
        }
        return Triple(finished, sawDecodeError, lines)
    }

    /**
     * 兼容模式 A：持久 root shell + 整条命令。
     * 脚本路径只在 shell 内部作为参数出现，不经过 su 的参数解析，
     * 因此可以绕过 su -c 的体积上限（4MB 能过、21MB 报 Cannot decompress 的设备靠这个救）。
     */
    private suspend fun runViaRootSession(request: RunRequest, scriptPath: String): Int? {
        val su = ShellEnv.suPath() ?: return null
        val shell = ShellEnv.shellPath()
        val session = RootSession.open(su) ?: run {
            appendSystem("[ShRunner] 持久 root shell 不可用")
            return null
        }
        appendSystem("[ShRunner] 启用持久 root shell：${session.info()}，在 shell 内执行脚本")
        return try {
            val command = buildList {
                add(shell)
                add(ShellEnv.singleQuote(scriptPath))
                request.args.forEach { add(ShellEnv.singleQuote(it)) }
            }.joinToString(" ")
            val timeout = if (request.timeoutMs > 0) request.timeoutMs else 600_000
            session.exec(command, timeout) { line -> if (line.isNotEmpty()) append(line, Stream.STDOUT) }
        } finally {
            session.close()
        }
    }

    /**
     * 兼容模式 B（最后兜底）：持久 root shell 逐条短命令。
     * 仅在整条执行也失败时使用；跨行结构（heredoc、函数）可能不生效。
     */
    private suspend fun runLineByLine(request: RunRequest, script: File): Int? {
        val su = ShellEnv.suPath() ?: return null
        val session = RootSession.open(su) ?: return null
        appendSystem("[ShRunner] 兜底方案：逐条短命令执行（跨行结构可能失效）")
        return try {
            val lines = runCatching { script.readLines() }.getOrElse { return null }
            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val code = session.exec(line, 15_000) { l -> if (l.isNotEmpty()) append(l, Stream.STDOUT) }
                if (code == null) {
                    appendSystem("[ShRunner] 第 ${index + 1} 行超时，已中止")
                    return 1
                }
            }
            appendSystem("[ShRunner] 逐条执行完毕")
            0
        } finally {
            session.close()
        }
    }

    /** 脚本体检：全部按字节处理，避免破坏内嵌的二进制数据。 */
    private fun describeScript(staged: File, original: File): String {
        val report = ShellEnv.inspectStaged(staged, original)
        val mb = "%.1fMB".format(report.sizeBytes / 1048576.0)
        val integrity = if (report.sameSize) "字节数一致（内容未改动）" else "⚠ 字节数与原文件不同"
        return "[ShRunner] 脚本体检：$mb · ${if (report.isBinary) "含二进制数据" else "纯文本"} · " +
            "shebang「${report.shebang}」 · $integrity"
    }

    /** 候选执行方案：首选探测通过的，其余作为失败回退。 */
    private fun candidatePlans(request: RunRequest, scriptPath: String): List<ShellEnv.CommandPlan> {
        val shell = ShellEnv.shellPath()
        val plain = run {
            val argv = mutableListOf(shell, scriptPath)
            argv.addAll(request.args)
            ShellEnv.CommandPlan(argv, null, argv.joinToString(" "))
        }
        if (!request.useRoot) return listOf(plain)

        val probe = ShellEnv.probeRoot()
        val su = probe.suPath
        if (!probe.ok || su == null) {
            appendSystem("[ShRunner] 已回退为普通（非 root）执行")
            return listOf(plain)
        }

        val sizeMb = runCatching { File(scriptPath).length() }.getOrDefault(0L) / (1024 * 1024)
        // 大脚本（>2MB）直接走持久 shell：su -c 对参数体积有上限，试了也是白跑
        val bigScript = sizeMb >= 2

        val modes = mutableListOf<ShellEnv.SuMode>()
        if (!bigScript) {
            modes += ShellEnv.SuMode.SU_C_GZIP   // 仅小脚本值得试
        }
        probe.mode?.let { if (it !in modes) modes += it }
        ShellEnv.preferredMode?.let { if (it !in modes) modes += it }
        ShellEnv.SuMode.entries.forEach { if (it !in modes && it != ShellEnv.SuMode.SU_C_GZIP) modes += it }
        if (bigScript) modes += ShellEnv.SuMode.SU_C_GZIP   // 放最后兜底

        // 预先生成 gzip 版本（失败则该模式自动跳过）
        val gzPath = ShellEnv.gzip(File(scriptPath))?.let { ShellEnv.canonical(it) }
        if (gzPath != null) {
            appendSystem("[ShRunner] 已生成压缩副本（${File(gzPath).length() / 1024} KB）：$gzPath")
        }

        return modes.mapNotNull { mode ->
            val path = if (mode == ShellEnv.SuMode.SU_C_GZIP) gzPath ?: return@mapNotNull null else scriptPath
            ShellEnv.buildCommand(mode, su, shell, path, request.args)
        } + plain
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

    private fun startProcess(request: RunRequest, plan: ShellEnv.CommandPlan): Process {
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

    /** 一键 root 诊断：把探测结果直接打到输出区。 */
    fun diagnose(scriptPath: String?) {
        scope.launch {
            appendSystem("[ShRunner] 开始 root 诊断…")
            val results = ShellEnv.diagnose(ShellEnv.canonical(File(scriptPath ?: "")))
            results.forEach { appendSystem(it) }
        }
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
