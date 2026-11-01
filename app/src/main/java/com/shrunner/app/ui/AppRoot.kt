package com.shrunner.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shrunner.app.ui.editor.EditorScreen
import com.shrunner.app.ui.history.HistoryScreen
import com.shrunner.app.ui.run.RunScreen
import com.shrunner.app.ui.scripts.ScriptsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("scripts", "Scripts", Icons.Filled.Terminal),
    Tab("favorites", "Favorites", Icons.Filled.Star),
    Tab("history", "History", Icons.Filled.History),
)

@Composable
fun AppRoot(launchScript: String?, onLaunchConsumed: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route == null || tabs.any { it.route == route }

    fun openEditor(name: String) = nav.navigate("editor/${Uri.encode(name)}")
    fun openRun(name: String) = nav.navigate("run/${Uri.encode(name)}")

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(nav, startDestination = "scripts", modifier = Modifier.padding(inner)) {
            composable("scripts") {
                ScriptsScreen(favoritesOnly = false, onOpen = ::openEditor, onRun = ::openRun)
            }
            composable("favorites") {
                ScriptsScreen(favoritesOnly = true, onOpen = ::openEditor, onRun = ::openRun)
            }
            composable("history") { HistoryScreen() }
            composable("editor/{name}") {
                EditorScreen(onBack = { nav.popBackStack() }, onRun = ::openRun)
            }
            composable("run/{name}") {
                RunScreen(onBack = { nav.popBackStack() })
            }
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
