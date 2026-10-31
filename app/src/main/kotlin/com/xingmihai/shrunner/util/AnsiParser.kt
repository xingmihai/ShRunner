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

/**
 * 语法高亮的上限字符数。
 *
 * 超过这个长度就不做高亮：一方面逐词扫描大文件会卡住界面，
 * 另一方面这种脚本多半内嵌了 base64 之类的编码块，高亮没有意义。
 */
const val HIGHLIGHT_LIMIT = 100_000

/**
 * 编辑器用的 Shell 语法高亮。
 *
 * 关键约束：返回的文本长度必须与原文本**严格相等**。
 * 高亮结果通过 OffsetMapping.Identity 交给 TextField，一旦长度对不上，
 * 光标/选区的偏移量就会越界，Compose 会直接抛 IllegalStateException 崩溃
 * （典型：originalToTransformed ... is not in range of transformed text）。
 *
 * 因此这里按索引在原字符串上推进，逐段 append 原文本的子串，
 * 不使用 lines() / 重新 join 之类的重建方式（它们会丢掉末尾空行与 \r）。
 */
fun highlightShell(source: String): AnnotatedString {
    if (source.length > HIGHLIGHT_LIMIT) {
        // 不做任何变换，长度天然一致
        return AnnotatedString(source)
    }

    val keywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
        "case", "esac", "function", "return", "exit", "export", "local", "source",
        "echo", "printf", "set", "shift", "trap", "eval", "exec"
    )
    val commands = setOf(
        "ls", "cd", "cp", "mv", "rm", "cat", "grep", "sed", "awk", "curl", "wget",
        "sleep", "date", "mkdir", "chmod", "su", "toybox", "busybox"
    )
    val commentStyle = SpanStyle(color = Color(0xFF8A8F98), fontStyle = FontStyle.Italic)
    val stringStyle = SpanStyle(color = Color(0xFFCE9178))
    val varStyle = SpanStyle(color = Color(0xFF9CDCFE))

    return buildAnnotatedString {
        val n = source.length
        var i = 0
        while (i < n) {
            when (val c = source[i]) {
                '#' -> {
                    // 注释：到行尾（不含换行，换行留给下一轮原样追加）
                    var end = i
                    while (end < n && source[end] != '\n') end++
                    pushStyle(commentStyle)
                    append(source.substring(i, end))
                    pop()
                    i = end
                }
                '"', '\'' -> {
                    val quote = c
                    var end = i + 1
                    while (end < n && source[end] != quote && source[end] != '\n') end++
                    if (end < n && source[end] == quote) end++
                    pushStyle(stringStyle)
                    append(source.substring(i, end))
                    pop()
                    i = end
                }
                '$' -> {
                    var end = i + 1
                    while (end < n) {
                        val ch = source[end]
                        if (ch.isLetterOrDigit() || ch == '_' || ch == '{' || ch == '}' || ch == '$') end++
                        else break
                    }
                    pushStyle(varStyle)
                    append(source.substring(i, end))
                    pop()
                    i = end
                }
                else -> {
                    if (c.isLetterOrDigit() || c == '_') {
                        var end = i
                        while (end < n) {
                            val ch = source[end]
                            if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == '/') end++
                            else break
                        }
                        val token = source.substring(i, end)
                        val style = when {
                            token in keywords -> SpanStyle(color = Color(0xFFC586C0), fontWeight = FontWeight.Bold)
                            token in commands -> SpanStyle(color = Color(0xFF4EC9B0))
                            else -> null
                        }
                        if (style != null) {
                            pushStyle(style)
                            append(token)
                            pop()
                        } else {
                            append(token)
                        }
                        i = end
                    } else {
                        // 空白、换行、符号等原样追加，保证长度不丢
                        append(c)
                        i++
                    }
                }
            }
        }
    }
}
