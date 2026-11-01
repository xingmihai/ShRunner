package com.shrunner.app.ui.settings

import androidx.lifecycle.ViewModel
import com.shrunner.app.data.SettingsStore
import com.shrunner.app.data.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val store: SettingsStore) : ViewModel() {
    val themeMode = store.themeMode
    val reduceTransparency = store.reduceTransparency
    val language = store.language

    fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)
    fun setReduceTransparency(enabled: Boolean) = store.setReduceTransparency(enabled)
    fun setLanguage(tag: String) = store.setLanguage(tag)
}
