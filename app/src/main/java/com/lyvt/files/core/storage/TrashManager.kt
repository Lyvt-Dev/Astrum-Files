package com.lyvt.files.core.storage

import android.content.Context
import com.lyvt.files.core.database.TrashDao
import com.lyvt.files.core.database.TrashEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-controlled recycle bin. Items are moved (renamed, so no data is copied) into a ".lyvt-trash" folder on the
 * same volume, which keeps deletion instant and restoration lossless. Limitations: trash content is removed when
 * the app is uninstalled, and files from locations the app cannot write cannot be trashed.
 */
@Singleton
class TrashManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TrashDao,
    private val volumes: StorageVolumes,
) {
    val items = dao.observe()

    private fun trashDirFor(file: File): File {
        val volumeRoot = volumes.volumes().map { it.path }.filter { file.absolutePath.startsWith(it) }.maxByOrNull { it.length }
        val dir = if (volumeRoot != null) {
            // App-specific directory on the same volume => rename() works and no extra permission needed.
            context.getExternalFilesDirs(null).firstOrNull { it != null && it.absolutePath.startsWith(volumeRoot) }
                ?.let { File(it, ".lyvt-trash") } ?: File(context.filesDir, ".lyvt-trash")
        } else File(context.filesDir, ".lyvt-trash")
        dir.mkdirs()
        File(dir, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        return dir
    }

    suspend fun moveToTrash(files: List<File>): OpResult = withContext(Dispatchers.IO) {
        var ok = 0
        val failed = mutableListOf<Pair<String, String>>()
        for (f in files) {
            try {
                if (!f.exists()) throw IOException("No longer exists")
                val size = FileOperations.totalSize(f)
                val dir = trashDirFor(f)
                val target = File(dir, "${System.currentTimeMillis()}_${f.name}")
                if (!f.renameTo(target)) {
                    // Cross-volume fallback: copy then delete original.
                    if (f.isDirectory) f.copyRecursively(target) else f.copyTo(target)
                    if (!f.deleteRecursively()) { target.deleteRecursively(); throw IOException("Could not remove original") }
                }
                dao.insert(TrashEntity(originalPath = f.absolutePath, trashPath = target.absolutePath, name = f.name,
                    size = size, isDirectory = target.isDirectory, deletedAt = System.currentTimeMillis()))
                ok++
            } catch (e: Exception) {
                failed += f.name to (e.message ?: "Error")
            }
        }
        if (failed.isEmpty()) OpResult.Success(ok) else OpResult.Partial(ok, failed)
    }

    suspend fun restore(ids: List<Long>): OpResult = withContext(Dispatchers.IO) {
        var ok = 0
        val failed = mutableListOf<Pair<String, String>>()
        for (e in dao.byIds(ids)) {
            try {
                val src = File(e.trashPath)
                if (!src.exists()) { dao.delete(e.id); throw IOException("Trash copy is missing") }
                val original = File(e.originalPath)
                val parent = original.parentFile ?: throw IOException("Invalid original path")
                if (!parent.exists() && !parent.mkdirs()) throw IOException("Original folder is not available")
                val target = if (original.exists()) FileOperations.uniqueTarget(parent, original.name) else original
                if (!src.renameTo(target)) {
                    if (src.isDirectory) src.copyRecursively(target) else src.copyTo(target)
                    src.deleteRecursively()
                }
                dao.delete(e.id)
                ok++
            } catch (ex: Exception) {
                failed += e.name to (ex.message ?: "Error")
            }
        }
        if (failed.isEmpty()) OpResult.Success(ok) else OpResult.Partial(ok, failed)
    }

    suspend fun deleteForever(ids: List<Long>): OpResult = withContext(Dispatchers.IO) {
        var ok = 0
        for (e in dao.byIds(ids)) {
            val f = File(e.trashPath)
            if (!f.exists() || f.deleteRecursively()) { dao.delete(e.id); ok++ }
        }
        OpResult.Success(ok)
    }

    suspend fun emptyAll(): OpResult = deleteForever(dao.all().map { it.id })

    /** Applies retention days and size limit (oldest first). */
    suspend fun enforcePolicy(retentionDays: Int, limitMb: Int) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        deleteForever(dao.olderThan(cutoff).map { it.id })
        val all = dao.all().sortedBy { it.deletedAt }
        var total = all.sumOf { it.size }
        val limit = limitMb * 1024L * 1024L
        val evict = mutableListOf<Long>()
        for (e in all) { if (total <= limit) break; evict += e.id; total -= e.size }
        if (evict.isNotEmpty()) deleteForever(evict)
    }
}
