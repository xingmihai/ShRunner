package com.shrunner.app.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shrunner.app.R
import com.shrunner.app.ui.editor.EditorScreen
import com.shrunner.app.ui.glass.BottomBarHeight
import com.shrunner.app.ui.history.HistoryScreen
import com.shrunner.app.ui.run.RunScreen
import com.shrunner.app.ui.scripts.ScriptsScreen
import com.shrunner.app.ui.settings.SettingsScreen
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl

private data class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("scripts", R.string.nav_scripts, Icons.Filled.Terminal),
    Tab("favorites", R.string.nav_favorites, Icons.Filled.Star),
    Tab("history", R.string.nav_history, Icons.Filled.History),
    Tab("settings", R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot(launchScript: String?, onLaunchConsumed: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route == null || tabs.any { it.route == route }

    fun openEditor(name: String) = nav.navigate("editor/${Uri.encode(name)}")
    fun openRun(name: String) = nav.navigate("run/${Uri.encode(name)}")

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "scripts", modifier = Modifier.fillMaxSize()) {
            composable("scripts") {
                ScriptsScreen(favoritesOnly = false, onOpen = ::openEditor, onRun = ::openRun)
            }
            composable("favorites") {
                ScriptsScreen(favoritesOnly = true, onOpen = ::openEditor, onRun = ::openRun)
            }
            composable("history") { HistoryScreen() }
            composable("settings") { SettingsScreen() }
            composable("editor/{name}") {
                EditorScreen(onBack = { nav.popBackStack() }, onRun = ::openRun)
            }
            composable("run/{name}") {
                RunScreen(onBack = { nav.popBackStack() })
            }
        }

        if (showBar) {
            TabBar(
                currentRoute = route,
                onSelect = { target ->
                    nav.navigate(target) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp)
                    .navigationBarsPadding(),
            )
        }
    }

    // Shortcut / tile launches: open the run screen (the user still taps Run).
    LaunchedEffect(launchScript) {
        launchScript?.let {
            openRun(it)
            onLaunchConsumed()
        }
    }
}

/**
 * Bottom navigation built from Glasmio's segmented control (glass lens, icon + label per tab).
 * GlasmioNavigationBar needs a shared layer backdrop and scroll-compaction state wired through the whole
 * page tree; this keeps the same look with only verified APIs.
 */
@Composable
private fun TabBar(currentRoute: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val labels = tabs.map { stringResource(it.label) }
    val selected = tabs.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    GlasmioSegmentedControl(
        items = labels.mapIndexed { index, label -> index to label },
        selected = selected,
        onSelected = { onSelect(tabs[it].route) },
        modifier = modifier,
        height = BottomBarHeight,
        itemContent = { index, _ ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Icon(tabs[index].icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(labels[index], fontSize = 10.sp, maxLines = 1)
            }
        },
    )
}
