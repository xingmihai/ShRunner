package com.xingmihai.shrunner.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * 每个脚本独立的运行配置：自动应答、环境变量、工作目录、root 开关。
 * 自动应答按脚本分开存，避免不同脚本的提问串味。
 */
class ScriptConfig private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("script_config", Context.MODE_PRIVATE)

    data class Config(
        /** 一行一个，按顺序自动回答脚本的提问。 */
        val autoAnswers: String = "",
        /** 一行一个 KEY=VALUE，仅对该脚本生效。 */
        val env: String = "",
        /** 留空表示用脚本所在目录。 */
        val workDir: String = "",
        /** 空=跟随全局开关；on=强制 root；off=强制非 root。 */
        val rootOverride: String = ""
    )

    fun of(id: String): Config {
        if (id.isBlank()) return Config()
        val raw = prefs.getString("cfg_v2_$id", null) ?: return Config()
        val json = runCatching { JSONObject(raw) }.getOrElse { return Config() }
        return Config(
            autoAnswers = json.optString("answers", ""),
            env = json.optString("env", ""),
            workDir = json.optString("workDir", ""),
            rootOverride = json.optString("root", "")
        )
    }

    fun save(id: String, config: Config) {
        if (id.isBlank()) return
        val json = JSONObject().apply {
            put("answers", config.autoAnswers)
            put("env", config.env)
            put("workDir", config.workDir)
            put("root", config.rootOverride)
        }
        prefs.edit { putString("cfg_v2_$id", json.toString()) }
    }

    fun answers(id: String): List<String> = of(id).autoAnswers.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    /** 该脚本最终生效的环境变量：全局默认打底，脚本配置覆盖。 */
    fun envMap(id: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        parseInto(RunPrefs.get(prefsAll ?: return result).customEnv, result)
        parseInto(of(id).env, result)
        return result
    }

    private var prefsAll: Context? = null

    private fun parseInto(text: String, sink: MutableMap<String, String>) {
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .forEach { line ->
                val idx = line.indexOf('=')
                val key = line.substring(0, idx).trim()
                if (key.isNotEmpty()) sink[key] = line.substring(idx + 1).trim()
            }
    }

    /** 该脚本是否用 root：优先脚本自己的设置，否则跟随全局。 */
    fun useRoot(id: String, globalRoot: Boolean): Boolean = when (of(id).rootOverride) {
        "on" -> true
        "off" -> false
        else -> globalRoot
    }

    companion object {
        @Volatile
        private var instance: ScriptConfig? = null

        fun get(context: Context): ScriptConfig =
            instance ?: synchronized(this) {
                instance ?: ScriptConfig(context).also {
                    it.prefsAll = context.applicationContext
                }
            }
    }
}
