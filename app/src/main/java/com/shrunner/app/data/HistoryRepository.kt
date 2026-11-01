package com.shrunner.app.data

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {
    val entries: Flow<List<HistoryEntry>> = dao.observe()
    suspend fun record(entry: HistoryEntry) = dao.insert(entry)
    suspend fun clear() = dao.clear()
}
