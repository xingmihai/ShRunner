package com.shrunner.app.system

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.shrunner.app.MainActivity
import com.shrunner.app.R

/**
 * Home-screen / launcher shortcuts and the Quick Settings tile.
 * They only OPEN the run screen for a script; the user still taps Run (and sees any danger prompt).
 */
object Shortcuts {
    const val ACTION_RUN = "com.shrunner.app.action.RUN_SCRIPT"
    const val EXTRA_SCRIPT = "script_name"

    fun runIntent(context: Context, scriptName: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_RUN)
            .putExtra(EXTRA_SCRIPT, scriptName)

    fun requestPin(context: Context, scriptName: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, build(context, "pin:$scriptName", scriptName), null)
    }

    /** Favorites show up when long-pressing the app icon. */
    fun publishFavorites(context: Context, names: List<String>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
        val list = names.take(max).map { build(context, "fav:$it", it) }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, list) }
    }

    fun requestAddTile(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, RunTileService::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_terminal),
                context.mainExecutor,
            ) { }
        }
    }

    private fun build(context: Context, id: String, scriptName: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(scriptName.removeSuffix(".sh"))
            .setLongLabel("Run ${scriptName.removeSuffix(".sh")}")
            .setIcon(icon(context))
            .setIntent(runIntent(context, scriptName))
            .build()

    private fun icon(context: Context): IconCompat {
        val size = 216
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF1F2A37.toInt())
        ContextCompat.getDrawable(context, R.drawable.ic_terminal)?.let { d ->
            val inset = size / 4 // stay inside the adaptive-icon safe zone
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
