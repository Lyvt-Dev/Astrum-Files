package com.lyvt.files.core.scan

import com.lyvt.files.core.storage.Hashing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

data class DuplicateGroup(val hash: String, val size: Long, val files: List<File>) {
    /** Bytes reclaimable if all but one copy are removed. */
    val wasted: Long get() = size * (files.size - 1)
}

/**
 * Exact duplicate detection: (1) group by size, (2) filter by SHA-256 of the first 16 KiB, (3) full streaming
 * SHA-256, (4) byte-for-byte equality is implied by a full SHA-256 collision-resistant match.
 */
object DuplicateFinder {
    suspend fun find(files: List<File>, minSize: Long = 1, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<DuplicateGroup> {
        val bySize = files.asSequence().filter { it.isFile && it.length() >= minSize }.groupBy { it.length() }.filterValues { it.size > 1 }
        val candidates = bySize.values.sumOf { it.size }
        var processed = 0
        val result = mutableListOf<DuplicateGroup>()
        for ((size, group) in bySize) {
            currentCoroutineContext().ensureActive()
            val byPartial = group.groupBy { runCatching { Hashing.partial(it) }.getOrNull() }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (pg in byPartial) {
                val byFull = if (size <= 16 * 1024) mapOf(Hashing.partial(pg[0]) to pg)
                else pg.groupBy { runCatching { Hashing.sha256(it) }.getOrNull() }.filterKeys { it != null }.mapKeys { it.key!! }
                byFull.filterValues { it.size > 1 }.forEach { (h, fs) -> result += DuplicateGroup(h, size, fs.sortedBy { it.lastModified() }) }
            }
            processed += group.size
            onProgress(processed, candidates)
        }
        return result.sortedByDescending { it.wasted }
    }

    /** Selection that keeps the oldest copy of each group — never selects every copy. */
    fun defaultSelection(groups: List<DuplicateGroup>): Set<String> = groups.flatMap { g -> g.files.drop(1).map { it.absolutePath } }.toSet()

    /** Safety check: returns groups where the selection would remove every copy. */
    fun groupsFullySelected(groups: List<DuplicateGroup>, selected: Set<String>) = groups.filter { g -> g.files.all { it.absolutePath in selected } }
}
