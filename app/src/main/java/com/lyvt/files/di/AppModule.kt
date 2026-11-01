package com.lyvt.files.di

import android.content.Context
import androidx.room.Room
import com.lyvt.files.core.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun db(@ApplicationContext c: Context): AppDatabase = Room.databaseBuilder(c, AppDatabase::class.java, "lyvt.db").build()
    @Provides fun trash(db: AppDatabase) = db.trash()
    @Provides fun favorites(db: AppDatabase) = db.favorites()
    @Provides fun recents(db: AppDatabase) = db.recents()
    @Provides fun ops(db: AppDatabase) = db.operations()
    @Provides fun snapshots(db: AppDatabase) = db.snapshots()
    @Provides fun tags(db: AppDatabase) = db.tags()
    @Provides fun organizer(db: AppDatabase) = db.organizer()
    @Provides fun notes(db: AppDatabase) = db.notes()
    @Provides fun collections(db: AppDatabase) = db.collections()
    @Provides fun searches(db: AppDatabase) = db.searches()
    @Provides fun connections(db: AppDatabase) = db.connections()
    @Provides fun transfers(db: AppDatabase) = db.transfers()
}
