package com.lyvt.files.core.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes

/**
 * A [File] that remembers the size/date read during a directory scan, so later sorting and filtering
 * (which call length()/lastModified() many times) do not hit the disk again. This was the main cause of slow
 * scans — especially on Windows, where every java.io.File attribute call is a separate, expensive system call.
 */
class ScannedFile(path: String, private val size: Long, private val mtime: Long) : File(path) {
    override fun length(): Long = size
    override fun lastModified(): Long = mtime
    override fun isFile(): Boolean = true
    override fun isDirectory(): Boolean = false
}

/** Directory listing with one attribute read per entry (cached by the OS directory stream on Windows). */
object FastFs {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    data class Entry(val file: File, val isDirectory: Boolean, val size: Long, val mtime: Long, val hidden: Boolean) {
        fun toItem() = FileItem(file.absolutePath, file.name, isDirectory, if (isDirectory) 0 else size, mtime, hidden)
    }

    fun attrs(p: Path): BasicFileAttributes? = runCatching {
        if (windows) Files.readAttributes(p, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        else Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    fun entry(p: Path, a: BasicFileAttributes): Entry {
        val name = p.fileName?.toString().orEmpty()
        val dir = a.isDirectory || (a.isSymbolicLink && Files.isDirectory(p))
        val hidden = name.startsWith(".") || (a is DosFileAttributes && a.isHidden)
        return Entry(if (dir) p.toFile() else ScannedFile(p.toString(), a.size(), a.lastModifiedTime().toMillis()), dir, a.size(), a.lastModifiedTime().toMillis(), hidden)
    }

    /** Lists [dir]; returns null when it cannot be read (access denied / missing). */
    fun list(dir: File): List<Entry>? = runCatching {
        Files.newDirectoryStream(dir.toPath()).use { ds -> ds.mapNotNull { p -> attrs(p)?.let { entry(p, it) } } }
    }.getOrNull()
}
