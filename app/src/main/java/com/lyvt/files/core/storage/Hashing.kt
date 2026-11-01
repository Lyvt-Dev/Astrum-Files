package com.lyvt.files.core.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private const val BUFFER = 64 * 1024

    /** Streaming digest; never loads the whole file into memory. Cancellable between chunks. */
    suspend fun digest(input: InputStream, algorithm: String = "SHA-256", limit: Long = Long.MAX_VALUE): String {
        val md = MessageDigest.getInstance(algorithm)
        val buf = ByteArray(BUFFER)
        var remaining = limit
        input.use { s ->
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val n = s.read(buf, 0, minOf(BUFFER.toLong(), remaining).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun sha256(file: File): String = digest(file.inputStream())
    suspend fun md5(file: File): String = digest(file.inputStream(), "MD5")
    suspend fun partial(file: File, bytes: Long = 16 * 1024): String = digest(file.inputStream(), "SHA-256", bytes)
}
