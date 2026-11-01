package com.lyvt.files.core.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.Locale

/**
 * Search query with operators parsed from free text:
 *   ext:pdf,docx  type:image  size>10mb  size<1gb  after:2024-01-01  before:2024-12-31  "exact phrase"  re:regex
 */
data class SearchQuery(
    val text: String = "",
    val extensions: Set<String> = emptySet(),
    val category: FileCategory? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val after: Long? = null,
    val before: Long? = null,
    val regex: Regex? = null,
    val includeHidden: Boolean = false,
    val foldersOnly: Boolean = false,
) {
    fun matches(f: File): Boolean {
        val name = f.name
        if (!includeHidden && name.startsWith(".")) return false
        if (foldersOnly && !f.isDirectory) return false
        if (text.isNotEmpty() && !name.contains(text, ignoreCase = true)) return false
        regex?.let { if (!it.containsMatchIn(name)) return false }
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extensions.isNotEmpty() && (f.isDirectory || ext !in extensions)) return false
        if (category != null && (f.isDirectory || FileCategory.of(ext) != category)) return false
        if (minSize != null || maxSize != null) {
            if (f.isDirectory) return false
            val s = f.length()
            if (minSize != null && s < minSize) return false
            if (maxSize != null && s > maxSize) return false
        }
        val m = if (after != null || before != null) f.lastModified() else 0
        if (after != null && m < after) return false
        if (before != null && m > before) return false
        return true
    }

    companion object {
        private val sizeRe = Regex("""size([<>])(\d+(?:\.\d+)?)(b|kb|mb|gb)?""", RegexOption.IGNORE_CASE)

        fun parseSize(num: String, unit: String?): Long {
            val mult = when (unit?.lowercase()) { "kb" -> 1024L; "mb" -> 1024L * 1024; "gb" -> 1024L * 1024 * 1024; else -> 1L }
            return (num.toDouble() * mult).toLong()
        }

        fun parse(input: String, includeHidden: Boolean = false): SearchQuery {
            var q = SearchQuery(includeHidden = includeHidden)
            val words = mutableListOf<String>()
            val tokens = Regex("\"([^\"]*)\"|(\\S+)").findAll(input).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val df = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            for (t in tokens) {
                val lower = t.lowercase(Locale.ROOT)
                val size = sizeRe.matchEntire(t)
                when {
                    lower.startsWith("ext:") -> q = q.copy(extensions = lower.removePrefix("ext:").split(',').map { it.trim('.', ' ') }.filter { it.isNotEmpty() }.toSet())
                    lower.startsWith("type:") -> q = q.copy(category = when (lower.removePrefix("type:")) {
                        "image", "images", "photo" -> FileCategory.IMAGE
                        "video", "videos" -> FileCategory.VIDEO
                        "audio", "music" -> FileCategory.AUDIO
                        "doc", "docs", "document" -> FileCategory.DOCUMENT
                        "archive", "zip" -> FileCategory.ARCHIVE
                        "apk", "app" -> FileCategory.APK
                        "folder", "dir" -> { q = q.copy(foldersOnly = true); null }
                        else -> null
                    })
                    size != null -> {
                        val bytes = parseSize(size.groupValues[2], size.groupValues[3].ifEmpty { null })
                        q = if (size.groupValues[1] == ">") q.copy(minSize = bytes) else q.copy(maxSize = bytes)
                    }
                    lower.startsWith("after:") -> q = q.copy(after = runCatching { df.parse(lower.removePrefix("after:"))!!.time }.getOrNull())
                    lower.startsWith("before:") -> q = q.copy(before = runCatching { df.parse(lower.removePrefix("before:"))!!.time + 86_399_999 }.getOrNull())
                    lower.startsWith("re:") -> q = q.copy(regex = runCatching { Regex(t.substring(3), RegexOption.IGNORE_CASE) }.getOrNull())
                    else -> words += t
                }
            }
            return q.copy(text = words.joinToString(" "))
        }
    }
}

object FileSearch {
    /** Recursive, cancellable search streaming batches of matches. Skips symlink loops and Android/data. */
    fun search(root: File, query: SearchQuery, excluded: Set<String> = emptySet(), batch: Int = 50, onFolder: (Int) -> Unit = {}): Flow<List<FileItem>> = flow {
        val buffer = ArrayList<FileItem>(batch)
        val visited = HashSet<String>()
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val canon = if (java.nio.file.Files.isSymbolicLink(dir.toPath())) runCatching { dir.canonicalPath }.getOrNull() ?: continue else dir.absolutePath
            if (!visited.add(canon) || excluded.any { canon.startsWith(it) }) continue
            val children = FastFs.list(dir) ?: continue
            onFolder(visited.size)
            for (e in children) {
                val c = e.file
                if (e.isDirectory && (query.includeHidden || !c.name.startsWith("."))) stack.add(c)
                if (query.matches(c)) {
                    buffer += e.toItem()
                    if (buffer.size >= batch) { emit(buffer.toList()); buffer.clear() }
                }
            }
        }
        if (buffer.isNotEmpty()) emit(buffer.toList())
    }
}
