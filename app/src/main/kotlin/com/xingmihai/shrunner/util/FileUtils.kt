package com.xingmihai.shrunner.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import com.xingmihai.shrunner.MainActivity
import com.xingmihai.shrunner.R
import java.io.File

/** 读取 content:// 或 file:// 的真实显示名，用作导入后的脚本名。 */
fun displayNameOf(context: Context, uri: Uri): String {
    var name: String? = null
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported.sh"
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** 把日志写入应用私有目录，便于导出。 */
fun exportLog(context: Context, name: String, content: String): File {
    val dir = File(context.filesDir, "logs").apply { mkdirs() }
    val file = File(dir, "$name.log")
    file.writeText(content)
    return file
}

/** 为脚本创建桌面快捷方式（Android 8+ 固定快捷方式）。 */
fun requestScriptShortcut(context: Context, scriptId: String, label: String) {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return
    if (!manager.isRequestPinShortcutSupported) {
        Toast.makeText(context, "当前桌面不支持固定快捷方式", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_RUN
        putExtra(MainActivity.EXTRA_SCRIPT_ID, scriptId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        setPackage(context.packageName)
    }
    val info = ShortcutInfo.Builder(context, "run_$scriptId")
        .setShortLabel(label)
        .setLongLabel("运行 $label")
        .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(intent)
        .build()
    manager.requestPinShortcut(info, null)
}
