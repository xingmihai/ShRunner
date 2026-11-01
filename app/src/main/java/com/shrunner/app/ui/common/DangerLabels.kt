package com.shrunner.app.ui.common

import androidx.annotation.StringRes
import com.shrunner.app.R

/** Rule ids are stored in history; labels are resolved at display time so they follow the language setting. */
@StringRes
fun dangerLabelRes(ruleId: String): Int = when (ruleId) {
    "rm-recursive" -> R.string.danger_rm_recursive
    "find-delete" -> R.string.danger_find_delete
    "su" -> R.string.danger_su
    "mount" -> R.string.danger_mount
    "raw-disk" -> R.string.danger_raw_disk
    "power" -> R.string.danger_power
    "pm" -> R.string.danger_pm
    "system-settings" -> R.string.danger_system_settings
    "recursive-perms" -> R.string.danger_recursive_perms
    "pipe-to-shell" -> R.string.danger_pipe_to_shell
    "fork-bomb" -> R.string.danger_fork_bomb
    else -> R.string.danger_unknown
}
