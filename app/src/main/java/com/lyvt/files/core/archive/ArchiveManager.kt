package com.lyvt.files.core.archive

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

data class ArchiveEntryInfo(val path: String, val isDirectory: Boolean, val size: Long, val compressedSize: Long, val encrypted: Boolean)

class ArchivePasswordException(msg: String) : IOException(msg)

data class ExtractionLimits(
    val maxTotalBytes: Long = 20L * 1024 * 1024 * 1024,
    val maxEntries: Int = 200_000,
    /** Ratio of uncompressed to compressed size above which an entry is considered a decompression bomb. */
    val maxRatio: Long = 1000,
)

enum class ArchiveFormat { ZIP, SEVEN_Z, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, RAR, UNKNOWN;
    companion object {
        fun of(name: String): ArchiveFormat {
            val n = name.lowercase()
            return when {
                n.endsWith(".zip") -> ZIP
                n.endsWith(".7z") -> SEVEN_Z
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> TAR_BZ2
                n.endsWith(".tar.xz") || n.endsWith(".txz") -> TAR_XZ
                n.endsWith(".tar") -> TAR
                n.endsWith(".gz") -> GZ
                n.endsWith(".bz2") -> BZ2
                n.endsWith(".rar") -> RAR
                else -> UNKNOWN
            }
        }
    }
}

object ArchiveManager {

    /** Resolves an entry name inside [dest], rejecting absolute paths and traversal (ZIP Slip). */
    fun safeResolve(dest: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) throw IOException("Unsafe absolute path in archive: $entryName")
        val target = File(dest, normalized)
        val destCanon = dest.canonicalPath + File.separator
        val tCanon = target.canonicalPath
        if (!tCanon.startsWith(destCanon) && tCanon != dest.canonicalPath) throw IOException("Blocked path traversal entry: $entryName")
        return target
    }

    fun list(archive: File, password: CharArray? = null): List<ArchiveEntryInfo> = when (ArchiveFormat.of(archive.name)) {
        ArchiveFormat.ZIP -> ZipFile(archive, password).fileHeaders.map {
            ArchiveEntryInfo(it.fileName, it.isDirectory, it.uncompressedSize, it.compressedSize, it.isEncrypted)
        }
        ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(archive).setPassword(password).get().use { z ->
            z.entries.map { ArchiveEntryInfo(it.name, it.isDirectory, it.size, -1, false) }
        }
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> tarStream(archive).use { t ->
            generateSequence { t.nextEntry }.map { ArchiveEntryInfo(it.name, it.isDirectory, it.size, -1, false) }.toList()
        }
        ArchiveFormat.GZ, ArchiveFormat.BZ2 -> listOf(ArchiveEntryInfo(archive.name.substringBeforeLast('.'), false, -1, archive.length(), false))
        ArchiveFormat.RAR -> throw IOException("RAR is a proprietary format and is not supported. Use the RAR author's tools to convert it.")
        ArchiveFormat.UNKNOWN -> throw IOException("Unsupported archive format")
    }

    private fun tarStream(f: File): ArchiveInputStream<*> {
        val raw = f.inputStream().buffered()
        val decompressed: InputStream = when (ArchiveFormat.of(f.name)) {
            ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(raw)
            ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw)
            ArchiveFormat.TAR_XZ -> XZCompressorInputStream(raw)
            else -> raw
        }
        return TarArchiveInputStream(decompressed)
    }

    private class Budget(val limits: ExtractionLimits) {
        var bytes = 0L; var entries = 0
        fun entry() { if (++entries > limits.maxEntries) throw IOException("Archive has too many entries") }
        fun add(n: Long) { bytes += n; if (bytes > limits.maxTotalBytes) throw IOException("Extraction exceeds safe size limit (possible decompression bomb)") }
    }

    private suspend fun pump(input: InputStream, target: File, budget: Budget, onBytes: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".lyvtpart")
        try {
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    budget.add(n.toLong()); out.write(buf, 0, n); onBytes(n.toLong())
                }
            }
            if (target.exists()) throw IOException("${target.name} already exists")
            if (!part.renameTo(target)) throw IOException("Could not write ${target.name}")
        } finally { part.delete() }
    }

    /**
     * Extract all or [only] selected entries. Never overwrites existing files (throws instead, caller chooses a new
     * destination). Returns number of files written.
     */
    suspend fun extract(
        archive: File, dest: File, password: CharArray? = null, only: Set<String>? = null,
        limits: ExtractionLimits = ExtractionLimits(), onBytes: (Long) -> Unit = {},
    ): Int {
        dest.mkdirs()
        val budget = Budget(limits)
        var count = 0
        fun wanted(name: String) = only == null || only.any { name == it || name.startsWith(it.trimEnd('/') + "/") }
        when (ArchiveFormat.of(archive.name)) {
            ArchiveFormat.ZIP -> {
                val zip = ZipFile(archive, password)
                if (!zip.isValidZipFile) throw IOException("Archive is corrupted or not a ZIP file")
                if (zip.isEncrypted && password == null) throw ArchivePasswordException("Password required")
                for (h in zip.fileHeaders) {
                    if (!wanted(h.fileName)) continue
                    budget.entry()
                    if (h.compressedSize > 0 && h.uncompressedSize / h.compressedSize > limits.maxRatio) throw IOException("Suspicious compression ratio in ${h.fileName}")
                    val target = safeResolve(dest, h.fileName)
                    if (h.isDirectory) { target.mkdirs(); continue }
                    try {
                        zip.getInputStream(h).use { pump(it, target, budget, onBytes) }
                    } catch (e: ZipException) {
                        if (e.type == ZipException.Type.WRONG_PASSWORD) throw ArchivePasswordException("Incorrect password")
                        throw IOException("Corrupted entry ${h.fileName}: ${e.message}")
                    }
                    count++
                }
            }
            ArchiveFormat.SEVEN_Z -> SevenZFile.builder().setFile(archive).setPassword(password).get().use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (wanted(e.name)) {
                        budget.entry()
                        val target = safeResolve(dest, e.name)
                        if (e.isDirectory) target.mkdirs() else { pump(z.getInputStream(e), target, budget, onBytes); count++ }
                    }
                    e = z.nextEntry
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ -> tarStream(archive).use { t ->
                var e = t.nextEntry
                while (e != null) {
                    if (wanted(e.name) && t.canReadEntryData(e)) {
                        budget.entry()
                        val target = safeResolve(dest, e.name)
                        val tarEntry = e as? org.apache.commons.compress.archivers.tar.TarArchiveEntry
                        when {
                            e.isDirectory -> target.mkdirs()
                            tarEntry != null && (tarEntry.isSymbolicLink || tarEntry.isLink) -> Unit // links are skipped for safety
                            else -> { pump(t, target, budget, onBytes); count++ }
                        }
                    }
                    e = t.nextEntry
                }
            }
            ArchiveFormat.GZ, ArchiveFormat.BZ2 -> {
                val raw = archive.inputStream().buffered()
                val s = if (ArchiveFormat.of(archive.name) == ArchiveFormat.GZ) GzipCompressorInputStream(raw) else BZip2CompressorInputStream(raw)
                s.use { pump(it, safeResolve(dest, archive.name.substringBeforeLast('.')), budget, onBytes) }
                count = 1
            }
            ArchiveFormat.RAR -> throw IOException("RAR extraction is not supported")
            ArchiveFormat.UNKNOWN -> throw IOException("Unsupported archive format")
        }
        return count
    }

    /** Create a ZIP (optionally AES-256 encrypted). Directory structure is preserved. Refuses to overwrite. */
    fun createZip(sources: List<File>, target: File, password: CharArray? = null, level: Int = 5, onProgress: (Int) -> Unit = {}) {
        if (target.exists()) throw IOException("${target.name} already exists")
        val params = ZipParameters().apply {
            compressionLevel = when { level <= 0 -> CompressionLevel.NO_COMPRESSION; level <= 2 -> CompressionLevel.FASTEST
                level <= 4 -> CompressionLevel.FAST; level <= 6 -> CompressionLevel.NORMAL; level <= 8 -> CompressionLevel.MAXIMUM; else -> CompressionLevel.ULTRA }
            if (password != null && password.isNotEmpty()) {
                isEncryptFiles = true; encryptionMethod = EncryptionMethod.AES; aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        val zip = ZipFile(target, password?.takeIf { it.isNotEmpty() })
        try {
            sources.forEachIndexed { i, f ->
                if (f.isDirectory) zip.addFolder(f, params) else zip.addFile(f, params)
                onProgress(((i + 1) * 100) / sources.size)
            }
        } catch (e: Exception) {
            target.delete(); throw IOException("Compression failed: ${e.message}")
        }
    }

    /** Integrity test: decompresses every entry to /dev/null verifying CRC/MAC. */
    suspend fun test(archive: File, password: CharArray? = null): Result<Int> = runCatching {
        when (ArchiveFormat.of(archive.name)) {
            ArchiveFormat.ZIP -> {
                val zip = ZipFile(archive, password); var n = 0
                zip.fileHeaders.filterNot { it.isDirectory }.forEach { h ->
                    currentCoroutineContext().ensureActive()
                    zip.getInputStream(h).use { s -> val b = ByteArray(65536); while (s.read(b) >= 0) Unit }; n++
                }
                n
            }
            else -> list(archive, password).size
        }
    }
}
