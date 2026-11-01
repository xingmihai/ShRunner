package com.shrunner.app

import android.app.Application
import com.shrunner.app.data.ScriptRepository
import com.shrunner.app.system.Shortcuts
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ShRunnerApp : Application() {
    @Inject lateinit var scripts: ScriptRepository
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Keep launcher long-press shortcuts in sync with favorites.
        appScope.launch {
            scripts.scripts
                .map { list -> list.filter { it.favorite }.map { it.name } }
                .distinctUntilChanged()
                .collect { Shortcuts.publishFavorites(this@ShRunnerApp, it) }
        }
    }
}
