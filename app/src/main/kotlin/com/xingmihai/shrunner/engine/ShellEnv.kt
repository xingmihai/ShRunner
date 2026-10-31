package com.xingmihai.shrunner.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/** 设备 shell 环境探测、执行环境构造与脚本暂存。 */
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
        "/data/adb/magisk/busybox"
    )

    /** root 执行方式。SESSION 为首选：脚本路径不经 su 参数解析，无体积上限。 */
    enum class SuMode(val label: String) {
        SESSION("持久 root shell"),
        SU_C_SH("su -c \"sh 脚本\""),
        SU_0_SH("su 0 \"sh 脚本\""),
        SU_STDIN("su ← stdin")
    }

    data class RootProbe(
        val ok: Boolean,
        val suPath: String?,
        val mode: SuMode?,
        val detail: String
    )

    @Volatile
    private var cachedProbe: RootProbe? = null

    @Volatile
    var preferredMode: SuMode? = null

    @Volatile
    private var stageDir: File? = null

    fun init(context: Context) {
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

    fun toolboxSummary(): String = toolboxPath()?.substringAfterLast('/') ?: "无"

    fun defaultPath(): String = listOf(
        "/system/bin", "/system/xbin", "/vendor/bin", "/sbin", "/sbin/bin",
        "/product/bin", "/apex/com.android.runtime/bin", "/apex/com.android.art/bin",
        "/odm/bin", "/system_ext/bin"
    ).joinToString(":")

    /** 执行脚本时的基础环境：缺了这些，脚本里的原生程序容易取不到值而崩溃。 */
    fun baseEnv(): Map<String, String> {
        val env = linkedMapOf<String, String>()
        env["PATH"] = defaultPath()
        env["HOME"] = "/data/local/tmp"
        env["TMPDIR"] = "/data/local/tmp"
        env["TEMP"] = "/data/local/tmp"
        env["TMP"] = "/data/local/tmp"
        env["TERM"] = "xterm-256color"
        env["SHELL"] = shellPath()
        env["USER"] = "root"
        env["LOGNAME"] = "root"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["PWD"] = "/"
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_ROOT"] = "/system"
        env["ANDROID_STORAGE"] = "/storage"
        env["EXTERNAL_STORAGE"] = "/sdcard"
        env["LD_LIBRARY_PATH"] = "/system/lib64:/vendor/lib64:/apex/com.android.runtime/lib64"
        return env
    }

    /** 供持久 root shell 首行执行的 export 语句。 */
    fun envPrologue(): String = baseEnv().entries.joinToString(" ") { (k, v) ->
        "export $k=" + singleQuote(v)
    }

    /**
     * 探测 root 是否真的可用。
     * 优先验证持久 shell（大脚本唯一可行的路径），再退回 `su -c id`。
     */
    fun probeRoot(force: Boolean = false): RootProbe {
        if (!force) cachedProbe?.let { return it }
        cachedProbe = probeRootInternal()
        return cachedProbe!!
    }

    private fun probeRootInternal(): RootProbe {
        val modes = preferredMode?.let { listOf(it) } ?: listOf(SuMode.SESSION, SuMode.SU_C_SH)
        for (su in SU_CANDIDATES) {
            if (!File(su).exists()) continue
            for (mode in modes) {
                val ok = when (mode) {
                    SuMode.SESSION -> runBlocking(Dispatchers.IO) {
                        RootSession.open(su)?.let { session ->
                            var out: String? = null
                            session.exec("id", 6000) { line -> if (line.contains("uid=0")) out = line }
                            session.close()
                            out != null
                        } ?: false
                    }
                    else -> {
                        val r = runShort(listOf(su, "-c", "echo SHRUNNER_OK"))
                        r.first.contains("SHRUNNER_OK")
                    }
                }
                if (ok) return RootProbe(true, su, mode, "已验证")
            }
        }
        val su = suPath()
        return RootProbe(false, su, null, if (su == null) "未找到 su" else "su 无法执行脚本")
    }

    fun cachedRoot(): RootProbe? = cachedProbe

    fun resetProbe() {
        cachedProbe = null
        cachedPty = null
    }

    /** 返回 (输出, 退出码) 的短命令执行，带超时。 */
    private fun runShort(cmd: List<String>, timeoutSec: Long = 8): Pair<String, Int> = runCatching {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        val done = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) process.destroy()
        text to process.exitValue()
    }.getOrDefault("" to -1)

    data class CommandPlan(
        val argv: List<String>,
        val stdin: String?,
        val display: String
    )

    fun buildCommand(
        mode: SuMode,
        su: String,
        shell: String,
        scriptPath: String,
        args: List<String>,
        prepared: String? = null
    ): CommandPlan {
        val quotedArgs = args.joinToString(" ") { singleQuote(it) }
        val payload = prepared ?: "$shell ${singleQuote(scriptPath)}$quotedArgs"
        return when (mode) {
            SuMode.SU_C_SH -> CommandPlan(listOf(su, "-c", payload), null, "$su -c '$payload'")
            SuMode.SU_0_SH -> CommandPlan(listOf(su, "0", payload), null, "$su 0 '$payload'")
            SuMode.SU_STDIN -> CommandPlan(listOf(su), "$payload\nexit\n", "$payload | su")
            SuMode.SESSION -> CommandPlan(emptyList(), null, "持久会话内执行")
        }
    }

    /**
     * 查找能给脚本分配伪终端（PTY）的命令。
     *
     * 很多交互脚本只在真终端下才阻塞等输入：给它管道的话 read 立刻失败，
     * 拿到空值就按「非 y」处理，直接输出「操作已取消」。
     * 终端类 App（如 MT 管理器）能跑通，正是因为它们有 PTY。
     *
     * 这里用 shell 的 `command -v` 全 PATH 探测，比写死几个路径可靠。
     */
    fun ptyCommand(force: Boolean = false): String? {
        if (!force) cachedPty?.let { return it }
        cachedPty = findPtyCommand()
        return cachedPty
    }

    @Volatile
    private var cachedPty: String? = null

    private fun findPtyCommand(): String? {
        val shell = shellPath()

        // 1) 全 PATH 查找
        val viaPath = runShort(listOf(shell, "-c", "command -v script"), 6).first
            .lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") }
        if (viaPath != null) return viaPath

        // 2) 已知位置逐个探测（含 KernelSU / APatch / Magisk 提供的路径）
        // 用 ls 批量过滤存在的路径，避免在 shell 命令里写变量（省掉一层转义）
        val paths = listOf(
            "/system/bin/script", "/system/xbin/script", "/sbin/script",
            "/vendor/bin/script", "/product/bin/script", "/system_ext/bin/script",
            "/data/adb/ksu/bin/script", "/data/adb/ap/bin/script"
        )
        val viaFile = runShort(
            listOf(shell, "-c", "ls -1 " + paths.joinToString(" ") + " 2>/dev/null | head -n 1"),
            6
        ).first.trim().takeIf { it.startsWith("/") }
        if (viaFile != null) return viaFile

        // 3) busybox / toybox 以 applet 形式提供 script
        val boxes = listOf(
            "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox",
            "/data/adb/magisk/busybox", "/data/adb/modules/busybox-ndk/system/bin/busybox",
            "/system/bin/busybox", "/system/xbin/busybox", "/sbin/busybox",
            "/system/bin/toybox", "/system/xbin/toybox"
        )
        val box = runShort(
            listOf(shell, "-c", "ls -1 " + boxes.joinToString(" ") + " 2>/dev/null | head -n 1"),
            6
        ).first.trim().takeIf { it.startsWith("/") }
        if (box != null) {
            val has = runShort(
                listOf(shell, "-c", box + " --list 2>/dev/null | grep -qx script && echo yes"),
                8
            ).first
            if (has.contains("yes")) return box + " script"
            val runnable = runShort(
                listOf(shell, "-c", box + " script --help >/dev/null 2>&1 && echo yes"),
                8
            ).first
            if (runnable.contains("yes")) return box + " script"
        }
        return null
    }

    /** 用 PTY 包装一条命令；返回 null 表示没有可用工具。 */
    fun wrapPty(command: String, pty: String?): String? {
        val tool = pty ?: return null
        return when {
            tool.endsWith("script") -> "$tool -q -e -c " + singleQuote(command) + " /dev/null"
            tool.endsWith("unbuffer") -> "$tool $command"
            else -> null
        }
    }

    /** 该脚本对应的暂存目录，即被执行副本所在位置。 */
    fun stageDirOf(source: File): File =
        (ensureStageDir() ?: File(source.parentFile, ".stage")).apply { mkdirs() }

    fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    fun singleQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /**
     * 是否必须暂存一份副本。
     * 大脚本（10MB+）只要 shebang 正确、路径是 ASCII，就直接用原文件执行，
     * 省掉一次全量拷贝。
     */
    fun needsStaging(file: File, useSession: Boolean): Boolean {
        val header = ByteArray(256)
        val read = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrDefault(-1)
        if (read <= 0) return false
        // shebang 缺失或无效 → 需要暂存修复
        val hasValid = hasValidShebang(header, read)
        if (!hasValid) return true
        // 非 ASCII 路径：仅在不走持久会话时才需要改名（su 对非 ASCII 参数可能转码）
        val asciiPath = file.absolutePath.all { it.code in 32..126 }
        return !asciiPath && !useSession
    }

    /** 暂存副本的目标路径。 */
    fun stageFileFor(id: String): File = File(ensureStageDir() ?: File("/data/local/tmp"), asciiName(id) + ".sh")

    /** 需要暂存时调用：流式拷贝 + 按需修复 shebang，避免整体入内存。 */
    fun stageScript(source: File, id: String): File {
        val dir = stageDirOf(source)
        val target = File(dir, asciiName(id) + ".sh")
        val header = ByteArray(256)
        val read = runCatching { source.inputStream().use { it.read(header) } }.getOrDefault(-1)
        val needFix = read > 0 && !hasValidShebang(header, read)

        source.inputStream().use { input ->
            target.outputStream().use { output ->
                if (!needFix) {
                    input.copyTo(output)
                } else {
                    // 跳过错误 shebang 首行，写入正确首行
                    val first = ByteArray(read)
                    System.arraycopy(header, 0, first, 0, read)
                    val lineEnd = first.indexOf(0x0A.toByte()).let { if (it < 0) read else it + 1 }
                    val rest = ByteArray(maxOf(0, read - lineEnd))
                    System.arraycopy(first, lineEnd, rest, 0, rest.size)
                    output.write("#!/system/bin/sh\n".toByteArray(Charsets.UTF_8))
                    output.write(rest)
                    input.copyTo(output)
                }
            }
        }
        runCatching { target.setExecutable(true, false) }
        runCatching { target.setReadable(true, false) }
        return target
    }

    data class ScriptStat(val sizeBytes: Long, val isBinary: Boolean, val shebang: String)

    /** 轻量体检：只读前 64KB，大小用 length()，10MB 脚本也能瞬间完成。 */
    fun stat(file: File): ScriptStat {
        val size = file.length()
        val head = runCatching {
            val buf = ByteArray(minOf(size, 65536).toInt())
            val n = file.inputStream().use { it.read(buf) }
            if (n <= 0) ByteArray(0) else buf.copyOfRange(0, n)
        }.getOrDefault(ByteArray(0))
        return ScriptStat(
            sizeBytes = size,
            isBinary = looksBinary(head),
            shebang = readShebang(head)
        )
    }

    private fun hasValidShebang(header: ByteArray, len: Int): Boolean {
        if (len < 2) return false
        if (header[0] != 0x23.toByte() || header[1] != 0x21.toByte()) return true  // 无 shebang，交给 sh 默认处理
        val end = header.indexOf(0x0A.toByte()).let { if (it < 0 || it >= len) len else it }
        val interp = String(header, 0, end, Charsets.UTF_8).removePrefix("#!").trim()
        return interp in setOf("/system/bin/sh", "/bin/sh", "sh", "/system/bin/mksh")
    }

    private fun readShebang(head: ByteArray): String {
        if (head.size < 2 || head[0] != 0x23.toByte() || head[1] != 0x21.toByte()) return "无"
        val end = head.indexOf(0x0A.toByte()).let { if (it < 0) head.size.coerceAtMost(60) else it }
        return String(head, 0, end, Charsets.UTF_8).trim().take(60)
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var suspicious = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0x00) return true
            if (v < 0x09 || (v > 0x0D && v < 0x20)) suspicious++
        }
        return suspicious > bytes.size / 100
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
}
