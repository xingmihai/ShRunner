package com.xingmihai.shrunner.data

import android.content.Context
import androidx.core.content.edit

/**
 * 执行环境配置：用户自定义的环境变量、是否跳过更新检查等开关。
 * 存在 SharedPreferences 里，每次执行脚本时注入。
 */
class RunPrefs private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("run_prefs", Context.MODE_PRIVATE)

    /** 用户自定义环境变量，一行一个 KEY=VALUE。 */
    var customEnv: String
        get() = prefs.getString(KEY_CUSTOM_ENV, "") ?: ""
        set(value) = prefs.edit { putString(KEY_CUSTOM_ENV, value) }

    /** 是否自动注入「跳过更新检查」类变量（脚本里的原生程序常因联网失败而崩溃）。 */
    var skipUpdateCheck: Boolean
        get() = prefs.getBoolean(KEY_SKIP_UPDATE, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_UPDATE, value) }

    /** 是否把 stdin 固定接空（避免脚本卡在等输入）。 */
    var closeStdin: Boolean
        get() = prefs.getBoolean(KEY_CLOSE_STDIN, false)
        set(value) = prefs.edit { putBoolean(KEY_CLOSE_STDIN, value) }

    /** 解析出最终要注入的环境变量。 */
    fun resolveEnv(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        customEnv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .forEach { line ->
                val idx = line.indexOf('=')
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) result[key] = value
            }
        if (skipUpdateCheck) {
            // 常见脚本/原生程序识别的「跳过联网检查」变量名，一次全给
            result["SKIP_UPDATE_CHECK"] = "1"
            result["NO_UPDATE_CHECK"] = "1"
            result["DISABLE_UPDATE_CHECK"] = "1"
            result["CHECK_UPDATE"] = "0"
            result["AUTO_UPDATE"] = "0"
            result["SKIP_VERSION_CHECK"] = "1"
        }
        return result
    }

    companion object {
        private const val KEY_CUSTOM_ENV = "custom_env"
        private const val KEY_SKIP_UPDATE = "skip_update"
        private const val KEY_CLOSE_STDIN = "close_stdin"

        @Volatile
        private var instance: RunPrefs? = null

        fun get(context: Context): RunPrefs =
            instance ?: synchronized(this) {
                instance ?: RunPrefs(context).also { instance = it }
            }
    }
}
