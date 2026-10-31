package com.xingmihai.shrunner.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地文件浏览。root 下通过持久 shell 的 ls 列目录（能看到全部路径），
 * 非 root 时退回 Java File API（仅应用私有目录等可访问位置）。
 */
object FileBrowser {

    data class Entry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long
    )

    suspend fun list(dir: String): List<Entry> = withContext(Dispatchers.IO) {
        val su = ShellEnv.suPath()
        val viaRoot = su?.let { RootSession.open(it) }
        val result = if (viaRoot != null) {
            try {
                listViaRoot(viaRoot, dir).takeIf { it.isNotEmpty() } ?: listViaJava(dir)
            } finally {
                viaRoot.close()
            }
        } else {
            listViaJava(dir)
        }
        result.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    private suspend fun listViaRoot(session: RootSession, dir: String): List<Entry> {
        val lines = mutableListOf<String>()
        session.exec("ls -la " + ShellEnv.singleQuote(dir), 8000) { line -> lines += line }
        val entries = mutableListOf<Entry>()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("total")) continue
            val parts = t.split(Regex("\\s+"), limit = 8)
            if (parts.size < 8) continue
            val name = parts[7]
            if (name == "." || name == "..") continue
            val isDir = parts[0].startsWith("d")
            val size = parts[4].toLongOrNull() ?: 0L
            val path = if (dir.endsWith("/")) dir + name else "$dir/$name"
            entries += Entry(name, path, isDir, size)
        }
        return entries
    }

    private fun listViaJava(dir: String): List<Entry> {
        val file = File(dir)
        val items = runCatching { file.listFiles() }.getOrNull() ?: return emptyList()
        return items.map {
            Entry(it.name, it.absolutePath, it.isDirectory, it.length())
        }
    }

    /** 常用起点：内存卡、下载目录、应用私有目录。 */
    fun roots(): List<String> = listOf(
        "/sdcard",
        "/sdcard/Download",
        "/data/local/tmp",
        "/data/data"
    )
}
