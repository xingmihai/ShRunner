package com.xingmihai.shrunner.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 网络连通性检测。
 *
 * 脚本卡在「检查更新中」然后崩溃（如 stoi 解析空响应）时，
 * 多半是它请求境外接口失败、拿到空内容。这里先测一遍常见端点，
 * 帮用户判断到底要不要配代理。
 */
object NetCheck {

    private val TARGETS = listOf(
        "api.github.com" to 443,
        "raw.githubusercontent.com" to 443,
        "github.com" to 443,
        "www.google.com" to 443,
        "1.1.1.1" to 443
    )

    /** 逐个 TCP 连接测试，返回结果文本。 */
    suspend fun run(): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf("== 网络连通性检测 ==")
        var reachable = 0
        for ((host, port) in TARGETS) {
            val start = System.currentTimeMillis()
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 4000)
                    true
                }
            }.getOrDefault(false)
            val cost = System.currentTimeMillis() - start
            if (ok) reachable++
            out += "$host:$port → " + (if (ok) "通" else "不通") + "（${cost}ms）"
        }
        out += "结果：$reachable / ${TARGETS.size} 可达"
        out += when {
            reachable == 0 -> "全部不通：脚本的更新检查必然失败，建议配代理或改用离线安装"
            reachable >= 3 -> "网络基本正常：崩溃更可能是脚本自身解析问题"
            else -> "部分可达：可能是 DNS 或线路问题，可尝试配置代理"
        }
        out += "== 检测结束 =="
        out
    }
}
