package com.xingmihai.shrunner.data

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScriptEntry(
    val id: String,
    val name: String,
    val file: File,
    val updatedAt: Long,
    val sizeBytes: Long,
    val favorite: Boolean = false,
    val lastRunAt: Long = 0L,
    val lastExitCode: Int? = null
)

/**
 * 脚本仓库：脚本文件保存在应用私有目录 filesDir/scripts，
 * 元数据（收藏、上次运行时间/退出码）保存在 SharedPreferences。
 */
class ScriptRepository private constructor(private val app: Application) {

    private val dir: File = File(app.filesDir, "scripts").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("script_meta", Context.MODE_PRIVATE)

    fun directory(): File = dir

    fun list(): List<ScriptEntry> = dir.listFiles()
        ?.filter { it.isFile && it.extension == "sh" }
        ?.map { file ->
            val id = file.nameWithoutExtension
            ScriptEntry(
                id = id,
                name = id,
                file = file,
                updatedAt = file.lastModified(),
                sizeBytes = file.length(),
                favorite = readMeta(id).optBoolean(KEY_FAVORITE, false),
                lastRunAt = readMeta(id).optLong(KEY_LAST_RUN, 0L),
                lastExitCode = readMeta(id).optIntOrNull(KEY_EXIT_CODE)
            )
        }
        ?.sortedWith(compareByDescending<ScriptEntry> { it.favorite }.thenByDescending { it.updatedAt })
        ?: emptyList()

    fun find(id: String): ScriptEntry? = list().firstOrNull { it.id == id }

    /** 按原始文件名反查已导入条目（文件浏览器直接执行时用）。 */
    fun findByName(rawName: String): ScriptEntry? {
        val id = normalizeId(rawName)
        return list().firstOrNull { it.id == id }
    }

    fun fileOf(id: String): File = File(dir, "$id.sh")

    fun create(rawName: String): ScriptEntry {
        val id = normalizeId(rawName)
        val file = fileOf(id)
        if (!file.exists()) {
            file.writeText(TEMPLATE.trimIndent() + "\n")
        }
        return ScriptEntry(id, id, file, file.lastModified(), file.length())
    }

    /** 从 content:// 导入：流式拷贝，10MB 脚本也不会整体进内存。 */
    fun import(uri: Uri, rawName: String): ScriptEntry? {
        val id = uniqueId(rawName)
        val target = fileOf(id)
        val ok = runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        return if (ok) ScriptEntry(id, id, target, target.lastModified(), target.length()) else null
    }

    /**
     * 从本地路径导入（文件浏览器用）。
     * 应用自身没有存储权限，读 /sdcard 等路径会失败，此时改用 root shell 复制。
     * 返回 Pair(条目, 失败原因)，成功时原因为 null。
     */
    suspend fun importPath(path: String): Pair<ScriptEntry?, String?> {
        val source = File(path)
        if (!source.exists()) return null to "文件不存在"
        if (source.isDirectory) return null to "这是个目录"

        val id = uniqueId(source.name)
        val target = fileOf(id)
        val error = com.xingmihai.shrunner.engine.ShellCopy.copy(source.absolutePath, target.absolutePath)
        if (error != null) {
            runCatching { target.delete() }
            return null to error
        }
        runCatching { target.setReadable(true, false) }
        runCatching { target.setWritable(true, false) }
        return ScriptEntry(id, id, target, target.lastModified(), target.length()) to null
    }

    private fun uniqueId(rawName: String): String {
        var id = normalizeId(rawName)
        var file = fileOf(id)
        var index = 1
        while (file.exists()) {
            id = "${normalizeId(rawName)}_$index"
            file = fileOf(id)
            index++
        }
        return id
    }

    fun rename(id: String, rawNewName: String): String {
        val newId = normalizeId(rawNewName)
        if (newId == id) return id
        val from = fileOf(id)
        val to = fileOf(newId)
        if (from.exists() && !to.exists()) {
            from.renameTo(to)
            val meta = readMeta(id)
            prefs.edit().putString(newId, meta.toString()).remove(id).apply()
        }
        return newId
    }

    fun delete(id: String) {
        fileOf(id).delete()
        prefs.edit().remove(id).apply()
    }

    fun toggleFavorite(id: String) {
        val meta = readMeta(id)
        meta.put(KEY_FAVORITE, !meta.optBoolean(KEY_FAVORITE, false))
        writeMeta(id, meta)
    }

    fun recordRun(id: String, exitCode: Int) {
        val meta = readMeta(id)
        meta.put(KEY_LAST_RUN, System.currentTimeMillis())
        meta.put(KEY_EXIT_CODE, exitCode)
        writeMeta(id, meta)
    }

    fun readText(id: String): String = runCatching { fileOf(id).readText() }.getOrDefault("")

    fun writeText(id: String, content: String) {
        fileOf(id).writeText(content)
    }

    private fun normalizeId(raw: String): String {
        val cleaned = raw.trim()
            .substringBeforeLast(".sh")
            .replace(Regex("[^A-Za-z0-9_.\\-一-龥]"), "_")
            .trim('_')
        return cleaned.ifBlank { "script_${System.currentTimeMillis()}" }
    }

    private fun readMeta(id: String): org.json.JSONObject =
        org.json.JSONObject(prefs.getString(id, "{}") ?: "{}")

    private fun writeMeta(id: String, meta: org.json.JSONObject) {
        prefs.edit().putString(id, meta.toString()).apply()
    }

    private fun org.json.JSONObject.optIntOrNull(key: String): Int? =
        if (has(key)) getInt(key) else null

    companion object {
        private const val KEY_FAVORITE = "favorite"
        private const val KEY_LAST_RUN = "last_run"
        private const val KEY_EXIT_CODE = "exit_code"

        private val TEMPLATE = """
            #!/system/bin/sh
            # ShRunner 新建脚本
            echo "Hello from ShRunner"
            echo "当前时间: $(date)"
            sleep 1
            echo "参数: ${'$'}@"
            exit 0
        """.trimIndent()

        @Volatile
        private var instance: ScriptRepository? = null

        fun get(app: Application): ScriptRepository =
            instance ?: synchronized(this) {
                instance ?: ScriptRepository(app).also { instance = it }
            }
    }
}

/** 统一的时间展示格式。 */
fun formatTime(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
