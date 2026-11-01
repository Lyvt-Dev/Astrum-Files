package com.lyvt.files.core.storage

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.lyvt.files.core.database.FavoriteDao
import com.lyvt.files.core.database.FavoriteEntity
import com.lyvt.files.core.database.OperationDao
import com.lyvt.files.core.database.OperationEntity
import com.lyvt.files.core.database.RecentDao
import com.lyvt.files.core.database.RecentEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ListResult {
    data class Ok(val items: List<FileItem>) : ListResult
    data object NotFound : ListResult
    data object AccessDenied : ListResult
}

@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favorites: FavoriteDao,
    private val recents: RecentDao,
    private val operations: OperationDao,
    val trash: TrashManager,
    private val library: LibraryRepository,
) {
    val favoriteList = favorites.observe()
    fun recentFiles(limit: Int = 20) = recents.recent(limit)
    fun frequentFiles(limit: Int = 10) = recents.frequent(limit)
    val history = operations.observe()

    suspend fun list(path: String, showHidden: Boolean, sort: SortOrder): ListResult = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists()) return@withContext ListResult.NotFound
        val children = dir.listFiles() ?: return@withContext ListResult.AccessDenied
        ListResult.Ok(
            children.asSequence()
                .filter { showHidden || !it.name.startsWith(".") }
                .filterNot { it.name.endsWith(FileOperations.PART_SUFFIX) }
                .map { FileItem.from(it, countChildren = true) }
                .sortedWith(sort.comparator())
                .toList()
        )
    }

    private suspend fun log(type: String, summary: String, success: Boolean) =
        operations.insert(OperationEntity(type = type, summary = summary, success = success))

    private fun scan(vararg paths: String) {
        runCatching { MediaScannerConnection.scanFile(context, paths, null, null) }
    }

    suspend fun createFolder(parent: String, name: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching { FileOperations.createFolder(File(parent), name) }.also { log("create", "Folder $name", it.isSuccess) }
    }

    suspend fun createFile(parent: String, name: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching { FileOperations.createFile(File(parent), name) }.also { log("create", "File $name", it.isSuccess) }
    }

    suspend fun rename(path: String, newName: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching { FileOperations.rename(File(path), newName) }.onSuccess {
            favorites.rename(path, it.absolutePath, it.name)
            library.onMoved(path, it.absolutePath)
            scan(path, it.absolutePath)
        }.also { log("rename", "${File(path).name} → $newName", it.isSuccess) }
    }

    suspend fun transfer(
        paths: List<String>, dest: String, move: Boolean, policy: ConflictPolicy,
        onProgress: (TransferProgress) -> Unit,
    ): OpResult = withContext(Dispatchers.IO) {
        val files = paths.map(::File)
        val r = if (move) FileOperations.move(files, File(dest), policy, onProgress) else FileOperations.copy(files, File(dest), policy, onProgress)
        log(if (move) "move" else "copy", "${files.size} item(s) → ${File(dest).name}", r !is OpResult.Failure)
        if (move) files.forEach { f -> val t = File(dest, f.name); if (!f.exists() && t.exists()) library.onMoved(f.absolutePath, t.absolutePath) }
        scan(dest)
        r
    }

    suspend fun duplicate(path: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching { FileOperations.duplicate(File(path)) }.also { log("duplicate", File(path).name, it.isSuccess) }
    }

    /** Deletes via trash when [useTrash], otherwise permanently. Always called after explicit user confirmation. */
    suspend fun delete(paths: List<String>, useTrash: Boolean): OpResult = withContext(Dispatchers.IO) {
        val files = paths.map(::File)
        val r = if (useTrash) trash.moveToTrash(files) else {
            var ok = 0
            val failed = mutableListOf<Pair<String, String>>()
            files.forEach { f -> if (f.deleteRecursively()) ok++ else failed += f.name to "Could not delete" }
            if (failed.isEmpty()) OpResult.Success(ok) else OpResult.Partial(ok, failed)
        }
        paths.forEach { favorites.delete(it); recents.delete(it) }
        log(if (useTrash) "trash" else "delete", "${files.size} item(s)", r !is OpResult.Failure)
        scan(*paths.toTypedArray())
        r
    }

    suspend fun toggleFavorite(item: FileItem, isFav: Boolean) {
        if (isFav) favorites.delete(item.path) else favorites.upsert(FavoriteEntity(item.path, item.name, item.isDirectory))
    }

    suspend fun setPinned(item: FileItem, pinned: Boolean, color: Int = 0) =
        favorites.upsert(FavoriteEntity(item.path, item.name, item.isDirectory, pinned = pinned, color = color))

    suspend fun recordOpen(path: String) {
        val prev = recents.get(path)
        recents.upsert(RecentEntity(path, File(path).name, System.currentTimeMillis(), (prev?.openCount ?: 0) + 1))
    }

    suspend fun folderSize(path: String): Pair<Long, Int> = withContext(Dispatchers.IO) {
        var size = 0L
        var count = 0
        File(path).walkTopDown().onFail { _, _ -> }.forEach { if (it.isFile) { size += it.length(); count++ } }
        size to count
    }

    fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun openExternal(file: File): String? = try {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), mimeType(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        "No app can open this file type"
    } catch (e: IllegalArgumentException) {
        "This location cannot be shared with other apps"
    }

    fun share(files: List<File>): String? = try {
        val uris = ArrayList(files.filter { it.isFile }.map(::uriFor))
        if (uris.isEmpty()) throw IOException("Folders cannot be shared directly — compress them first")
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeType(files.first { it.isFile }))
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        intent.clipData = ClipData.newRawUri(null, uris[0])
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: Exception) {
        e.message ?: "Sharing failed"
    }
}
