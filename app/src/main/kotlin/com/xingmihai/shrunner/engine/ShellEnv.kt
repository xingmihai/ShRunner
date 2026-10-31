package com.xingmihai.shrunner.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/** 设备 shell 环境探测：sh 路径、root 方案与调用形式、busybox/toybox、脚本暂存。 */
object ShellEnv {

    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/debug_ramdisk/su"
    )

    private val TOOLBOX_CANDIDATES = listOf(
        "/system/bin/toybox",
        "/system/bin/busybox",
        "/system/xbin/busybox",
        "/sbin/busybox",
        "/data/adb/magisk/busybox"
    )

    /**
     * 不同的 su 调用形式。
     * 有些设备（尤其国内一键 root / 部分厂商内置 su）的 `-c` 参数解析是坏的：
     * 它会对参数做解码再执行，于是出现 `gzip: not gzip / Cannot decompress` 这类报错。
     * 因此提供多种形式，自动探测出能真正跑通脚本的那一种。
     */
    enum class SuMode(val label: String) {
        /**
         * `su -c "脚本.gz"` —— 针对会把参数当压缩包解压再执行的 su。
         * 这类 su 遇到明文脚本会报 `gzip: not gzip / Cannot decompress`，
         * 那就直接给它 gzip 格式。
         */
        SU_C_GZIP("su -c \"脚本.gz\"（压缩传入，推荐）"),

        /** `su -c "sh script args"` —— 最常见 */
        SU_C_SH("su -c \"sh 脚本\""),

        /** `su 0 "sh script args"` —— 部分 su 只认 uid 形式 */
        SU_0_SH("su 0 \"sh 脚本\""),

        /** `su -c "script args"` —— 直接跑脚本，靠 shebang */
        SU_C_DIRECT("su -c \"脚本\""),

        /** `su -c sh 脚本`（不带引号），专治把引号一起吃进去的 su */
        SU_C_SH_PLAIN("su -c sh 脚本（无引号）"),

        /** `su -s sh -c "script args"` —— 显式指定 shell */
        SU_S_C("su -s sh -c \"脚本\""),

        /** 不带任何参数，把命令写进 su 的 stdin —— 彻底绕开坏掉的 -c 解析 */
        SU_STDIN("su ← stdin（推荐，兼容性最好）");

        companion object {
            fun of(name: String?): SuMode? = entries.firstOrNull { it.name == name }
        }
    }

    data class RootProbe(
        val ok: Boolean,
        val suPath: String?,
        val mode: SuMode?,
        val detail: String,
        val tried: List<String> = emptyList()   // 每种尝试的失败原因，便于排查
    )

    @Volatile
    private var cachedProbe: RootProbe? = null

    @Volatile
    var preferredMode: SuMode? = null   // 用户在界面上手动指定

    @Volatile
    private var stageDir: File? = null

    /** 由 Application 注入，用于确定暂存目录（应用私有目录，一定能写）。 */
    fun init(context: android.content.Context) {
        runCatching {
            val dir = File(context.filesDir, "scripts/.stage")
            dir.mkdirs()
            stageDir = dir
        }
    }

    private fun ensureStageDir(): File? {
        stageDir?.let { return it }
        runCatching {
            val dir = File("/data/local/tmp").takeIf { it.isDirectory && it.canWrite() }
            if (dir != null) stageDir = dir
        }
        return stageDir
    }

    fun shellPath(): String {
        val candidates = listOf("/system/bin/sh", "/bin/sh", "/system/bin/mksh")
        return candidates.firstOrNull { File(it).exists() } ?: "/system/bin/sh"
    }

    fun suPath(): String? = SU_CANDIDATES.firstOrNull { File(it).exists() }

    fun isRootAvailable(): Boolean = probeRoot().ok

    fun toolboxPath(): String? = TOOLBOX_CANDIDATES.firstOrNull { File(it).exists() }

    fun toolboxSummary(): String = toolboxPath() ?: "无（命令集以系统 toybox 为准）"

    fun defaultPath(): String {
        val base = "/system/bin:/system/xbin:/vendor/bin:/sbin:/sbin/bin:/product/bin:/apex/com.android.runtime/bin"
        val extra = listOfNotNull(toolboxPath()).joinToString(":") { File(it).parent ?: "" }
        return if (extra.isBlank()) base else "$base:$extra"
    }

    /**
     * 端到端探测 root：真的跑一个探针脚本，只有输出里出现标记才算可用。
     * 只跑 `su -c id` 会被这类坏 su 骗过（uid=0 正常但传脚本就炸）。
     */
    fun probeRoot(force: Boolean = false): RootProbe {
        if (!force) cachedProbe?.let { return it }
        cachedProbe = probeRootInternal()
        return cachedProbe!!
    }

    private fun probeRootInternal(): RootProbe {
        val probeFile = runCatching {
            // 探针必须落在应用自己能写的目录里（/data/local/tmp 在 Android 10+ 对应用不可写）
            val dir = ensureStageDir()
                ?: File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
            val f = File(dir, "shrunner_probe.sh")
            f.writeText("#!/system/bin/sh\necho SHRUNNER_PROBE_OK\nid\nexit 0\n")
            f.setExecutable(true, false)
            f.setReadable(true, false)
            f
        }.getOrNull() ?: return RootProbe(false, null, null, "无法创建探针脚本（暂存目录不可写）")

        val tried = mutableListOf<String>()
        val modes = preferredMode?.let { listOf(it) } ?: SuMode.entries

        for (su in SU_CANDIDATES) {
            if (!File(su).exists()) continue
            for (mode in modes) {
                val result = runCommand(
                    buildCommand(mode, su, shellPath(), probeFile.absolutePath, emptyList())
                )
                val output = result.first
                if (output.contains("SHRUNNER_PROBE_OK")) {
                    return RootProbe(true, su, mode, "已验证可执行", tried)
                }
                val reason = output.trim().lineSequence().lastOrNull { it.isNotBlank() }
                    ?.take(60) ?: "退出码 ${result.second}"
                tried.add("${su.substringAfterLast('/')} · ${mode.label} → $reason")
            }
        }
        return RootProbe(false, suPath(), null, tried.lastOrNull() ?: "所有 su 形式均无法执行脚本", tried)
    }

    /** 返回 (stdout, 是否需要通过 stdin 写入的命令, exitCode) */
    data class CommandPlan(
        val argv: List<String>,
        val stdin: String?,   // 非空表示需要写入进程 stdin
        val display: String   // 展示用
    )

    fun buildCommand(
        mode: SuMode,
        su: String,
        shell: String,
        scriptPath: String,
        args: List<String>
    ): CommandPlan {
        val quotedScript = singleQuote(scriptPath)
        val quotedArgs = args.joinToString(" ") { singleQuote(it) }
        return when (mode) {
            SuMode.SU_C_GZIP -> {
                val payload = quotedScript + quotedArgs
                CommandPlan(listOf(su, "-c", payload), null, "$su -c '$payload'")
            }
            SuMode.SU_C_SH -> {
                val payload = "$shell $quotedScript$quotedArgs"
                CommandPlan(listOf(su, "-c", payload), null, "$su -c '$payload'")
            }
            SuMode.SU_C_SH_PLAIN -> {
                val payload = "$shell $scriptPath$quotedArgs"
                CommandPlan(listOf(su, "-c", payload), null, "$su -c '$payload'")
            }
            SuMode.SU_0_SH -> {
                val payload = "$shell $quotedScript$quotedArgs"
                CommandPlan(listOf(su, "0", payload), null, "$su 0 '$payload'")
            }
            SuMode.SU_C_DIRECT -> {
                val payload = "$quotedScript$quotedArgs"
                CommandPlan(listOf(su, "-c", payload), null, "$su -c '$payload'")
            }
            SuMode.SU_S_C -> {
                val payload = "$quotedScript$quotedArgs"
                CommandPlan(listOf(su, "-s", shell, "-c", payload), null, "$su -s $shell -c '$payload'")
            }
            SuMode.SU_STDIN -> {
                val payload = "$shell $quotedScript$quotedArgs\nexit\n"
                CommandPlan(listOf(su), payload, "$payload | $su")
            }
        }
    }

    /**
     * root 诊断：逐个验证 su 到底能吃什么，把结果打回输出区。
     * 用于定位「su -c 传脚本就报 gzip / Cannot decompress」这类设备专属问题。
     */
    fun diagnose(scriptPath: String?): List<String> {
        val out = mutableListOf<String>()
        val shell = shellPath()
        val su = suPath()
        out += "== ShRunner root 诊断 =="
        out += "sh: $shell"
        out += "su: ${su ?: "未找到"}"

        if (su != null) {
            runCatching {
                val p = ProcessBuilder(listOf(shell, "-c", "ls -l $su")).redirectErrorStream(true).start()
                val t = p.inputStream.bufferedReader().readText()
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                out += "su 文件信息: ${t.trim().lineSequence().firstOrNull() ?: "无输出"}"
            }
        }

        // 1) su 能否执行一条普通短命令
        if (su != null) {
            val r = runShort(listOf(su, "-c", "echo DIAG_ECHO_OK"))
            out += if (r.first.contains("DIAG_ECHO_OK")) {
                "① su -c 'echo' → 成功"
            } else {
                "① su -c 'echo' → 失败：${summarize(r)}"
            }
        }

        // 2) 非 root 直接执行真实脚本，排除脚本自身问题
        scriptPath?.let { path ->
            val r = runShort(listOf(shell, path), timeoutSec = 20)
            out += "② 非 root 执行脚本 → 退出码 ${r.second}｜${summarize(r)}"
        }

        // 3) 不同体积的探针脚本，找出 su 的容量上限
        if (su != null) {
            val dir = ensureStageDir()
            if (dir != null) {
                for (sizeKb in listOf(1, 64, 512, 4096)) {
                    val f = runCatching {
                        val file = File(dir, "diag_${sizeKb}k.sh")
                        val body = "#!/system/bin/sh\necho DIAG_SIZE_OK_${sizeKb}K\n"
                        file.writeText(body + ": > /dev/null\n" + "# ".repeat(1) + "pad\n".padEnd(2) +
                            "#" + "x".repeat(sizeKb * 1024) + "\n")
                        file.setExecutable(true, false)
                        file
                    }.getOrNull() ?: continue
                    val r = runShort(listOf(su, "-c", "$shell ${f.absolutePath}"), timeoutSec = 20)
                    out += if (r.first.contains("DIAG_SIZE_OK_${sizeKb}K")) {
                        "③ ${sizeKb}KB 脚本 → 成功"
                    } else {
                        "③ ${sizeKb}KB 脚本 → 失败：${summarize(r)}"
                    }
                }
            }
        }

        // 4) 持久 root shell 是否可用
        if (su != null) {
            val session = RootSession.open(su)
            out += if (session != null) {
                val idOut = runBlockingSession(session, "id")
                session.close()
                "④ 持久 root shell → 可用｜id: ${idOut?.firstOrNull { it.contains("uid=") } ?: "无输出"}"
            } else {
                "④ 持久 root shell → 不可用"
            }
        }

        out += "== 诊断结束 =="
        return out
    }

    /** 同步取一条命令的输出，用于诊断这种非协程场景。 */
    private fun runBlockingSession(session: RootSession, cmd: String): List<String>? {
        val collected = java.util.Collections.synchronizedList(mutableListOf<String>())
        var code: Int? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            code = session.exec(cmd, 6000) { line -> if (line.isNotEmpty()) collected += line }
            latch.countDown()
        }
        runCatching { latch.await(8, java.util.concurrent.TimeUnit.SECONDS) }
        return if (code == null) null else collected.toList()
    }

    private fun summarize(pair: Pair<String, Int>): String {
        val lines = pair.first.lineSequence().filter { it.isNotBlank() }.toList()
        val last = lines.lastOrNull()?.take(70) ?: "无输出"
        return "$last（退出码 ${pair.second}）"
    }

    /** 执行短命令并取回 (输出, 退出码)。 */
    private fun runShort(cmd: List<String>, timeoutSec: Long = 8): Pair<String, Int> = runCatching {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        val done = process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) process.destroy()
        text to process.exitValue()
    }.getOrDefault("" to -1)

    /** 只读缓存，避免在 UI 线程起进程。 */
    fun cachedRoot(): RootProbe? = cachedProbe

    fun resetProbe() {
        cachedProbe = null
    }

    /**
     * 把脚本暂存为「纯 ASCII 路径 + LF 换行 + 正确 shebang」的可执行文件。
     * 1. 避开 su 对非 ASCII（中文文件名）参数的转码问题；
     * 2. 避开 /data/user/0/... 符号链接在 root 命名空间下的解析差异；
     * 3. 修掉 Windows 换行符（\r\n 会让 sh 报 not found）和 #!/bin/bash 这类不存在的解释器。
     */
    fun stageScript(source: File, id: String): File {
        val stageDir = (ensureStageDir() ?: File(source.parentFile, ".stage")).apply { mkdirs() }
        val target = File(stageDir, asciiName(id) + ".sh")
        target.writeText(normalize(source.readText()))
        runCatching { target.setExecutable(true, false) }
        runCatching { target.setReadable(true, false) }
        return target
    }

    /**
     * 把脚本压缩为 gzip 版本，供「先解压再执行」的 su 使用。
     * 这类 su 要求 -c 传入的文件是 gzip 格式，明文脚本会报 Cannot decompress。
     */
    fun gzip(source: File): File? = runCatching {
        val dir = ensureStageDir() ?: source.parentFile ?: return null
        val target = File(dir, source.nameWithoutExtension + ".sh.gz")
        java.util.zip.GZIPOutputStream(target.outputStream()).use { gz ->
            source.inputStream().use { it.copyTo(gz) }
        }
        target.setReadable(true, false)
        target
    }.getOrNull()

    /** 取规范绝对路径，把 /data/user/0/... 归一为 /data/data/... */
    fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    fun singleQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    private fun normalize(text: String): String {
        val unified = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = unified.split("\n").toMutableList()
        if (lines.isNotEmpty() && lines[0].startsWith("#!")) {
            val interp = lines[0].removePrefix("#!").trim()
            val valid = interp == "/system/bin/sh" || interp == "/bin/sh" ||
                interp == "sh" || interp == "/system/bin/mksh"
            if (!valid) lines[0] = "#!/system/bin/sh"
        }
        return lines.joinToString("\n")
    }

    private fun asciiName(id: String): String {
        val ascii = id.filter { it.code in 32..126 }.trim()
        val safe = if (ascii.isBlank()) {
            "s" + Integer.toHexString(id.hashCode())
        } else {
            ascii.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        }
        return safe.take(48)
    }

    /** 执行一条命令并取回合并输出与退出码，带超时。 */
    private fun runCommand(plan: CommandPlan): Pair<String, Int> = runCatching {
        val process = ProcessBuilder(plan.argv)
            .redirectErrorStream(true)
            .start()
        plan.stdin?.let { text ->
            runCatching {
                process.outputStream.bufferedWriter().use { w ->
                    w.write(text)
                    w.flush()
                }
            }
        }
        val text = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(8, TimeUnit.SECONDS)
        if (!finished) process.destroy()
        text to process.exitValue()
    }.getOrDefault("" to -1)
}
