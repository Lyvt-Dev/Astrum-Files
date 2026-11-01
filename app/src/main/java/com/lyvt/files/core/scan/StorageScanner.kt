package com.lyvt.files.core.scan

import com.lyvt.files.core.storage.FastFs
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.core.storage.ScannedFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue

data class FolderStat(val path: String, val name: String, val size: Long, val files: Int)

data class ScanResult(
    val root: String,
    val scannedAt: Long,
    val totalBytes: Long,
    val fileCount: Int,
    val folderCount: Int,
    val byCategory: Map<FileCategory, Pair<Long, Int>>,
    val byExtension: List<Triple<String, Long, Int>>,
    val largestFiles: List<FileItem>,
    val topFolders: List<FolderStat>,
    val emptyFolders: List<String>,
    /** Files with size/date cached from the scan ([ScannedFile]) — no extra disk access when sorting/filtering. */
    val allFiles: List<File>,
    val inaccessible: Int,
)

/**
 * Single-pass walk (java.nio walkFileTree: one attribute read per entry, symlinks not followed so no loops)
 * collecting everything analytics and cleaner need. Inaccessible directories are counted, not guessed.
 */
object StorageScanner {
    suspend fun scan(root: File, exclusions: Set<String> = emptySet(), onProgress: (files: Int, bytes: Long) -> Unit = { _, _ -> }): ScanResult {
        val job = currentCoroutineContext()[Job]
        val cat = HashMap<FileCategory, LongArray>()
        val ext = HashMap<String, LongArray>()
        val largest = PriorityQueue<ScannedFile>(compareBy { it.length() })
        val topLevel = HashMap<String, LongArray>()
        val empty = mutableListOf<String>()
        val all = ArrayList<File>(1 shl 14)
        var total = 0L; var files = 0; var folders = 0; var denied = 0
        val rootPath = root.toPath()
        val childCounts = ArrayDeque<IntArray>()
        var top: String? = null
        var lastReport = 0L

        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (job?.isActive == false) return FileVisitResult.TERMINATE
                val s = dir.toString()
                if (dir != rootPath && exclusions.any { s.startsWith(it) }) return FileVisitResult.SKIP_SUBTREE
                childCounts.lastOrNull()?.let { it[0]++ }
                childCounts.addLast(IntArray(1)); folders++
                if (childCounts.size == 2) top = dir.fileName?.toString()
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                val c = childCounts.removeLastOrNull()
                if (exc != null) denied++ else if (c != null && c[0] == 0 && dir != rootPath) empty += dir.toString()
                if (childCounts.size == 1) top = null
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (file != rootPath) { denied++; childCounts.lastOrNull()?.let { it[0]++ } }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                childCounts.lastOrNull()?.let { it[0]++ }
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val len = attrs.size()
                val f = ScannedFile(file.toString(), len, attrs.lastModifiedTime().toMillis())
                total += len; files++
                all += f
                val name = f.name
                val e = name.substringAfterLast('.', "").lowercase()
                cat.getOrPut(FileCategory.of(e)) { LongArray(2) }.let { it[0] += len; it[1]++ }
                ext.getOrPut(e.ifEmpty { "(none)" }) { LongArray(2) }.let { it[0] += len; it[1]++ }
                if (largest.size < 100) largest.add(f) else if (len > largest.peek()!!.length()) { largest.poll(); largest.add(f) }
                top?.let { t -> topLevel.getOrPut(t) { LongArray(2) }.let { it[0] += len; it[1]++ } }
                if (files and 511 == 0) {
                    val now = System.nanoTime()
                    if (now - lastReport > 50_000_000) { lastReport = now; onProgress(files, total) }
                    if (job?.isActive == false) return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }
        })
        job?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException("Scan cancelled") }
        onProgress(files, total)
        return ScanResult(
            root = root.absolutePath,
            scannedAt = System.currentTimeMillis(),
            totalBytes = total, fileCount = files, folderCount = folders,
            byCategory = cat.mapValues { it.value[0] to it.value[1].toInt() },
            byExtension = ext.map { Triple(it.key, it.value[0], it.value[1].toInt()) }.sortedByDescending { it.second },
            largestFiles = largest.toList().sortedByDescending { it.length() }.map { FileItem.from(it) },
            topFolders = topLevel.map { FolderStat(File(root, it.key).absolutePath, it.key, it.value[0], it.value[1].toInt()) }.sortedByDescending { it.size },
            emptyFolders = empty,
            allFiles = all,
            inaccessible = denied,
        )
    }
}
