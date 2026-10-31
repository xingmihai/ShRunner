package com.xingmihai.shrunner.data

import android.content.Context
import androidx.core.content.edit

/**
 * 全局运行设置：默认环境变量与调试开关。
 * 调试类选项默认关闭，统一收在设置页的「高级」区域。
 */
class RunPrefs private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("run_prefs", Context.MODE_PRIVATE)

    /** 全局默认环境变量，一行一个 KEY=VALUE。会被脚本自身的配置覆盖。 */
    var customEnv: String
        get() = prefs.getString(KEY_CUSTOM_ENV, "") ?: ""
        set(value) = prefs.edit { putString(KEY_CUSTOM_ENV, value) }

    /** 是否使用 root 执行（脚本可单独覆盖）。 */
    var useRoot: Boolean
        get() = prefs.getBoolean(KEY_USE_ROOT, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_ROOT, value) }

    /** 高级：执行前打印脚本实际拿到的环境变量。 */
    var dumpEnv: Boolean
        get() = prefs.getBoolean(KEY_DUMP_ENV, false)
        set(value) = prefs.edit { putBoolean(KEY_DUMP_ENV, value) }

    /** 高级：打印实际执行的命令。 */
    var verbose: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE, false)
        set(value) = prefs.edit { putBoolean(KEY_VERBOSE, value) }

    companion object {
        private const val KEY_CUSTOM_ENV = "custom_env"
        private const val KEY_USE_ROOT = "use_root"
        private const val KEY_DUMP_ENV = "dump_env"
        private const val KEY_VERBOSE = "verbose"

        @Volatile
        private var instance: RunPrefs? = null

        fun get(context: Context): RunPrefs =
            instance ?: synchronized(this) {
                instance ?: RunPrefs(context).also { instance = it }
            }
    }
}
