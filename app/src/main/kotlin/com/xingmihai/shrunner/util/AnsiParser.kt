package com.xingmihai.shrunner.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * 极简 ANSI 转义解析：支持 SGR 基本色（30-37 / 90-97）、粗体、下划线与重置。
 * 其余转义序列直接丢弃，保证脚本原始输出不会污染界面。
 */
object AnsiParser {

    private val ESC = Regex("""\u001B\[[0-9;]*m""")

    private val FOREGROUND = mapOf(
        30 to Color(0xFF3B3B3B), 31 to Color(0xFFEF5350), 32 to Color(0xFF66BB6A),
        33 to Color(0xFFFFCA28), 34 to Color(0xFF42A5F5), 35 to Color(0xFFAB47BC),
        36 to Color(0xFF26C6DA), 37 to Color(0xFFE0E0E0),
        90 to Color(0xFF8A8F98), 91 to Color(0xFFEF9A9A), 92 to Color(0xFFA5D6A7),
        93 to Color(0xFFFFE082), 94 to Color(0xFF90CAF9), 95 to Color(0xFFCE93D8),
        96 to Color(0xFF80DEEA), 97 to Color(0xFFFFFFFF)
    )

    fun parse(input: String): AnnotatedString {
        if (!input.contains('\u001B')) return AnnotatedString(input)
        return buildAnnotatedString {
            var color: Color? = null
            var bold = false
            var underline = false
            var cursor = 0
            ESC.findAll(input).forEach { match ->
                if (match.range.first > cursor) {
                    pushStyle(SpanStyle(color = color ?: Color.Unspecified, fontWeight = if (bold) FontWeight.Bold else null, textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null))
                    append(input.substring(cursor, match.range.first))
                    pop()
                }
                cursor = match.range.last + 1
                val codes = match.value.removePrefix("\u001B[").removeSuffix("m")
                    .split(';').mapNotNull { it.toIntOrNull() }
                codes.forEach { code ->
                    when (code) {
                        0 -> { color = null; bold = false; underline = false }
                        1 -> bold = true
                        4 -> underline = true
                        in FOREGROUND -> color = FOREGROUND[code]
                    }
                }
            }
            if (cursor < input.length) {
                pushStyle(SpanStyle(color = color ?: Color.Unspecified, fontWeight = if (bold) FontWeight.Bold else null))
                append(input.substring(cursor))
                pop()
            }
        }
    }

    fun hasEscape(input: String): Boolean = input.contains('\u001B')
}

/** 编辑器用的 Shell 语法高亮。 */
fun highlightShell(source: String): AnnotatedString {
    val keywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
        "case", "esac", "function", "return", "exit", "export", "local", "source",
        "echo", "printf", "set", "shift", "trap", "eval", "exec"
    )
    val commands = setOf("ls", "cd", "cp", "mv", "rm", "cat", "grep", "sed", "awk", "curl", "wget", "sleep", "date", "mkdir", "chmod", "su", "toybox", "busybox")
    return buildAnnotatedString {
        var index = 0
        source.lines().forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) append('\n')
            var column = 0
            while (column < line.length) {
                val rest = line.substring(column)
                when {
                    rest.startsWith("#") -> {
                        pushStyle(SpanStyle(color = Color(0xFF8A8F98), fontStyle = FontStyle.Italic))
                        append(rest)
                        pop()
                        column = line.length
                    }
                    rest.startsWith("\"") || rest.startsWith("'") -> {
                        val quote = rest.first()
                        val end = rest.indexOf(quote, 1).let { if (it < 0) rest.length else it + 1 }
                        val literal = rest.substring(0, end)
                        pushStyle(SpanStyle(color = Color(0xFFCE9178)))
                        append(literal)
                        pop()
                        column += literal.length
                    }
                    rest.startsWith("$") -> {
                        val token = rest.takeWhile { it.isLetterOrDigit() || it == '_' || it == '{' || it == '}' || it == '$' }
                        pushStyle(SpanStyle(color = Color(0xFF9CDCFE)))
                        append(token.ifEmpty { "$" })
                        pop()
                        column += token.length.ifEmpty { 1 }
                    }
                    else -> {
                        val token = rest.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == '/' }
                        val style = when {
                            token in keywords -> SpanStyle(color = Color(0xFFC586C0), fontWeight = FontWeight.Bold)
                            token in commands -> SpanStyle(color = Color(0xFF4EC9B0))
                            else -> SpanStyle(color = Color.Unspecified)
                        }
                        pushStyle(style)
                        append(token.ifEmpty { line[column].toString() })
                        pop()
                        column += token.length.ifEmpty { 1 }
                    }
                }
            }
            index = column
        }
    }
}
