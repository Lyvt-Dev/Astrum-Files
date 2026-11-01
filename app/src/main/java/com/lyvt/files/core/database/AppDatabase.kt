package com.lyvt.files.core.database

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Update
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
    /** Accessible file count at snapshot time, -1 if not measured (added in v2). */
    @ColumnInfo(defaultValue = "-1") val fileCount: Int = -1,
    /** JSON map category -> bytes (added in v2). */
    @ColumnInfo(defaultValue = "") val categoryJson: String = "",
)

@Entity(tableName = "tags", primaryKeys = ["path", "tag"], indices = [Index("tag")])
data class TagEntity(val path: String, val tag: String)

/** File organizer rule. Source/destination folders are always chosen explicitly by the user. */
@Entity(tableName = "organizer_rules")
data class OrganizerRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceDir: String,
    /** Destination template, may contain {ext} {category} {yyyy} {MM} {lang}. Relative paths resolve against sourceDir. */
    val destination: String,
    val extensions: String = "",          // comma separated, empty = any
    val namePattern: String = "",         // regex, empty = any
    val minSizeKb: Long = 0,
    val maxSizeKb: Long = 0,              // 0 = unlimited
    val olderThanDays: Int = 0,
    val recursive: Boolean = false,
    val enabled: Boolean = true,
    /** Runs from the background scheduler only when the user explicitly enabled it. */
    val automatic: Boolean = false,
    val lastRun: Long = 0,
)

@Entity(tableName = "organizer_runs", indices = [Index("timestamp")])
data class OrganizerRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleName: String,
    val timestamp: Long,
    /** JSON array of [from, to] moves, used for undo. */
    val journal: String,
    val moved: Int,
    val failed: Int,
    val undone: Boolean = false,
)

@Entity(tableName = "notes")
data class NoteEntity(@PrimaryKey val path: String, val note: String, val size: Long, val updatedAt: Long)

@Entity(tableName = "collections")
data class CollectionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val color: Int = 0, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "collection_items", primaryKeys = ["collectionId", "path"], indices = [Index("path")])
data class CollectionItemEntity(val collectionId: Long, val path: String, val size: Long = 0, val addedAt: Long = System.currentTimeMillis())

@Entity(tableName = "search_history", indices = [Index("at")])
data class SearchEntity(@PrimaryKey val query: String, val saved: Boolean = false, val at: Long = System.currentTimeMillis())

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: String,      // SFTP | SMB | WEBDAV | FTP
    val host: String,
    val port: Int,
    val username: String,
    /** Keystore-encrypted password (base64). Never stored in plaintext. */
    val secret: String,
    val path: String = "/",
    val share: String = "",
    /** SFTP host key fingerprint pinned on first successful, user-approved connect. */
    val hostKey: String = "",
)

@Entity(tableName = "transfers", indices = [Index("at")])
data class TransferEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val direction: String, val name: String, val size: Long, val peer: String, val success: Boolean, val at: Long = System.currentTimeMillis())

@Dao
interface OrganizerDao {
    @Query("SELECT * FROM organizer_rules ORDER BY name COLLATE NOCASE") fun rules(): Flow<List<OrganizerRuleEntity>>
    @Query("SELECT * FROM organizer_rules WHERE automatic = 1 AND enabled = 1") suspend fun automaticRules(): List<OrganizerRuleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(r: OrganizerRuleEntity): Long
    @Query("DELETE FROM organizer_rules WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM organizer_runs ORDER BY timestamp DESC LIMIT 50") fun runs(): Flow<List<OrganizerRunEntity>>
    @Insert suspend fun insertRun(r: OrganizerRunEntity): Long
    @Update suspend fun updateRun(r: OrganizerRunEntity)
}

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes WHERE path = :path") fun note(path: String): Flow<NoteEntity?>
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun all(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes") suspend fun allNow(): List<NoteEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(n: NoteEntity)
    @Query("DELETE FROM notes WHERE path = :path") suspend fun delete(path: String)
    @Query("UPDATE notes SET path = :to WHERE path = :from") suspend fun move(from: String, to: String)
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE") fun all(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM collection_items WHERE collectionId = :id ORDER BY addedAt DESC") fun items(id: Long): Flow<List<CollectionItemEntity>>
    @Query("SELECT * FROM collection_items") suspend fun allItems(): List<CollectionItemEntity>
    @Insert suspend fun create(c: CollectionEntity): Long
    @Query("DELETE FROM collections WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM collection_items WHERE collectionId = :id") suspend fun clear(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(i: CollectionItemEntity)
    @Query("DELETE FROM collection_items WHERE collectionId = :id AND path = :path") suspend fun remove(id: Long, path: String)
    @Query("UPDATE collection_items SET path = :to WHERE path = :from") suspend fun move(from: String, to: String)
}

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_history ORDER BY saved DESC, at DESC LIMIT 30") fun recent(): Flow<List<SearchEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: SearchEntity)
    @Query("DELETE FROM search_history WHERE saved = 0") suspend fun clearHistory()
    @Query("DELETE FROM search_history WHERE `query` = :q") suspend fun delete(q: String)
}

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY name COLLATE NOCASE") fun all(): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connections WHERE id = :id") suspend fun get(id: Long): ConnectionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: ConnectionEntity): Long
    @Query("DELETE FROM connections WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY at DESC LIMIT 100") fun all(): Flow<List<TransferEntity>>
    @Insert suspend fun insert(t: TransferEntity)
}

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
    @Query("SELECT * FROM snapshots ORDER BY timestamp ASC") fun observeAll(): Flow<List<StorageSnapshotEntity>>
}

@Dao
interface TagDao {
    @Query("SELECT DISTINCT tag FROM tags ORDER BY tag") fun allTags(): Flow<List<String>>
    @Query("SELECT tag FROM tags WHERE path = :path") fun tagsFor(path: String): Flow<List<String>>
    @Query("SELECT path FROM tags WHERE tag = :tag") fun pathsFor(tag: String): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(e: TagEntity)
    @Query("DELETE FROM tags WHERE path = :path AND tag = :tag") suspend fun remove(path: String, tag: String)
    @Query("SELECT * FROM tags") suspend fun allNow(): List<TagEntity>
    @Query("UPDATE tags SET path = :to WHERE path = :from") suspend fun move(from: String, to: String)
}

@Database(
    entities = [TrashEntity::class, FavoriteEntity::class, RecentEntity::class, OperationEntity::class,
        StorageSnapshotEntity::class, TagEntity::class, OrganizerRuleEntity::class, OrganizerRunEntity::class, NoteEntity::class,
        CollectionEntity::class, CollectionItemEntity::class, SearchEntity::class, ConnectionEntity::class, TransferEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trash(): TrashDao
    abstract fun favorites(): FavoriteDao
    abstract fun recents(): RecentDao
    abstract fun operations(): OperationDao
    abstract fun snapshots(): SnapshotDao
    abstract fun tags(): TagDao
    abstract fun organizer(): OrganizerDao
    abstract fun notes(): NotesDao
    abstract fun collections(): CollectionDao
    abstract fun searches(): SearchDao
    abstract fun connections(): ConnectionDao
    abstract fun transfers(): TransferDao
}
