package com.lyvt.files.core.storage

import com.lyvt.files.core.common.NaturalOrder
import java.io.File
import java.util.Locale

enum class FileCategory { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER;

    companion object {
        private val map: Map<String, FileCategory> = buildMap {
            listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif", "dng").forEach { put(it, IMAGE) }
            listOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts").forEach { put(it, VIDEO) }
            listOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr", "mid").forEach { put(it, AUDIO) }
            listOf("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "csv", "json",
                "xml", "html", "htm", "rtf", "epub", "log", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "yml", "yaml")
                .forEach { put(it, DOCUMENT) }
            listOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz").forEach { put(it, ARCHIVE) }
            put("apk", APK); put("apks", APK); put("xapk", APK)
            put("ts", VIDEO)
        }
        fun of(extension: String): FileCategory = map[extension.lowercase(Locale.ROOT)] ?: OTHER
    }
}

data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    val childCount: Int = -1,
) {
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val category: FileCategory get() = if (isDirectory) FileCategory.OTHER else FileCategory.of(extension)
    val file: File get() = File(path)

    companion object {
        fun from(f: File, countChildren: Boolean = false): FileItem = FileItem(
            path = f.absolutePath,
            name = f.name,
            isDirectory = f.isDirectory,
            size = if (f.isDirectory) 0 else f.length(),
            lastModified = f.lastModified(),
            isHidden = f.name.startsWith("."),
            childCount = if (countChildren && f.isDirectory) f.list()?.size ?: 0 else -1,
        )
    }
}

enum class SortField { NAME, DATE, SIZE, TYPE }

data class SortOrder(
    val field: SortField = SortField.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
) {
    fun comparator(): Comparator<FileItem> {
        val base: Comparator<FileItem> = when (field) {
            SortField.NAME -> Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortField.DATE -> compareBy { it.lastModified }
            SortField.SIZE -> compareBy { it.size }
            SortField.TYPE -> compareBy<FileItem> { it.extension }.then { a, b -> NaturalOrder.compare(a.name, b.name) }
        }
        val directed = if (ascending) base else base.reversed()
        return if (foldersFirst) compareBy<FileItem> { !it.isDirectory }.then(directed) else directed
    }
}

/** How to resolve a name clash at the destination. Nothing is ever overwritten silently. */
enum class ConflictPolicy { KEEP_BOTH, SKIP, REPLACE }

sealed interface OpResult {
    data class Success(val count: Int, val message: String? = null) : OpResult
    data class Partial(val done: Int, val failed: List<Pair<String, String>>) : OpResult
    data class Failure(val reason: String) : OpResult
}
