package com.xingmihai.shrunner.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 持久 root shell 会话。
 *
 * 适用设备：su 无法一次性接受整条大命令（部分实现的 su 会对参数做解压/解码预处理，
 * 遇到大脚本就报 gzip / Cannot decompress 并退出 127），但仍然能进入交互 shell。
 * 这里改成：只启动一次 su，之后**逐条短命令**写进去，用标记行判断每条是否执行完。
 */
class RootSession private constructor(
    private val process: Process,
    private val suPath: String
) {

    @Volatile
    private var alive = true

    @Volatile
    private var currentMarker: String? = null

    @Volatile
    private var currentLines: MutableList<String>? = null

    @Volatile
    private var currentDeferred: CompletableDeferred<List<String>>? = null

    private val seq = AtomicInteger(0)
    private val writer: BufferedWriter = java.io.BufferedWriter(OutputStreamWriter(process.outputStream))

    init { startReader() }

    private fun startReader() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (alive) {
                        val line = reader.readLine() ?: break
                        val marker = currentMarker
                        if (marker != null && line.contains(marker)) {
                            val done = currentLines ?: mutableListOf()
                            val deferred = currentDeferred
                            currentMarker = null
                            currentLines = null
                            currentDeferred = null
                            deferred?.complete(done)
                        } else {
                            currentLines?.add(line)
                        }
                    }
                }
            }
        }
    }

    /** 执行一条命令并返回它的输出行；超时返回 null。 */
    suspend fun exec(command: String, timeoutMs: Long = 10_000): List<String>? {
        if (!alive) return null
        val marker = "SHRUNNER_${seq.incrementAndGet()}_END"
        val deferred = CompletableDeferred<List<String>>()
        currentLines = mutableListOf()
        currentMarker = marker
        currentDeferred = deferred
        return try {
            runCatching {
                writer.write(command)
                writer.write("\necho $marker\n")
                writer.flush()
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            currentMarker = null
            currentLines = null
            currentDeferred = null
        }
    }

    fun close() {
        alive = false
        runCatching {
            writer.write("exit\n")
            writer.flush()
            writer.close()
        }
        runCatching { process.destroy() }
    }

    fun info(): String = "$suPath 持久会话（逐条短命令）"

    companion object {
        /** 尝试打开持久 root shell，用一条极短命令验证可用；不可用返回 null。 */
        fun open(su: String): RootSession? = runCatching {
            val process = ProcessBuilder(su)
                .redirectErrorStream(true)
                .start()
            process.outputStream?.let { /* 保持打开，由会话负责写入 */ }
            val session = RootSession(process, su)
            var ok: List<String>? = null
            val job = CoroutineScope(Dispatchers.IO).launch {
                ok = session.exec("echo SHRUNNER_SESSION_OK", 6000)
            }
            val start = System.currentTimeMillis()
            while (job.isActive && System.currentTimeMillis() - start < 8000) {
                Thread.sleep(50)
            }
            val lines = ok
            if (lines == null || !lines.any { it.contains("SHRUNNER_SESSION_OK") }) {
                session.close()
                null
            } else {
                session
            }
        }.getOrNull()
    }
}
