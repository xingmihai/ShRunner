package com.shrunner.app.di

import android.content.Context
import androidx.room.Room
import com.shrunner.app.data.AppDatabase
import com.shrunner.app.data.HistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "shrunner.db").build()

    @Provides
    fun historyDao(db: AppDatabase): HistoryDao = db.historyDao()
}
