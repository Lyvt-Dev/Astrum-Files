package com.lyvt.files.core.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private const val BUFFER = 256 * 1024
    private val HEX = "0123456789abcdef".toCharArray()
    fun hex(b: ByteArray): String { val c = CharArray(b.size * 2); for (i in b.indices) { val v = b[i].toInt() and 0xff; c[i * 2] = HEX[v ushr 4]; c[i * 2 + 1] = HEX[v and 15] }; return String(c) }

    /** Streaming digest; never loads the whole file into memory. Cancellable between chunks. */
    suspend fun digest(input: InputStream, algorithm: String = "SHA-256", limit: Long = Long.MAX_VALUE): String {
        val md = MessageDigest.getInstance(algorithm)
        val buf = ByteArray(minOf(BUFFER.toLong(), limit).toInt().coerceAtLeast(1))
        var remaining = limit
        input.use { s ->
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val n = s.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return hex(md.digest())
    }

    suspend fun sha256(file: File): String = digest(file.inputStream())
    suspend fun md5(file: File): String = digest(file.inputStream(), "MD5")
    /** Hash of three 16 KiB samples (start, middle, end) — cheap way to tell apart big files with identical headers. */
    fun samples(file: File, chunk: Int = 16 * 1024): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(chunk)
        java.io.RandomAccessFile(file, "r").use { r ->
            val len = r.length()
            for (pos in longArrayOf(0, (len / 2 - chunk / 2).coerceAtLeast(0), (len - chunk).coerceAtLeast(0))) {
                r.seek(pos); val n = r.read(buf); if (n > 0) md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    suspend fun partial(file: File, bytes: Long = 16 * 1024): String = digest(file.inputStream(), "SHA-256", bytes)
}
