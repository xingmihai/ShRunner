package com.shrunner.app.ui.run

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.shrunner.app.data.AppPreferences
import com.shrunner.app.data.DangerHit
import com.shrunner.app.data.HistoryEntry
import com.shrunner.app.data.HistoryRepository
import com.shrunner.app.data.RunStatus
import com.shrunner.app.data.ScriptRepository
import com.shrunner.app.shell.DangerDetector
import com.shrunner.app.shell.ShellEvent
import com.shrunner.app.shell.ShellExecutor
import com.shrunner.app.ui.common.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class OutLine(val text: String, val isError: Boolean)

sealed interface RunPhase {
    data object Loading : RunPhase
    data class Error(val message: String) : RunPhase
    data object Ready : RunPhase
    data object Running : RunPhase
    data class Finished(val status: RunStatus, val exitCode: Int?) : RunPhase
}

@HiltViewModel
class RunViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: ScriptRepository,
    private val executor: ShellExecutor,
    private val prefs: AppPreferences,
    private val history: HistoryRepository,
) : BaseViewModel() {

    val name: String = checkNotNull(handle.get<String>("name"))

    var phase by mutableStateOf<RunPhase>(RunPhase.Loading)
        private set
    var lines by mutableStateOf<List<OutLine>>(emptyList())
        private set
    /** Non-null while the "dangerous commands" dialog is showing. */
    var pendingConfirm by mutableStateOf<List<DangerHit>?>(null)
        private set

    private var snapshotText = ""
    private var allHits: List<DangerHit> = emptyList()
    private var job: Job? = null

    init { prepare() }

    fun prepare() {
        viewModelScope.launch {
            phase = RunPhase.Loading
            phase = try {
                repo.read(name)
                RunPhase.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RunPhase.Error(e.message ?: "Couldn't open the script.")
            }
        }
    }

    /** Re-reads and re-scans the script on every run, so edits can't dodge the check. */
    fun requestRun() {
        if (job?.isActive == true) return
        launchCatching {
            val text = repo.read(name)
            val hits = DangerDetector.scan(text)
            val approved = prefs.approved()
            val unapproved = hits.filter { it.fingerprint !in approved }
            snapshotText = text
            allHits = hits
            if (unapproved.isEmpty()) start() else pendingConfirm = unapproved
        }
    }

    fun confirm() {
        val hits = pendingConfirm ?: return
        pendingConfirm = null
        launchCatching {
            prefs.approve(hits.map { it.fingerprint }.toSet())
            start()
        }
    }

    fun decline() {
        val hits = pendingConfirm ?: return
        pendingConfirm = null
        launchCatching {
            history.record(
                HistoryEntry(
                    scriptName = name, startedAt = System.currentTimeMillis(), durationMs = 0,
                    exitCode = null, status = RunStatus.DECLINED,
                    dangerLabels = labels(hits), outputTail = "",
                ),
            )
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun outputText(): String = lines.joinToString("\n") { it.text }

    private fun start() {
        val text = snapshotText
        val flaggedLabels = labels(allHits)
        lines = emptyList()
        phase = RunPhase.Running
        job = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val buffer = ArrayDeque<OutLine>()
            var dirty = false
            var status = RunStatus.FAILED
            var exit: Int? = null
            var file: File? = null
            // Publish output at most every 50 ms so chatty scripts don't flood Compose.
            val ticker = launch {
                while (isActive) {
                    delay(50)
                    if (dirty) {
                        lines = buffer.toList()
                        dirty = false
                    }
                }
            }
            try {
                file = repo.snapshot(name, text) // run exactly what was scanned
                executor.run(file, repo.workDir).collect { event ->
                    when (event) {
                        is ShellEvent.Line -> {
                            buffer.addLast(OutLine(event.text, event.isError))
                            if (buffer.size > MAX_LINES) buffer.removeFirst()
                            dirty = true
                        }
                        is ShellEvent.Exit -> exit = event.code
                    }
                }
                status = if (exit == 0) RunStatus.COMPLETED else RunStatus.FAILED
            } catch (e: CancellationException) {
                status = RunStatus.STOPPED
                throw e
            } catch (e: Exception) {
                buffer.addLast(OutLine("ShRunner: ${e.message ?: e.javaClass.simpleName}", true))
            } finally {
                ticker.cancel()
                withContext(NonCancellable) {
                    lines = buffer.toList()
                    file?.delete()
                    history.record(
                        HistoryEntry(
                            scriptName = name, startedAt = startedAt,
                            durationMs = System.currentTimeMillis() - startedAt,
                            exitCode = exit, status = status, dangerLabels = flaggedLabels,
                            outputTail = buffer.joinToString("\n") { it.text }.takeLast(TAIL_CHARS),
                        ),
                    )
                    phase = RunPhase.Finished(status, exit)
                }
            }
        }
    }

    private fun labels(hits: List<DangerHit>) = hits.map { it.label }.distinct().joinToString("; ")

    private companion object {
        const val MAX_LINES = 5_000
        const val TAIL_CHARS = 4_000
    }
}
