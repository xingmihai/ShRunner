package com.shrunner.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store by preferencesDataStore("shrunner")

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val favKey = stringSetPreferencesKey("favorites")
    private val approvedKey = stringSetPreferencesKey("approved_danger")
    private val tileKey = stringPreferencesKey("tile_script")

    val favorites: Flow<Set<String>> = context.store.data.map { it[favKey] ?: emptySet() }
    val tileScript: Flow<String?> = context.store.data.map { it[tileKey] }

    suspend fun approved(): Set<String> = context.store.data.first()[approvedKey] ?: emptySet()

    suspend fun approve(fingerprints: Set<String>) {
        context.store.edit { it[approvedKey] = (it[approvedKey] ?: emptySet()) + fingerprints }
    }

    suspend fun setFavorite(name: String, favorite: Boolean) {
        context.store.edit {
            val cur = it[favKey] ?: emptySet()
            it[favKey] = if (favorite) cur + name else cur - name
        }
    }

    suspend fun setTileScript(name: String) {
        context.store.edit { it[tileKey] = name }
    }

    suspend fun renameScript(old: String, new: String) {
        context.store.edit {
            val cur = it[favKey] ?: emptySet()
            if (old in cur) it[favKey] = cur - old + new
            if (it[tileKey] == old) it[tileKey] = new
        }
    }

    suspend fun forgetScript(name: String) {
        context.store.edit {
            it[favKey] = (it[favKey] ?: emptySet()) - name
            if (it[tileKey] == name) it.remove(tileKey)
        }
    }
}
