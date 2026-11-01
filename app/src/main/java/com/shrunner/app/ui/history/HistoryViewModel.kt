package com.shrunner.app.ui.history

import androidx.lifecycle.viewModelScope
import com.shrunner.app.data.HistoryEntry
import com.shrunner.app.data.HistoryRepository
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
class HistoryViewModel @Inject constructor(private val repo: HistoryRepository) : BaseViewModel() {
    private val retryTick = MutableStateFlow(0)

    val state: StateFlow<UiState<List<HistoryEntry>>> = retryTick
        .flatMapLatest { repo.entries.asUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun retry() = retryTick.update { it + 1 }
    fun clear() = launchCatching { repo.clear() }
}
