package com.shrunner.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrunner.app.R
import com.shrunner.app.data.SettingsStore
import com.shrunner.app.data.ThemeMode
import com.shrunner.app.ui.glass.bottomBarSpace
import io.github.neoruaa.glasmio.preference.GlasmioPreferenceGroupTitle
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import io.github.neoruaa.glasmio.ui.GlasmioSwitch

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val reduceTransparency by vm.reduceTransparency.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun changeLanguage(tag: String) {
        if (tag == language) return
        vm.setLanguage(tag)
        // Resources are bound to the Activity's base context, so recreate to apply the new language.
        context.findActivity()?.recreate()
    }

    val modes = ThemeMode.entries
    val modeLabels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).map { stringResource(it) }
    val languages = listOf(SettingsStore.LANG_ZH, SettingsStore.LANG_EN)

    GlasmioPinnedListPage(title = stringResource(R.string.nav_settings), bottomPadding = bottomBarSpace()) {
        item(key = "theme") {
            GlasmioPreferenceGroupTitle(stringResource(R.string.settings_theme))
            GlasmioSegmentedControl(
                items = modes.mapIndexed { index, _ -> index to modeLabels[index] },
                selected = modes.indexOf(mode),
                onSelected = { vm.setThemeMode(modes[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item(key = "display") {
            GlasmioPreferenceGroupTitle(stringResource(R.string.settings_display))
            GlasmioGroupedList {
                GlasmioListRow(
                    stringResource(R.string.settings_reduce_transparency),
                    subtitle = stringResource(R.string.settings_reduce_transparency_desc),
                    showTopSeparator = false,
                    trailing = { GlasmioSwitch(reduceTransparency, { vm.setReduceTransparency(it) }) },
                )
            }
        }
        item(key = "language") {
            GlasmioPreferenceGroupTitle(stringResource(R.string.settings_language))
            // Language names are shown in their own language on purpose.
            GlasmioSegmentedControl(
                items = listOf(0 to "简体中文", 1 to "English"),
                selected = languages.indexOf(language).coerceAtLeast(0),
                onSelected = { changeLanguage(languages[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
