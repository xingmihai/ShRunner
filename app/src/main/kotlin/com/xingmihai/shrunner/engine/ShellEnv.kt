package com.xingmihai.shrunner.engine

import java.io.File

/** 设备 shell 环境探测：sh 路径、root 可用性、busybox/toybox 情况、脚本暂存。 */
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

    /** 常见 su 调用形式：不同 root 方案（Magisk / KernelSU / APatch / 国内一键 root）支持的写法不同。 */
    private val SU_FORMS = listOf("su -c", "su 0")

    data class RootProbe(
        val ok: Boolean,          // 是否真的拿到了 uid=0
        val suPath: String?,
        val form: String,         // "su -c" 或 "su 0"
        val detail: String        // 探测输出，便于排查
    )

    @Volatile
    private var cachedProbe: RootProbe? = null

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
     * 真正探测 root：只有 `su ... id` 输出中包含 uid=0 才算可用。
     * 只判断 su 文件是否存在会被国内一键 root 的占位/损坏 su 骗过。
     */
    fun probeRoot(force: Boolean = false): RootProbe {
        if (!force) cachedProbe?.let { return it }
        var fallback = RootProbe(false, null, "su -c", "未找到 su")
        loop@ for (su in SU_CANDIDATES) {
            if (!File(su).exists()) continue
            for (form in SU_FORMS) {
                val cmd = if (form == "su -c") listOf(su, "-c", "id") else listOf(su, "0", "id")
                val out = runQuiet(cmd)
                if (out.contains("uid=0")) {
                    val hit = RootProbe(true, su, form, out.lineSequence().firstOrNull()?.trim().orEmpty())
                    cachedProbe = hit
                    return hit
                }
                if (fallback.suPath == null) {
                    fallback = RootProbe(false, su, form, out.lineSequence().firstOrNull()?.trim()?.ifBlank { "无输出" } ?: "无输出")
                }
            }
        }
        cachedProbe = fallback
        return fallback
    }

    /** 只读缓存，避免在 UI 线程触发进程。 */
    fun cachedRoot(): RootProbe? = cachedProbe

    fun resetProbe() {
        cachedProbe = null
    }

    /**
     * 把脚本暂存为「纯 ASCII 路径 + LF 换行 + 正确 shebang」的可执行文件。
     *
     * 目的：
     * 1. 避开 su 对非 ASCII（中文文件名）参数的转码问题；
     * 2. 避开 /data/user/0/... 这类符号链接在 root 命名空间下的解析差异；
     * 3. 顺手修掉 Windows 换行符（\r\n 会让 sh 报 not found）和 #!/bin/bash 这类不存在的解释器。
     */
    fun stageScript(source: File, id: String): File {
        val stageDir = File(source.parentFile, ".stage").apply { mkdirs() }
        val target = File(stageDir, asciiName(id) + ".sh")
        val text = normalize(source.readText())
        target.writeText(text)
        runCatching { target.setExecutable(true, false) }
        runCatching { target.setReadable(true, false) }
        return target
    }

    /** 取规范绝对路径，把 /data/user/0/... 归一为 /data/data/... */
    fun canonical(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /** 供 su -c 使用的单串命令构造，做单引号转义。 */
    fun buildShellCommand(shell: String, scriptPath: String, args: List<String>): String {
        val parts = mutableListOf(shell, scriptPath)
        parts.addAll(args)
        return parts.joinToString(" ") { singleQuote(it) }
    }

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

    private fun runQuiet(cmd: List<String>): String = runCatching {
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val ok = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        val text = process.inputStream.bufferedReader().readText()
        if (!ok) process.destroy()
        text
    }.getOrDefault("")
}
