package com.xingmihai.shrunner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingmihai.shrunner.data.ScriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val EDITOR_MAX_CHARS = 400_000
const val EDITOR_PREVIEW_CHARS = 50_000

data class EditorUiState(
    val id: String? = null,
    val name: String = "",
    val text: String = "",
    val dirty: Boolean = false,
    val loading: Boolean = false,
    /** 超过可编辑上限时为 true：这种脚本通常内嵌编码数据，编辑没有意义且必然卡顿。 */
    val readOnly: Boolean = false,
    /** 只读模式下展示的字节数（文本被截断，让用户知道真实大小）。 */
    val fullSizeBytes: Long = 0L
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScriptRepository.get(app)

    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui.asStateFlow()

    /**
     * 加载脚本。
     *
     * 必须在 IO 线程：大脚本（10MB+）在主线程读取会直接 ANR。
     * 超过上限时不读全文，只取一小段做只读预览。
     */
    fun load(id: String?) {
        val current = _ui.value
        if (current.id == id && id != null) return
        if (id == null) {
            _ui.value = EditorUiState(text = NEW_TEMPLATE)
            return
        }
        viewModelScope.launch {
            _ui.value = EditorUiState(id = id, name = id, loading = true)
            val loaded = withContext(Dispatchers.IO) { loadFromDisk(id) }
            _ui.value = EditorUiState(
                id = id,
                name = id,
                text = loaded.first,
                readOnly = loaded.third > EDITOR_MAX_CHARS,
                fullSizeBytes = loaded.second
            )
        }
    }

    /** 返回 (内容或预览, 真实字节数, 真实字符数)。 */
    private fun loadFromDisk(id: String): Triple<String, Long, Int> {
        val size = repo.sizeOf(id)
        // 超大文件：只取开头一段做预览，不把全文读进内存
        if (size > EDITOR_MAX_CHARS) {
            val head = repo.readHead(id, EDITOR_PREVIEW_CHARS)
            return Triple(head, size, size.toInt())
        }
        val text = repo.readText(id)
        return Triple(text, size, text.length)
    }

    fun onNameChange(value: String) {
        if (_ui.value.readOnly) return
        _ui.value = _ui.value.copy(name = value, dirty = true)
    }

    fun onTextChange(value: String) {
        if (_ui.value.readOnly) return
        _ui.value = _ui.value.copy(text = value, dirty = true)
    }

    /** 保存脚本，返回最终脚本 id（可能因为改名而变化）。 */
    fun save(): String {
        val state = _ui.value
        if (state.readOnly) return state.id.orEmpty()
        val id = state.id ?: repo.create(state.name.ifBlank { "script" }).id
        val finalId = if (state.name.isNotBlank() && state.name != state.id) {
            repo.rename(id, state.name)
        } else id
        // 写文件同样不能在 Discuss 主线程
        viewModelScope.launch(Dispatchers.IO) {
            repo.writeText(finalId, state.text)
        }
        _ui.value = state.copy(id = finalId, name = finalId, dirty = false)
        return finalId
    }

    companion object {
        private val NEW_TEMPLATE = """
            #!/system/bin/sh
            # 在这里写你的脚本
            echo "Hello from ShRunner"
            sleep 1
            echo "done"
        """.trimIndent() + "\n"
    }
}
