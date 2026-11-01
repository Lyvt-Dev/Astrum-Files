package com.lyvt.files.core.security

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VaultAuthException(msg: String) : IOException(msg)

/**
 * Pure-JVM authenticated encryption primitives (no Android dependencies, unit-tested).
 *
 * Stream format "LYV1": magic(4) | noncePrefix(8) | chunks...
 * Each chunk: length(4, big-endian, ciphertext length incl. tag) | AES-256-GCM ciphertext.
 * Chunk nonce = noncePrefix(8) || counter(4). AAD = counter(4) || finalFlag(1). The final flag authenticates
 * the end of stream so truncation and chunk reordering are detected. A fresh random prefix per file guarantees
 * unique nonces under one key (2^64 prefixes × 2^32 chunks).
 */
object VaultCipher {
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'Y'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val CHUNK = 64 * 1024
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    const val PBKDF2_ITERATIONS = 310_000

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun newKey(): SecretKey = SecretKeySpec(randomBytes(32), "AES")

    fun deriveKey(secret: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(secret, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Single-shot GCM for small blobs: nonce(12) | ciphertext+tag. */
    fun seal(key: SecretKey, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        aad?.let { c.updateAAD(it) }
        return nonce + c.doFinal(plain)
    }

    fun open(key: SecretKey, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        if (sealed.size < 12 + 16) throw VaultAuthException("Corrupted data")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, 12))
        aad?.let { c.updateAAD(it) }
        return try { c.doFinal(sealed, 12, sealed.size - 12) } catch (e: AEADBadTagException) {
            throw VaultAuthException("Authentication failed — wrong key or tampered data")
        }
    }

    private fun aad(counter: Int, last: Boolean) = ByteBuffer.allocate(5).putInt(counter).put(if (last) 1 else 0).array()
    private fun nonce(prefix: ByteArray, counter: Int) = ByteBuffer.allocate(12).put(prefix).putInt(counter).array()

    fun encryptStream(key: SecretKey, input: InputStream, output: OutputStream, onBytes: (Long) -> Unit = {}) {
        val prefix = randomBytes(8)
        output.write(MAGIC); output.write(prefix)
        val buf = ByteArray(CHUNK)
        var counter = 0
        var pending = readFully(input, buf)
        while (true) {
            val next = ByteArray(CHUNK)
            val nextLen = if (pending == CHUNK) readFully(input, next) else 0
            val last = nextLen == 0
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter)))
            c.updateAAD(aad(counter, last))
            val ct = c.doFinal(buf, 0, pending)
            output.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            output.write(ct)
            onBytes(pending.toLong())
            if (last) break
            System.arraycopy(next, 0, buf, 0, nextLen)
            pending = nextLen
            counter++
        }
        output.flush()
    }

    fun decryptStream(key: SecretKey, input: InputStream, output: OutputStream) {
        val din = DataInputStream(BufferedInputStream(input))
        val magic = ByteArray(4)
        try { din.readFully(magic) } catch (e: EOFException) { throw VaultAuthException("Not a vault file") }
        if (!magic.contentEquals(MAGIC)) throw VaultAuthException("Not a vault file")
        val prefix = ByteArray(8).also { din.readFully(it) }
        var counter = 0
        while (true) {
            val len = try { din.readInt() } catch (e: EOFException) { throw VaultAuthException("File is truncated") }
            if (len < 16 || len > CHUNK + 16) throw VaultAuthException("Corrupted chunk")
            val ct = ByteArray(len).also { din.readFully(it) }
            // Peek whether more data follows to decide which AAD to verify against.
            val last = run { din.mark(1); val b = din.read(); if (b >= 0) { din.reset(); false } else true }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, counter)))
            c.updateAAD(aad(counter, last))
            val pt = try { c.doFinal(ct) } catch (e: AEADBadTagException) {
                throw VaultAuthException(if (last) "File is truncated or tampered" else "Authentication failed")
            }
            output.write(pt)
            if (last) break
            counter++
        }
        output.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}
