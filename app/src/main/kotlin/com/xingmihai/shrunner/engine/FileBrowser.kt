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
        val session = su?.let { runCatching { RootSession.open(it) }.getOrNull() }
        val result = if (session != null) {
            try {
                listViaRoot(session, dir).takeIf { it.isNotEmpty() } ?: listViaJava(dir)
            } finally {
                runCatching { session.close() }
            }
        } else {
            listViaJava(dir)
        }
        result
            .asSequence()
            // 同一路径只保留一条：不同 ROM 的 ls 输出可能重复打印同一项，
            // 重复项会让 LazyColumn 的 key 撞车并直接崩溃
            .distinctBy { it.path }
            .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
            .toList()
    }

    /**
     * root 下列目录。
     *
     * 用 `ls -1p`：每行只有一个名字，目录以 / 结尾。
     * 不用 `ls -la` —— 那样要按空格切字段取第 8 段当名字，
     * 各家 ROM 的 ls 输出格式不一样（有的带秒、有的带 SELinux 标签、
     * 有的直接打绝对路径），取错就会拼出 "/sdcard//sdcard" 这种脏数据。
     */
    private suspend fun listViaRoot(session: RootSession, dir: String): List<Entry> {
        val lines = mutableListOf<String>()
        session.exec("ls -1p " + ShellEnv.singleQuote(dir), 8000) { line -> lines += line }

        val entries = mutableListOf<Entry>()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("total")) continue
            if (t == "." || t == "..") continue

            // -p 会给目录名加尾斜杠，据此判定类型
            var name = t
            var isDir = false
            if (name.endsWith("/")) {
                isDir = true
                name = name.trimEnd('/')
            }
            // 名字里不该再有斜杠（含绝对路径形式的脏数据），直接丢弃
            if (name.isEmpty() || name == "." || name == "..") continue
            if (name.contains("/")) continue

            val path = joinPath(dir, name)
            entries += Entry(name, path, isDir, sizeOf(path, isDir))
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

    /** 拼接路径并去掉多余斜杠，避免出现 /a//b 这类脏路径。 */
    private fun joinPath(dir: String, name: String): String {
        val base = if (dir.length > 1) dir.trimEnd('/') else dir
        val leaf = name.trimStart('/')
        return if (base.isEmpty() || base == "/") "/$leaf" else "$base/$leaf"
    }

    /** 大小：应用读得到就用 Java，读不到就按目录/未知处理，不强求。 */
    private fun sizeOf(path: String, isDir: Boolean): Long {
        if (isDir) return 0L
        return runCatching { File(path).length() }.getOrDefault(0L)
    }

    /** 常用起点：内存卡、下载目录、应用私有目录。 */
    fun roots(): List<String> = listOf(
        "/sdcard",
        "/sdcard/Download",
        "/data/local/tmp",
        "/data/data"
    )
}
