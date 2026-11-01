package com.lyvt.files.core.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** Progress of a running transfer in bytes and items. */
data class TransferProgress(val bytesDone: Long, val bytesTotal: Long, val currentName: String, val itemsDone: Int, val itemsTotal: Int) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * Pure java.io file operations. Android-independent so it can be unit tested.
 * Copies are written to a temporary ".lyvtpart" file and renamed atomically on completion, so an interrupted
 * transfer never leaves a truncated file under the real name; restarting simply redoes that file.
 */
object FileOperations {
    private const val BUFFER = 256 * 1024
    const val PART_SUFFIX = ".lyvtpart"

    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000]")

    fun validateName(name: String): String? = when {
        name.isBlank() -> "Name cannot be empty"
        name == "." || name == ".." -> "Reserved name"
        illegal.containsMatchIn(name) -> "Name contains invalid characters"
        name.toByteArray().size > 255 -> "Name is too long"
        else -> null
    }

    /** "photo.jpg" -> "photo (1).jpg", "photo (2).jpg", ... */
    fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var i = 1
        while (candidate.exists()) candidate = File(dir, "$base ($i)$ext").also { i++ }
        return candidate
    }

    fun totalSize(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    fun countItems(f: File): Int = if (f.isDirectory) f.walkTopDown().count() else 1

    fun createFolder(parent: File, name: String): File {
        validateName(name)?.let { throw IOException(it) }
        val f = File(parent, name)
        if (f.exists()) throw IOException("\"$name\" already exists")
        if (!f.mkdir()) throw IOException("Could not create folder (no write access?)")
        return f
    }

    fun createFile(parent: File, name: String): File {
        validateName(name)?.let { throw IOException(it) }
        val f = File(parent, name)
        if (f.exists()) throw IOException("\"$name\" already exists")
        if (!f.createNewFile()) throw IOException("Could not create file")
        return f
    }

    fun rename(f: File, newName: String): File {
        validateName(newName)?.let { throw IOException(it) }
        if (!f.exists()) throw IOException("\"${f.name}\" no longer exists")
        val target = File(f.parentFile, newName)
        if (target.exists() && !target.path.equals(f.path, ignoreCase = true)) throw IOException("\"$newName\" already exists")
        if (!f.renameTo(target)) throw IOException("Rename failed")
        return target
    }

    /** Resolve destination for [source] inside [destDir] according to [policy]; null means skip. */
    fun resolveTarget(source: File, destDir: File, policy: ConflictPolicy): File? {
        val t = File(destDir, source.name)
        if (!t.exists()) return t
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> uniqueTarget(destDir, source.name)
            ConflictPolicy.REPLACE -> t
        }
    }

    suspend fun copy(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: (TransferProgress) -> Unit = {},
    ): OpResult = transfer(sources, destDir, policy, move = false, onProgress)

    suspend fun move(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        onProgress: (TransferProgress) -> Unit = {},
    ): OpResult = transfer(sources, destDir, policy, move = true, onProgress)

    private suspend fun transfer(
        sources: List<File>,
        destDir: File,
        policy: ConflictPolicy,
        move: Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): OpResult {
        if (!destDir.isDirectory) return OpResult.Failure("Destination folder is not available")
        val total = sources.sumOf { totalSize(it) }
        if (!move && destDir.usableSpace in 1 until total) return OpResult.Failure("Not enough free space at destination")
        var done = 0L
        var count = 0
        val failed = mutableListOf<Pair<String, String>>()
        for (src in sources) {
            currentCoroutineContext().ensureActive()
            try {
                if (!src.exists()) throw IOException("No longer exists")
                val canonDest = destDir.canonicalPath + File.separator
                if (src.isDirectory && canonDest.startsWith(src.canonicalPath + File.separator)) {
                    throw IOException("Cannot copy a folder into itself")
                }
                val target = resolveTarget(src, destDir, policy) ?: continue
                if (target.canonicalPath == src.canonicalPath) {
                    if (move) continue else throw IOException("Source and destination are the same")
                }
                if (target.exists() && policy == ConflictPolicy.REPLACE) {
                    if (!target.deleteRecursively()) throw IOException("Could not replace existing item")
                }
                // Fast path: same-filesystem rename.
                if (move && src.renameTo(target)) {
                    done += totalSize(target)
                } else {
                    copyRecursive(src, target) { n -> done += n; onProgress(TransferProgress(done, total, src.name, count, sources.size)) }
                    if (move && !src.deleteRecursively()) throw IOException("Copied, but the original could not be removed")
                }
                count++
                onProgress(TransferProgress(done, total, src.name, count, sources.size))
            } catch (e: IOException) {
                failed += src.name to (e.message ?: "I/O error")
            } catch (e: SecurityException) {
                failed += src.name to "Access denied"
            }
        }
        return if (failed.isEmpty()) OpResult.Success(count) else if (count == 0 && failed.size == sources.size && sources.size == 1)
            OpResult.Failure("${failed[0].first}: ${failed[0].second}") else OpResult.Partial(count, failed)
    }

    private suspend fun copyRecursive(src: File, target: File, onBytes: (Long) -> Unit) {
        if (src.isDirectory) {
            if (!target.exists() && !target.mkdirs()) throw IOException("Could not create ${target.name}")
            src.listFiles()?.forEach { child -> copyRecursive(child, File(target, child.name), onBytes) }
            target.setLastModified(src.lastModified())
            return
        }
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        try {
            src.inputStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                    out.fd.sync()
                }
            }
            if (!part.renameTo(target)) throw IOException("Could not finalize ${target.name}")
            target.setLastModified(src.lastModified())
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    fun duplicate(f: File): File {
        val target = uniqueTarget(f.parentFile!!, f.name)
        if (f.isDirectory) f.copyRecursively(target) else f.copyTo(target)
        return target
    }
}
