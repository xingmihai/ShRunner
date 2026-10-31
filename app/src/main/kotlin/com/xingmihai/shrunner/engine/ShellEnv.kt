package com.xingmihai.shrunner.engine

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
        /** `su -c "sh script args"` —— 最常见 */
        SU_C_SH("su -c \"sh 脚本\""),

        /** `su 0 "sh script args"` —— 部分 su 只认 uid 形式 */
        SU_0_SH("su 0 \"sh 脚本\""),

        /** `su -c "script args"` —— 直接跑脚本，靠 shebang */
        SU_C_DIRECT("su -c \"脚本\""),

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
            // 探针放在 /data/local/tmp，root 一定可读可执行，排除权限干扰
            val dir = File("/data/local/tmp").takeIf { it.isDirectory }
                ?: File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
            val f = File(dir, "shrunner_probe.sh")
            f.writeText("#!/system/bin/sh\necho SHRUNNER_PROBE_OK\nid\nexit 0\n")
            f.setExecutable(true, false)
            f.setReadable(true, false)
            f
        }.getOrNull() ?: return RootProbe(false, null, null, "无法创建探针脚本")

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
            SuMode.SU_C_SH -> {
                val payload = "$shell $quotedScript$quotedArgs"
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
        val stageDir = File(source.parentFile, ".stage").apply { mkdirs() }
        val target = File(stageDir, asciiName(id) + ".sh")
        target.writeText(normalize(source.readText()))
        runCatching { target.setExecutable(true, false) }
        runCatching { target.setReadable(true, false) }
        return target
    }

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
