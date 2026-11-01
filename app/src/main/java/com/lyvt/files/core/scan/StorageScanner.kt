package com.lyvt.files.core.scan

import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
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
    val allFiles: List<File>,
    val inaccessible: Int,
)

/**
 * Single-pass walk of an accessible root collecting everything analytics and cleaner need.
 * Only reports what the app can actually read; inaccessible directories are counted, not guessed.
 */
object StorageScanner {
    suspend fun scan(root: File, exclusions: Set<String> = emptySet(), onProgress: (Int) -> Unit = {}): ScanResult {
        val cat = HashMap<FileCategory, LongArray>()
        val ext = HashMap<String, LongArray>()
        val largest = PriorityQueue<File>(compareBy { it.length() })
        val topLevel = HashMap<String, LongArray>()
        val empty = mutableListOf<String>()
        val all = ArrayList<File>()
        var total = 0L; var files = 0; var folders = 0; var denied = 0
        val rootCanon = root.canonicalPath
        val visited = HashSet<String>()
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val canon = runCatching { dir.canonicalPath }.getOrNull() ?: continue
            if (!visited.add(canon) || exclusions.any { canon.startsWith(it) }) continue
            val children = dir.listFiles()
            if (children == null) { denied++; continue }
            folders++
            if (children.isEmpty() && dir != root) empty += dir.absolutePath
            for (c in children) {
                if (c.isDirectory) { stack.add(c); continue }
                val len = c.length()
                total += len; files++
                all += c
                val e = c.extension.lowercase()
                cat.getOrPut(FileCategory.of(e)) { LongArray(2) }.let { it[0] += len; it[1]++ }
                ext.getOrPut(e.ifEmpty { "(none)" }) { LongArray(2) }.let { it[0] += len; it[1]++ }
                largest.add(c); if (largest.size > 100) largest.poll()
                val rel = c.absolutePath.removePrefix(rootCanon).removePrefix(root.absolutePath).trimStart('/')
                val top = rel.substringBefore('/', "")
                if (top.isNotEmpty() && rel.contains('/')) topLevel.getOrPut(top) { LongArray(2) }.let { it[0] += len; it[1]++ }
                if (files % 500 == 0) onProgress(files)
            }
        }
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
