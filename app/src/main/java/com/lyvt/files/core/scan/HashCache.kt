package com.lyvt.files.core.scan

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers full-file SHA-256 hashes between runs, keyed by path + size + modified time. A file that changed gets a new
 * key, so a stale hash is never used. Makes repeated duplicate searches near-instant. Stored as a small text file.
 */
class HashCache(private val file: File) {
    private val map = ConcurrentHashMap<String, String>()
    @Volatile private var dirty = false

    init {
        runCatching { file.forEachLine { l -> val i = l.lastIndexOf('\t'); if (i > 0) map[l.substring(0, i)] = l.substring(i + 1) } }
    }

    private fun key(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
    fun get(f: File): String? = map[key(f)]
    fun put(f: File, hash: String) { map[key(f)] = hash; dirty = true }

    /** Writes the cache atomically, dropping entries for files that no longer exist (bounded to 200k entries). */
    fun save() {
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                map.entries.asSequence().filter { File(it.key.substringBefore('|')).exists() }.take(200_000)
                    .forEach { w.write(it.key); w.write("\t"); w.write(it.value); w.newLine() }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            dirty = false
        }
    }
}
