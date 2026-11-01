package com.shrunner.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import com.shrunner.app.R
import com.shrunner.app.i18n.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScriptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
) {
    val scriptsDir: File = File(context.filesDir, "scripts").apply { mkdirs() }
    val workDir: File = File(context.filesDir, "work").apply { mkdirs() }
    private val snapshotDir: File = File(context.cacheDir, "run").apply { mkdirs() }

    private val refreshTick = MutableStateFlow(0)

    val scripts: Flow<List<Script>> = combine(refreshTick, prefs.favorites) { _, favorites ->
        withContext(Dispatchers.IO) {
            scriptsDir.listFiles { f -> f.isFile && f.name.endsWith(".sh") }.orEmpty()
                .map { Script(it.name, it.length(), it.lastModified(), it.name in favorites) }
                .sortedBy { it.name.lowercase() }
        }
    }

    private fun refresh() = refreshTick.update { it + 1 }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        LocaleHelper.localized(context).getString(id, *args)

    /** Adds ".sh" if missing and validates. Throws [IllegalArgumentException] with a user-facing message. */
    fun normalizeName(raw: String): String {
        var n = raw.trim()
        if (!n.endsWith(".sh", ignoreCase = true)) n += ".sh"
        require(!n.startsWith(".")) { str(R.string.err_name_dot) }
        require(NAME_REGEX.matches(n)) { str(R.string.err_name_chars) }
        return n
    }

    /** Resolves a script name to a file inside [scriptsDir]; rejects anything that could escape it. */
    fun resolve(name: String): File {
        require(NAME_REGEX.matches(name)) { str(R.string.err_invalid_name) }
        val f = File(scriptsDir, name)
        require(f.canonicalFile.parentFile == scriptsDir.canonicalFile) { str(R.string.err_invalid_name) }
        return f
    }

    suspend fun read(name: String): String = withContext(Dispatchers.IO) {
        val f = resolve(name)
        if (!f.exists()) throw IOException(str(R.string.err_gone, name))
        f.readText()
    }

    suspend fun write(name: String, text: String) = withContext(Dispatchers.IO) {
        writeAtomic(resolve(name), text)
        refresh()
    }

    suspend fun create(raw: String): String = withContext(Dispatchers.IO) {
        val name = normalizeName(raw)
        val f = resolve(name)
        require(!f.exists()) { str(R.string.err_exists, name) }
        writeAtomic(f, "#!/system/bin/sh\n\n")
        refresh()
        name
    }

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val display = context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: "script.sh"
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException(str(R.string.err_open))
        val text = input.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                require(total <= MAX_IMPORT_BYTES) { str(R.string.err_too_large) }
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
        require('\u0000' !in text) { str(R.string.err_not_text) }
        // Windows line endings and BOMs make /system/bin/sh fail with confusing errors.
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val name = uniqueName(sanitize(display))
        writeAtomic(File(scriptsDir, name), clean)
        refresh()
        name
    }

    suspend fun rename(old: String, raw: String): String = withContext(Dispatchers.IO) {
        val newName = normalizeName(raw)
        if (newName == old) return@withContext old
        val src = resolve(old)
        val dst = resolve(newName)
        require(src.exists()) { str(R.string.err_script_missing) }
        require(!dst.exists()) { str(R.string.err_exists, newName) }
        if (!src.renameTo(dst)) throw IOException(str(R.string.err_rename))
        prefs.renameScript(old, newName)
        refresh()
        newName
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        val f = resolve(name)
        if (f.exists() && !f.delete()) throw IOException(str(R.string.err_delete, name))
        prefs.forgetScript(name)
        refresh()
    }

    /** Copy of exactly the text that was scanned for dangerous commands; that copy is what runs. */
    suspend fun snapshot(name: String, text: String): File = withContext(Dispatchers.IO) {
        File(snapshotDir, "${System.nanoTime()}-$name").also { it.writeText(text) }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException(str(R.string.err_save, target.name))
        }
    }

    private fun sanitize(display: String): String {
        var base = display.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trimStart('.')
        if (base.endsWith(".sh", ignoreCase = true)) base = base.dropLast(3)
        if (base.isEmpty()) base = "script"
        return base.take(48)
    }

    private fun uniqueName(base: String): String {
        var n = "$base.sh"
        var i = 1
        while (File(scriptsDir, n).exists()) n = "$base-${i++}.sh"
        return n
    }

    private companion object {
        val NAME_REGEX = Regex("^[A-Za-z0-9._ -]{4,64}$")
        const val MAX_IMPORT_BYTES = 1_000_000
    }
}
