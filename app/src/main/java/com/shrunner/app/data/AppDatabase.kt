package com.shrunner.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history ORDER BY startedAt DESC LIMIT 500")
    fun observe(): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
