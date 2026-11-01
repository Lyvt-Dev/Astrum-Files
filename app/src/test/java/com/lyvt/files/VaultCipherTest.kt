package com.lyvt.files

import com.lyvt.files.core.security.VaultAuthException
import com.lyvt.files.core.security.VaultCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class VaultCipherTest {
    private fun roundTrip(size: Int) {
        val key = VaultCipher.newKey()
        val data = Random(size).nextBytes(size)
        val enc = ByteArrayOutputStream().also { VaultCipher.encryptStream(key, ByteArrayInputStream(data), it) }.toByteArray()
        val dec = ByteArrayOutputStream().also { VaultCipher.decryptStream(key, ByteArrayInputStream(enc), it) }.toByteArray()
        assertArrayEquals(data, dec)
    }

    @Test fun roundTripsVariousSizes() {
        listOf(0, 1, VaultCipher.CHUNK - 1, VaultCipher.CHUNK, VaultCipher.CHUNK + 1, 3 * VaultCipher.CHUNK + 17).forEach(::roundTrip)
    }

    @Test fun sameInputDifferentCiphertext() {
        val key = VaultCipher.newKey(); val data = ByteArray(100)
        val a = ByteArrayOutputStream().also { VaultCipher.encryptStream(key, ByteArrayInputStream(data), it) }.toByteArray()
        val b = ByteArrayOutputStream().also { VaultCipher.encryptStream(key, ByteArrayInputStream(data), it) }.toByteArray()
        assertFalse(a.contentEquals(b))
    }

    @Test(expected = VaultAuthException::class) fun wrongKeyFails() {
        val enc = ByteArrayOutputStream().also { VaultCipher.encryptStream(VaultCipher.newKey(), ByteArrayInputStream(ByteArray(10)), it) }.toByteArray()
        VaultCipher.decryptStream(VaultCipher.newKey(), ByteArrayInputStream(enc), ByteArrayOutputStream())
    }

    @Test(expected = VaultAuthException::class) fun tamperingDetected() {
        val key = VaultCipher.newKey()
        val enc = ByteArrayOutputStream().also { VaultCipher.encryptStream(key, ByteArrayInputStream(ByteArray(1000)), it) }.toByteArray()
        enc[enc.size - 5] = (enc[enc.size - 5].toInt() xor 1).toByte()
        VaultCipher.decryptStream(key, ByteArrayInputStream(enc), ByteArrayOutputStream())
    }

    @Test(expected = VaultAuthException::class) fun truncationAtChunkBoundaryDetected() {
        val key = VaultCipher.newKey()
        val enc = ByteArrayOutputStream().also { VaultCipher.encryptStream(key, ByteArrayInputStream(ByteArray(3 * VaultCipher.CHUNK)), it) }.toByteArray()
        // Drop the final chunk entirely: header(12) + 2 full chunks (4 + CHUNK + 16 each).
        val cut = enc.copyOf(12 + 2 * (4 + VaultCipher.CHUNK + 16))
        VaultCipher.decryptStream(key, ByteArrayInputStream(cut), ByteArrayOutputStream())
    }

    @Test fun pbkdf2DeterministicAndSealOpen() {
        val salt = VaultCipher.randomBytes(16)
        val k1 = VaultCipher.deriveKey("123456".toCharArray(), salt, 1000)
        val k2 = VaultCipher.deriveKey("123456".toCharArray(), salt, 1000)
        assertArrayEquals(k1.encoded, k2.encoded)
        val sealed = VaultCipher.seal(k1, "secret".toByteArray(), "aad".toByteArray())
        assertEquals("secret", String(VaultCipher.open(k2, sealed, "aad".toByteArray())))
    }

    @Test(expected = VaultAuthException::class) fun wrongPinFails() {
        val salt = VaultCipher.randomBytes(16)
        val sealed = VaultCipher.seal(VaultCipher.deriveKey("123456".toCharArray(), salt, 1000), ByteArray(32))
        VaultCipher.open(VaultCipher.deriveKey("654321".toCharArray(), salt, 1000), sealed)
    }
}
