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
 * 只启动一次 su 进入交互 shell，之后把命令写进 shell 的 stdin。
 * 脚本路径不经过 su 的参数解析，因此没有 `su -c` 的体积上限
 * （大脚本走 -c 会报 gzip / Cannot decompress）。
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
    private var onLine: ((String) -> Unit)? = null

    @Volatile
    private var currentDeferred: CompletableDeferred<Int>? = null

    private val seq = AtomicInteger(0)
    private val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(process.outputStream))

    init { startReader() }

    private fun startReader() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (alive) {
                        val line = reader.readLine() ?: break
                        val marker = currentMarker
                        if (marker != null && line.contains(marker)) {
                            val code = line.substringAfter(marker).trim()
                                .removePrefix("_").toIntOrNull() ?: 0
                            val deferred = currentDeferred
                            val callback = onLine
                            currentMarker = null
                            currentDeferred = null
                            onLine = null
                            callback?.invoke("")
                            deferred?.complete(code)
                        } else {
                            onLine?.invoke(line)
                        }
                    }
                }
            }
        }
    }

    /** 执行一条命令，输出经 onLine 实时回调，返回退出码；超时返回 null。 */
    suspend fun exec(
        command: String,
        timeoutMs: Long = 60_000,
        onLine: (String) -> Unit = {}
    ): Int? {
        if (!alive) return null
        val marker = "SHRUNNER_${seq.incrementAndGet()}_MARKER"
        val deferred = CompletableDeferred<Int>()
        currentMarker = marker
        currentDeferred = deferred
        this.onLine = onLine
        return try {
            runCatching {
                writer.write(command)
                writer.write("\n")
                writer.write("echo ${marker}_\$?\n")
                writer.flush()
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            currentMarker = null
            currentDeferred = null
            this.onLine = null
        }
    }

    /** 把用户输入写进正在运行的脚本，返回是否送达。 */
    fun sendInput(text: String): Boolean = runCatching {
        writer.write(text)
        if (!text.endsWith("\n")) writer.write("\n")
        writer.flush()
        true
    }.getOrDefault(false)

    fun close() {
        alive = false
        runCatching {
            writer.write("exit\n")
            writer.flush()
            writer.close()
        }
        runCatching { process.destroy() }
    }

    fun info(): String = "$suPath 持久会话"

    companion object {
        /** 打开持久 root shell，用一条极短命令验证可用；不可用返回 null。 */
        fun open(su: String): RootSession? = runCatching {
            val process = ProcessBuilder(su)
                .redirectErrorStream(true)
                .start()
            val session = RootSession(process, su)
            val lines = mutableListOf<String>()
            var code: Int? = null
            val job = CoroutineScope(Dispatchers.IO).launch {
                code = session.exec("echo SHRUNNER_SESSION_OK", 6000) { lines += it }
            }
            val start = System.currentTimeMillis()
            while (job.isActive && System.currentTimeMillis() - start < 8000) {
                Thread.sleep(50)
            }
            val ok = code != null && lines.any { it.contains("SHRUNNER_SESSION_OK") }
            if (!ok) {
                session.close()
                null
            } else session
        }.getOrNull()
    }
}
