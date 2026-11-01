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
 * Exact duplicate detection, fastest checks first so most files are ruled out after reading very little:
 *  (1) group by size (no reading), (2) hash of the first 16 KiB, (3) for files > 1 MiB: hash of 3 samples
 *  (start / middle / end), (4) full streaming SHA-256 — only files that passed every check are read completely.
 * Full hashes are remembered in [cache] (path + size + date), so repeated searches skip unchanged files.
 */
object DuplicateFinder {
    /** Set by the app (e.g. files dir / ~/.astrum) to remember hashes between runs. */
    @Volatile var cache: HashCache? = null

    suspend fun find(files: List<File>, minSize: Long = 1, parallelism: Int = 6, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<DuplicateGroup> = coroutineScope {
        val bySize = files.asSequence().filter { it.isFile && it.length() >= minSize }.groupBy { it.length() }.filterValues { it.size > 1 }
        val candidates = bySize.values.sumOf { it.size }
        val processed = AtomicInteger(0)
        val lastReport = java.util.concurrent.atomic.AtomicLong(0)
        fun tick(n: Int) { val d = processed.addAndGet(n); val now = System.nanoTime()
            if (now - lastReport.get() > 60_000_000 || d == candidates) { lastReport.set(now); onProgress(d, candidates) } }
        val io = Dispatchers.IO.limitedParallelism(parallelism)
        val c = cache
        suspend fun hashAll(fs: List<File>, h: suspend (File) -> String): Map<String, List<File>> =
            fs.map { f -> async(io) { runCatching { h(f) }.getOrNull()?.let { it to f } } }.awaitAll().filterNotNull().groupBy({ it.first }, { it.second })
        suspend fun full(f: File): String = c?.get(f) ?: Hashing.sha256(f).also { c?.put(f, it) }
        val groups = bySize.entries.map { (size, group) ->
            async {
                val out = ArrayList<DuplicateGroup>()
                // Cached full hashes need no reading at all.
                val (known, unknown) = if (c != null) group.partition { c.get(it) != null } else emptyList<File>() to group
                var stage = if (known.size + unknown.size == known.size) emptyList() else hashAll(unknown) { Hashing.partial(it) }.filterValues { it.size > 1 || known.isNotEmpty() }.values.toList()
                if (size > 1L shl 20) stage = stage.flatMap { g -> if (g.size > 1 || known.isNotEmpty()) hashAll(g) { Hashing.samples(it) }.values.filter { it.size > 1 || known.isNotEmpty() } else emptyList() }
                val pool = stage.flatten() + known
                val byFull: Map<String, List<File>> = if (size <= 16 * 1024 && known.isEmpty()) stage.associateBy { Hashing.partial(it[0]) } else hashAll(pool) { full(it) }
                byFull.filterValues { it.size > 1 }.forEach { (h, fs) -> out += DuplicateGroup(h, size, fs.sortedBy { it.lastModified() }) }
                tick(group.size)
                out
            }
        }.awaitAll().flatten()
        c?.save()
        groups.sortedByDescending { it.wasted }
    }

    /** Selection that keeps the oldest copy of each group — never selects every copy. */
    fun defaultSelection(groups: List<DuplicateGroup>): Set<String> = groups.flatMap { g -> g.files.drop(1).map { it.absolutePath } }.toSet()

    /** Safety check: returns groups where the selection would remove every copy. */
    fun groupsFullySelected(groups: List<DuplicateGroup>, selected: Set<String>) = groups.filter { g -> g.files.all { it.absolutePath in selected } }
}
