package com.xingmihai.shrunner.engine

import java.io.File

/** 设备 shell 环境探测：sh 路径、root 可用性、busybox/toybox 情况。 */
object ShellEnv {

    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
        "/debug_ramdisk/su"
    )

    private val TOOLBOX_CANDIDATES = listOf(
        "/system/bin/toybox",
        "/system/bin/busybox",
        "/system/xbin/busybox",
        "/sbin/busybox",
        "/data/adb/magisk/busybox"
    )

    fun shellPath(): String {
        val candidates = listOf("/system/bin/sh", "/bin/sh", "/system/bin/mksh")
        return candidates.firstOrNull { File(it).exists() } ?: "/system/bin/sh"
    }

    fun suPath(): String? = SU_CANDIDATES.firstOrNull { File(it).exists() }

    fun isRootAvailable(): Boolean = suPath() != null

    fun toolboxPath(): String? = TOOLBOX_CANDIDATES.firstOrNull { File(it).exists() }

    fun toolboxSummary(): String = toolboxPath() ?: "无（命令集以系统 toybox 为准）"

    fun defaultPath(): String {
        val base = "/system/bin:/system/xbin:/vendor/bin:/sbin:/sbin/bin:/product/bin:/apex/com.android.runtime/bin"
        val extra = listOfNotNull(toolboxPath()).joinToString(":") { File(it).parent ?: "" }
        return if (extra.isBlank()) base else "$base:$extra"
    }
}
