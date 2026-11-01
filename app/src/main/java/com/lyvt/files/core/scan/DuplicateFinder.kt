package com.lyvt.files.core.scan

import com.lyvt.files.core.storage.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
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
    /** Parallel hashing (several files at once) — disks/SSDs are much faster with a few reads in flight. */
    suspend fun find(files: List<File>, minSize: Long = 1, parallelism: Int = 4, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<DuplicateGroup> = coroutineScope {
        val bySize = files.asSequence().filter { it.isFile && it.length() >= minSize }.groupBy { it.length() }.filterValues { it.size > 1 }
        val candidates = bySize.values.sumOf { it.size }
        val processed = AtomicInteger(0)
        val io = Dispatchers.IO.limitedParallelism(parallelism)
        suspend fun hashAll(fs: List<File>, h: suspend (File) -> String): Map<String, List<File>> =
            fs.map { f -> async(io) { runCatching { h(f) }.getOrNull()?.let { it to f } } }.awaitAll().filterNotNull().groupBy({ it.first }, { it.second })
        val groups = bySize.entries.map { (size, group) ->
            async {
                val byPartial = hashAll(group) { Hashing.partial(it) }.filterValues { it.size > 1 }
                val out = ArrayList<DuplicateGroup>()
                for ((ph, pg) in byPartial) {
                    val byFull = if (size <= 16 * 1024) mapOf(ph to pg) else hashAll(pg) { Hashing.sha256(it) }
                    byFull.filterValues { it.size > 1 }.forEach { (h, fs) -> out += DuplicateGroup(h, size, fs.sortedBy { it.lastModified() }) }
                }
                onProgress(processed.addAndGet(group.size), candidates)
                out
            }
        }.awaitAll().flatten()
        groups.sortedByDescending { it.wasted }
    }

    /** Selection that keeps the oldest copy of each group — never selects every copy. */
    fun defaultSelection(groups: List<DuplicateGroup>): Set<String> = groups.flatMap { g -> g.files.drop(1).map { it.absolutePath } }.toSet()

    /** Safety check: returns groups where the selection would remove every copy. */
    fun groupsFullySelected(groups: List<DuplicateGroup>, selected: Set<String>) = groups.filter { g -> g.files.all { it.absolutePath in selected } }
}
