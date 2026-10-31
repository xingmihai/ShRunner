package com.xingmihai.shrunner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.xingmihai.shrunner.data.ScriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditorUiState(
    val id: String? = null,
    val name: String = "",
    val text: String = "",
    val dirty: Boolean = false
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScriptRepository.get(app)

    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui.asStateFlow()

    fun load(id: String?) {
        val current = _ui.value
        if (current.id == id && id != null) return
        if (id == null) {
            _ui.value = EditorUiState(text = NEW_TEMPLATE)
            return
        }
        _ui.value = EditorUiState(id = id, name = id, text = repo.readText(id))
    }

    fun onNameChange(value: String) {
        _ui.value = _ui.value.copy(name = value, dirty = true)
    }

    fun onTextChange(value: String) {
        _ui.value = _ui.value.copy(text = value, dirty = true)
    }

    /** 保存脚本，返回最终脚本 id（可能因为改名而变化）。 */
    fun save(): String {
        val state = _ui.value
        val id = state.id ?: repo.create(state.name.ifBlank { "script" }).id
        val finalId = if (state.name.isNotBlank() && state.name != state.id) {
            repo.rename(id, state.name)
        } else id
        repo.writeText(finalId, state.text)
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
