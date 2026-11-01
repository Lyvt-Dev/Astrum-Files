package com.astrum.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopVaultTest {
    companion object {
        @JvmStatic @BeforeClass fun home() { System.setProperty("user.home", Files.createTempDirectory("astrum-home").toString()) }
    }

    @Test fun createImportLockUnlockExportDecoyRecovery() {
        val src = Files.createTempDirectory("src").toFile()
        val secret = File(src, "tax-2024.pdf").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val code = Vault.create("correct horse".toCharArray())
        assertTrue(Vault.unlocked)
        Vault.import(listOf(secret), "", deleteOriginals = false) {}
        assertEquals(1, Vault.entries.size)
        // Ciphertext on disk must not contain the plaintext pattern
        val blob = File(Store.dir, "vault/blobs").listFiles()!!.first { it.name.endsWith(".bin") }.readBytes()
        assertFalse(blob.copyOfRange(12, 76).contentEquals(secret.readBytes().copyOfRange(0, 64)))
        Vault.lock(); assertFalse(Vault.unlocked)
        assertTrue(Vault.unlock("wrong".toCharArray()).isFailure)
        assertTrue(Vault.unlock("correct horse".toCharArray()).isSuccess)
        val out = Files.createTempDirectory("out").toFile()
        assertEquals(1, Vault.export(Vault.entries.toList(), out))
        assertTrue(File(out, "tax-2024.pdf").readBytes().contentEquals(secret.readBytes()))
        // Decoy: separate empty vault opened by a different password
        Vault.create("decoy password".toCharArray(), decoy = true)
        Vault.lock(); assertTrue(Vault.unlock("decoy password".toCharArray()).isSuccess)
        assertTrue(Vault.isDecoy); assertEquals(0, Vault.entries.size)
        Vault.lock()
        // Recovery resets the real vault's password
        assertTrue(Vault.resetWithRecovery(code, "new password 1".toCharArray()).isSuccess)
        assertEquals(1, Vault.entries.size); Vault.lock()
        assertTrue(Vault.unlock("new password 1".toCharArray()).isSuccess); Vault.lock()
        assertTrue(Vault.resetWithRecovery("AAAA-BBBB", "x".repeat(8).toCharArray()).isFailure)
    }

    @Test fun perceptualHashGroupsResizedCopies() {
        val dir = Files.createTempDirectory("img").toFile()
        val img = java.awt.image.BufferedImage(200, 150, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); for (x in 0 until 200) { g.color = java.awt.Color(x, 255 - x, (x * 3) % 255); g.fillRect(x, 0, 1, 150) }; g.dispose()
        val a = File(dir, "a.png"); javax.imageio.ImageIO.write(img, "png", a)
        val small = java.awt.image.BufferedImage(100, 75, java.awt.image.BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply { drawImage(img, 0, 0, 100, 75, null); dispose() } }
        val b = File(dir, "b.jpg"); javax.imageio.ImageIO.write(small, "jpg", b)
        val ha = ImageAnalysis.dHash(a)!!; val hb = ImageAnalysis.dHash(b)!!
        assertTrue(java.lang.Long.bitCount(ha xor hb) <= 8)
        assertEquals(1, ImageAnalysis.cluster(listOf(a to ha, b to hb), 8).size)
    }
}
