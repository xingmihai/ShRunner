package com.xingmihai.shrunner.util

/** 危险命令扫描：命中后在首次执行前弹二次确认。 */
object DangerScan {

    private data class Rule(val pattern: Regex, val reason: String)

    private val RULES = listOf(
        Rule(Regex("""(?m)^\s*rm\s+[^\n]*\s-[rRfF]{1,2}\b"""), "递归/强制删除文件（rm -rf）"),
        Rule(Regex("""\bmkfs(\.\w+)?\b"""), "格式化分区（mkfs）"),
        Rule(Regex("""(?m)^\s*dd\s+if="""), "底层磁盘写入（dd）"),
        Rule(Regex("""\breboot\b|\bpoweroff\b"""), "重启或关机设备"),
        Rule(Regex("""\bmount\b|\bumount\b"""), "挂载/卸载文件系统"),
        Rule(Regex("""\bsu\b"""), "脚本内部再次申请 root（su）"),
        Rule(Regex("""\bchmod\s+(-\s*)?[0-7]*7[0-7]{2}\b"""), "赋予 777 权限（chmod）"),
        Rule(Regex("""\bpm\s+(uninstall|install)\b"""), "安装/卸载应用（pm）"),
        Rule(Regex("""\b(wipe|format)\b"""), "擦除数据相关命令"),
        Rule(Regex("""/dev/block/|/dev/sd"""), "直接操作块设备"),
        Rule(Regex("""\bcurl\b[^\n]*\|\s*(ba)?sh"""), "下载并直接执行远程脚本")
    )

    fun scan(source: String): List<String> {
        val noComments = source.lines().filterNot { it.trimStart().startsWith("#") }
        val text = noComments.joinToString("\n")
        return RULES.filter { it.pattern.containsMatchIn(text) }.map { it.reason }.distinct()
    }
}
