package com.xingmihai.shrunner.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * root 下的文件复制。
 *
 * 背景：应用自身没有存储权限，用 Java File API 读 /sdcard 等路径会被拒绝，
 * 但 root shell 能读任意路径。这里通过 `cat` 重定向完成复制，
 * 复制后修正权限，保证应用后续能读写。
 */
object ShellCopy {

    /**
     * 把 src 复制为 dst。优先直接用 Java 读（快且无需 root），
     * 失败则改用 root shell。返回失败原因（成功返回 null）。
     */
    suspend fun copy(src: String, dst: String): String? = withContext(Dispatchers.IO) {
        val source = File(src)
        val target = File(dst)

        if (!source.exists()) return@withContext "源文件不存在"

        // 1) 应用自己能读就直接读
        val direct = runCatching {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.length() == source.length()
        }.getOrDefault(false)
        if (direct) return@withContext null

        // 2) root shell 复制（应用无存储权限时的主要路径）
        val su = ShellEnv.suPath() ?: return@withContext "无读取权限，且未找到 su"
        val session = RootSession.open(su)
            ?: return@withContext "无读取权限，且无法建立 root shell"

        try {
            val srcQ = ShellEnv.singleQuote(source.absolutePath)
            val dstQ = ShellEnv.singleQuote(target.absolutePath)
            // 复制后 chmod，否则文件归 root 所有，应用之后改不了
            val code = session.exec("cat $srcQ > $dstQ && chmod 666 $dstQ", 120_000)
            if (code != 0) return@withContext "root 复制失败（退出码 $code）"
            if (!target.exists()) return@withContext "复制后目标文件不存在"
            if (target.length() != source.length()) {
                return@withContext "复制不完整（${target.length()} / ${source.length()} 字节）"
            }
            return@withContext null
        } finally {
            session.close()
        }
    }
}
