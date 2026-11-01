package com.shrunner.app.shell

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ShellEvent {
    data class Line(val text: String, val isError: Boolean) : ShellEvent
    data class Exit(val code: Int) : ShellEvent
}

/**
 * Runs a script with the system shell (`/system/bin/sh <file>`), streaming stdout/stderr line by line.
 * Cancelling the collector kills the process (and its descendants on API 33+).
 * The script runs with this app's UID: no root unless the device is rooted and the script calls `su`.
 */
@Singleton
class ShellExecutor @Inject constructor(@ApplicationContext private val context: Context) {

    fun run(script: File, workDir: File): Flow<ShellEvent> = channelFlow {
        workDir.mkdirs()
        val builder = ProcessBuilder("/system/bin/sh", script.absolutePath).directory(workDir)
        builder.environment().apply {
            put("HOME", workDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }
        val process = builder.start()
        try {
            process.outputStream.close() // no interactive stdin yet
            val readers = listOf(false to process.inputStream, true to process.errorStream).map { (isError, stream) ->
                launch(Dispatchers.IO) {
                    stream.bufferedReader().use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            send(ShellEvent.Line(line, isError))
                        }
                    }
                }
            }
            val code = runInterruptible(Dispatchers.IO) { process.waitFor() }
            // A backgrounded child (`sleep 100 &`) can keep the pipes open; don't wait forever.
            withTimeoutOrNull(1_500) { readers.joinAll() }
            send(ShellEvent.Exit(code))
        } finally {
            kill(process)
        }
    }

    private fun kill(process: Process) {
        if (Build.VERSION.SDK_INT >= 33) {
            destroyDescendants(process)
        }
        process.destroyForcibly()
    }
}

/** Android 上没有 JDK 9 的 ProcessHandle API，这里用反射尽力杀掉子进程。 */
private fun destroyDescendants(process: Process) {
    runCatching {
        val handle = process.javaClass.getMethod("toHandle").invoke(process)
        val descendants = handle.javaClass.getMethod("descendants").invoke(handle)
        (descendants as? java.util.stream.Stream<*>)?.forEach { child ->
            runCatching { child.javaClass.getMethod("destroyForcibly").invoke(child) }
        }
    }
}
