package com.shrunner.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

data class Script(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val favorite: Boolean,
)

enum class RunStatus { COMPLETED, FAILED, STOPPED, DECLINED }

/** One dangerous-looking line found by [com.shrunner.app.shell.DangerDetector]. */
data class DangerHit(
    val ruleId: String,
    val label: String,
    val lineNumber: Int,
    val line: String,
    /** sha256(ruleId + trimmed line): approvals are tied to the exact line, not the script name. */
    val fingerprint: String,
)

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scriptName: String,
    val startedAt: Long,
    val durationMs: Long,
    val exitCode: Int?,
    val status: RunStatus,
    /** "; "-separated labels of flagged commands, empty if none. */
    val dangerLabels: String,
    /** Last few KB of output, for the audit trail. */
    val outputTail: String,
)
