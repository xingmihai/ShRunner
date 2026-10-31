package com.xingmihai.shrunner.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    /** 未成行的部分输出回调：脚本提问常不带换行，靠它才能及时识别。 */
    @Volatile
    private var onPartial: ((String) -> Unit)? = null

    @Volatile
    private var currentDeferred: CompletableDeferred<Int>? = null

    private val seq = AtomicInteger(0)
    private val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(process.outputStream))

    init { startReader() }

    private fun startReader() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = StringBuilder()
                    val chars = CharArray(1)
                    while (alive) {
                        val n = reader.read(chars)
                        if (n <= 0) break
                        val ch = chars[0]
                        if (ch == '\n') {
                            val line = buf.toString()
                            buf.setLength(0)
                            emitLine(line)
                        } else {
                            buf.append(ch)
                            // 未成行的部分也要回调，否则「是否继续执行? (y/n)」这类
                            // 不带换行的提问永远识别不到，脚本会一直等到超时
                            onPartial?.invoke(buf.toString())
                        }
                    }
                }
            }
        }
    }

    private fun emitLine(line: String) {
        val marker = currentMarker
        if (marker != null && line.contains(marker)) {
            val code = line.substringAfter(marker).trim().removePrefix("_").toIntOrNull() ?: 0
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

    suspend fun exec(
        command: String,
        timeoutMs: Long = 60_000,
        onLine: (String) -> Unit = {},
        onPartial: (String) -> Unit = {}
    ): Int? {
        if (!alive) return null
        val marker = "SHRUNNER_${seq.incrementAndGet()}_MARKER"
        val deferred = CompletableDeferred<Int>()
        currentMarker = marker
        currentDeferred = deferred
        this.onLine = onLine
        this.onPartial = onPartial
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
            this.onPartial = null
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
            // 用 join 等协程结束，不用忙等（忙等既耗 CPU 又不精确）
            runBlocking(Dispatchers.IO) {
                code = session.exec("echo SHRUNNER_SESSION_OK", 6000) { lines += it }
            }
            val ok = code != null && lines.any { it.contains("SHRUNNER_SESSION_OK") }
            if (!ok) {
                session.close()
                null
            } else session
        }.getOrNull()
    }
}
