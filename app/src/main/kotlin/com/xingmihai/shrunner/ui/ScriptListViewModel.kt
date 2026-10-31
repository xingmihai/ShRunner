package com.xingmihai.shrunner.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingmihai.shrunner.data.ScriptEntry
import com.xingmihai.shrunner.data.ScriptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScriptListViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScriptRepository.get(app)

    private val _scripts = MutableStateFlow<List<ScriptEntry>>(emptyList())
    val scripts: StateFlow<List<ScriptEntry>> = _scripts.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _scripts.value = repo.list() }
    }

    fun create(name: String): String {
        val entry = repo.create(name)
        refresh()
        return entry.id
    }

    fun import(uri: Uri, name: String) {
        repo.import(uri, name)
        refresh()
    }

    fun delete(id: String) {
        repo.delete(id)
        refresh()
    }

    fun toggleFavorite(id: String) {
        repo.toggleFavorite(id)
        refresh()
    }
}
