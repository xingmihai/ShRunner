package com.shrunner.app.ui.settings

import androidx.lifecycle.ViewModel
import com.shrunner.app.data.SettingsStore
import com.shrunner.app.data.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val store: SettingsStore) : ViewModel() {
    val themeMode = store.themeMode
    val dynamicColor = store.dynamicColor
    val language = store.language

    fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)
    fun setDynamicColor(enabled: Boolean) = store.setDynamicColor(enabled)
    fun setLanguage(tag: String) = store.setLanguage(tag)
}
