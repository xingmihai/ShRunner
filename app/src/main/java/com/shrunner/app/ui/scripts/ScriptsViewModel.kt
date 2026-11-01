package com.shrunner.app.ui.scripts

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.shrunner.app.data.AppPreferences
import com.shrunner.app.data.Script
import com.shrunner.app.data.ScriptRepository
import com.shrunner.app.ui.common.BaseViewModel
import com.shrunner.app.ui.common.UiState
import com.shrunner.app.ui.common.asUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScriptsViewModel @Inject constructor(
    private val repo: ScriptRepository,
    private val prefs: AppPreferences,
) : BaseViewModel() {

    private val retryTick = MutableStateFlow(0)

    val state: StateFlow<UiState<List<Script>>> = retryTick
        .flatMapLatest { repo.scripts.asUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun retry() = retryTick.update { it + 1 }

    fun toggleFavorite(script: Script) = launchCatching { prefs.setFavorite(script.name, !script.favorite) }
    fun create(name: String, onCreated: (String) -> Unit) = launchCatching { onCreated(repo.create(name)) }
    fun import(uri: Uri, onDone: (String) -> Unit) = launchCatching { onDone(repo.import(uri)) }
    fun rename(old: String, new: String) = launchCatching { repo.rename(old, new) }
    fun delete(name: String) = launchCatching { repo.delete(name) }
    fun setTileTarget(name: String) = launchCatching { prefs.setTileScript(name) }
}
