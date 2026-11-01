package com.shrunner.app.shell

import com.shrunner.app.data.DangerHit
import java.security.MessageDigest

/**
 * Static, line-based guardrail that flags commands worth a second look.
 * It is NOT a sandbox: `eval`, variables, base64 and sourced files can hide a command.
 * It is deliberately conservative, so false positives are expected.
 */
object DangerDetector {

    private val splitter = Regex("""[\s;&|(){}`]+""")
    private val devRedirect = Regex(""">\s*/dev/(?!null\b|zero\b|tty\b|stdout\b|stderr\b)""")
    private val pipeToShell = Regex("""\b(curl|wget)\b.*\|\s*(sudo\s+)?(ba|z)?sh\b""")
    private val forkBomb = Regex(""":\s*\(\s*\)\s*\{""")

    private class Rule(val id: String, val test: (tokens: List<String>, cmds: List<String>, line: String) -> Boolean)

    private val rules = listOf(
        Rule("rm-recursive") { tokens, cmds, _ ->
            "rm" in cmds && tokens.any { tok ->
                tok == "--recursive" || (tok.length > 1 && tok[0] == '-' && tok[1] != '-' && tok.any { c -> c == 'r' || c == 'R' })
            }
        },
        Rule("find-delete") { tokens, cmds, _ -> "find" in cmds && "-delete" in tokens },
        Rule("su") { _, cmds, _ -> cmds.any { it == "su" || it == "sudo" } },
        Rule("mount") { _, cmds, _ ->
            cmds.any { it == "mount" || it == "umount" || it == "losetup" }
        },
        Rule("raw-disk") { tokens, cmds, line ->
            ("dd" in cmds && tokens.any { it.startsWith("of=") }) ||
                cmds.any { it.startsWith("mkfs") || it in setOf("fdisk", "sfdisk", "parted", "wipefs", "blkdiscard", "shred") } ||
                devRedirect.containsMatchIn(line)
        },
        Rule("power") { _, cmds, _ ->
            cmds.any { it in setOf("reboot", "shutdown", "poweroff", "halt") }
        },
        Rule("pm") { tokens, cmds, _ ->
            "pm" in cmds && tokens.any { it in setOf("uninstall", "clear", "disable", "disable-user", "hide", "suspend") }
        },
        Rule("system-settings") { tokens, cmds, _ ->
            cmds.any { it in setOf("setprop", "resetprop", "setenforce") } || ("settings" in cmds && "put" in tokens)
        },
        Rule("recursive-perms") { tokens, cmds, _ ->
            cmds.any { it in setOf("chmod", "chown", "chcon") } && tokens.any { it == "-R" || it == "--recursive" }
        },
        Rule("pipe-to-shell") { _, _, line -> pipeToShell.containsMatchIn(line) },
        Rule("fork-bomb") { _, _, line -> forkBomb.containsMatchIn(line) },
    )

    fun scan(script: String): List<DangerHit> {
        val hits = mutableListOf<DangerHit>()
        script.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val tokens = line.split(splitter).filter { it.isNotEmpty() }
            val cmds = tokens.map { it.substringAfterLast('/') }
            for (rule in rules) {
                if (rule.test(tokens, cmds, line)) {
                    hits += DangerHit(rule.id, index + 1, line, fingerprint(rule.id, line))
                }
            }
        }
        return hits
    }

    private fun fingerprint(ruleId: String, line: String): String =
        MessageDigest.getInstance("SHA-256").digest("$ruleId|$line".toByteArray())
            .joinToString("") { "%02x".format(it) }
}
