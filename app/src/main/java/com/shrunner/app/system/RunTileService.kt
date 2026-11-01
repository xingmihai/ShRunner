package com.shrunner.app.system

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.shrunner.app.MainActivity
import com.shrunner.app.data.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** One Quick Settings tile that opens the run screen for the script chosen via "Set as tile". */
@AndroidEntryPoint
class RunTileService : TileService() {
    @Inject lateinit var prefs: AppPreferences

    private fun target(): String? = runBlocking { prefs.tileScript.first() }

    override fun onStartListening() {
        val name = target()
        qsTile?.apply {
            label = name?.removeSuffix(".sh") ?: "ShRunner"
            state = if (name != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val name = target()
        val intent = (if (name != null) Shortcuts.runIntent(this, name) else Intent(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
