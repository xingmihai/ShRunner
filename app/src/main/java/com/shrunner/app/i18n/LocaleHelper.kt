package com.shrunner.app.i18n

import android.content.Context
import android.content.res.Configuration
import com.shrunner.app.data.SettingsStore
import java.util.Locale

object LocaleHelper {
    /** Context whose resources use [languageTag]; used in Activity.attachBaseContext. */
    fun wrap(base: Context, languageTag: String): Context {
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Context in the currently selected language, for strings built outside the UI (errors, shortcuts). */
    fun localized(context: Context): Context = wrap(context, SettingsStore.readLanguage(context))
}
