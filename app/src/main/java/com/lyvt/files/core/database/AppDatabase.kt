package com.lyvt.files.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trash", indices = [Index("deletedAt")])
data class TrashEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,
    val trashPath: String,
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val deletedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val pinned: Boolean = false,
    val color: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "recents", indices = [Index("openedAt")])
data class RecentEntity(
    @PrimaryKey val path: String,
    val name: String,
    val openedAt: Long,
    val openCount: Int = 1,
)

@Entity(tableName = "operations", indices = [Index("timestamp")])
data class OperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val summary: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "snapshots", indices = [Index("volumeId", "timestamp")])
data class StorageSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volumeId: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val timestamp: Long,
)

@Entity(tableName = "tags", primaryKeys = ["path", "tag"], indices = [Index("tag")])
data class TagEntity(val path: String, val tag: String)

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC") fun observe(): Flow<List<TrashEntity>>
    @Query("SELECT * FROM trash") suspend fun all(): List<TrashEntity>
    @Query("SELECT * FROM trash WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<TrashEntity>
    @Query("SELECT * FROM trash WHERE deletedAt < :before") suspend fun olderThan(before: Long): List<TrashEntity>
    @Insert suspend fun insert(e: TrashEntity): Long
    @Query("DELETE FROM trash WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY pinned DESC, name COLLATE NOCASE") fun observe(): Flow<List<FavoriteEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE path = :path") suspend fun delete(path: String)
    @Query("UPDATE favorites SET path = :newPath, name = :newName WHERE path = :oldPath")
    suspend fun rename(oldPath: String, newPath: String, newName: String)
}

@Dao
interface RecentDao {
    @Query("SELECT * FROM recents ORDER BY openedAt DESC LIMIT :limit") fun recent(limit: Int): Flow<List<RecentEntity>>
    @Query("SELECT * FROM recents ORDER BY openCount DESC, openedAt DESC LIMIT :limit") fun frequent(limit: Int): Flow<List<RecentEntity>>
    @Query("SELECT * FROM recents WHERE path = :path") suspend fun get(path: String): RecentEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: RecentEntity)
    @Query("DELETE FROM recents WHERE path = :path") suspend fun delete(path: String)
    @Query("DELETE FROM recents") suspend fun clear()
}

@Dao
interface OperationDao {
    @Query("SELECT * FROM operations ORDER BY timestamp DESC LIMIT 200") fun observe(): Flow<List<OperationEntity>>
    @Insert suspend fun insert(e: OperationEntity)
    @Query("DELETE FROM operations") suspend fun clear()
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM snapshots WHERE volumeId = :volumeId ORDER BY timestamp ASC") fun observe(volumeId: String): Flow<List<StorageSnapshotEntity>>
    @Query("SELECT * FROM snapshots WHERE volumeId = :volumeId ORDER BY timestamp DESC LIMIT 1") suspend fun latest(volumeId: String): StorageSnapshotEntity?
    @Insert suspend fun insert(e: StorageSnapshotEntity)
}

@Dao
interface TagDao {
    @Query("SELECT DISTINCT tag FROM tags ORDER BY tag") fun allTags(): Flow<List<String>>
    @Query("SELECT tag FROM tags WHERE path = :path") fun tagsFor(path: String): Flow<List<String>>
    @Query("SELECT path FROM tags WHERE tag = :tag") fun pathsFor(tag: String): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(e: TagEntity)
    @Query("DELETE FROM tags WHERE path = :path AND tag = :tag") suspend fun remove(path: String, tag: String)
}

@Database(
    entities = [TrashEntity::class, FavoriteEntity::class, RecentEntity::class, OperationEntity::class,
        StorageSnapshotEntity::class, TagEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trash(): TrashDao
    abstract fun favorites(): FavoriteDao
    abstract fun recents(): RecentDao
    abstract fun operations(): OperationDao
    abstract fun snapshots(): SnapshotDao
    abstract fun tags(): TagDao
}
