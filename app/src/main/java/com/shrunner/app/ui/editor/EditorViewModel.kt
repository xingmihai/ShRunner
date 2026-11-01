package com.shrunner.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.shrunner.app.data.ScriptRepository
import com.shrunner.app.ui.common.BaseViewModel
import com.shrunner.app.ui.common.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: ScriptRepository,
) : BaseViewModel() {

    val name: String = checkNotNull(handle.get<String>("name"))

    var loadState by mutableStateOf<UiState<Unit>>(UiState.Loading)
        private set
    var text by mutableStateOf("")
        private set
    private var saved by mutableStateOf("")

    val dirty: Boolean get() = text != saved

    init { load() }

    fun load() {
        viewModelScope.launch {
            loadState = UiState.Loading
            loadState = try {
                val content = repo.read(name)
                text = content
                saved = content
                UiState.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't open the script.")
            }
        }
    }

    fun onTextChange(value: String) {
        text = value
    }

    fun save(onSaved: () -> Unit = {}) = launchCatching {
        val snapshot = text
        repo.write(name, snapshot)
        saved = snapshot
        onSaved()
    }
}
