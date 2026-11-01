package com.shrunner.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Appearance and language settings.
 * Backed by SharedPreferences on purpose: the language must be readable synchronously in
 * attachBaseContext, and the theme must be known before the first frame (no flash).
 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
    )
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))
    private val _language = MutableStateFlow(readLanguage(context))

    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()
    val language: StateFlow<String> = _language.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _dynamicColor.value = enabled
    }

    fun setLanguage(tag: String) {
        val valid = if (tag == LANG_EN) LANG_EN else LANG_ZH
        prefs.edit().putString(KEY_LANG, valid).apply()
        _language.value = valid
    }

    companion object {
        const val LANG_ZH = "zh"
        const val LANG_EN = "en"
        /** Chinese unless the user explicitly picks English. */
        const val DEFAULT_LANGUAGE = LANG_ZH

        private const val FILE = "settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_LANG = "language"

        fun readLanguage(context: Context): String =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANG, null)
                ?.takeIf { it == LANG_ZH || it == LANG_EN } ?: DEFAULT_LANGUAGE
    }
}
