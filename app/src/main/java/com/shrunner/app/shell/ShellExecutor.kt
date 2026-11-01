package com.shrunner.app.shell

import android.content.Context
import android.system.Os
import android.system.OsConstants
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
 * Runs a script with the system shell, streaming stdout/stderr line by line.
 * Cancelling the collector kills the script and every process it started (found via /proc).
 * The script runs with this app's UID: no root unless the device is rooted and the script calls `su`.
 *
 * Android has no java.lang.ProcessHandle, so the shell first writes its own PID to a file and then
 * `exec`s the script shell, which keeps that PID. On Stop we walk /proc for its descendants.
 */
@Singleton
class ShellExecutor @Inject constructor(@ApplicationContext private val context: Context) {

    fun run(script: File, workDir: File): Flow<ShellEvent> = channelFlow {
        workDir.mkdirs()
        val pidFile = File(context.cacheDir, "run/${System.nanoTime()}.pid").also { it.parentFile?.mkdirs() }
        // $1 = pid file, $2 = script. `exec` keeps the PID that was just written.
        val builder = ProcessBuilder(
            "/system/bin/sh", "-c", "echo \$\$ > \"\$1\"; exec /system/bin/sh \"\$2\"",
            "sh", pidFile.absolutePath, script.absolutePath,
        ).directory(workDir)
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
            kill(process, pidFile)
        }
    }

    private fun kill(process: Process, pidFile: File) {
        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
        if (pid != null) killTree(pid)
        process.destroyForcibly()
        pidFile.delete()
    }

    /** SIGKILLs [rootPid] and all its descendants, discovered by parent PID in /proc. */
    private fun killTree(rootPid: Int) {
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val ppid = runCatching { parentPid(File(dir, "stat").readText()) }.getOrNull() ?: return@forEach
            children.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val victims = ArrayList<Int>()
        val queue = ArrayDeque<Int>().apply { add(rootPid) }
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            victims += p
            children[p]?.let { queue.addAll(it) }
        }
        victims.forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
    }

    /** /proc/<pid>/stat is "pid (comm) state ppid ..."; comm may contain spaces or ')', so cut at the last ')'. */
    private fun parentPid(stat: String): Int =
        stat.substring(stat.lastIndexOf(')') + 2).split(' ')[1].toInt()
}
