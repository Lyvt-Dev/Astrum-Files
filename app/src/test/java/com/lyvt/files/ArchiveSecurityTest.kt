package com.lyvt.files

import com.lyvt.files.core.archive.ArchiveManager
import com.lyvt.files.core.archive.ArchivePasswordException
import com.lyvt.files.core.archive.ExtractionLimits
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveSecurityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipWith(name: String, entries: Map<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    @Test fun zipSlipIsBlocked() = runTest {
        val evil = zipWith("evil.zip", mapOf("ok.txt" to "a".toByteArray(), "../../escape.txt" to "b".toByteArray()))
        val dest = tmp.newFolder("out")
        try { ArchiveManager.extract(evil, dest); fail("should block traversal") } catch (e: IOException) { assertTrue(e.message!!.contains("traversal") || e.message!!.contains("Unsafe")) }
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertFalse(File(tmp.root.parentFile, "escape.txt").exists())
    }

    @Test fun absolutePathBlocked() {
        try { ArchiveManager.safeResolve(tmp.root, "/etc/passwd"); fail() } catch (_: IOException) {}
    }

    @Test fun decompressionBombLimit() = runTest {
        val bomb = zipWith("bomb.zip", mapOf("zeros.bin" to ByteArray(5 * 1024 * 1024)))
        try {
            ArchiveManager.extract(bomb, tmp.newFolder("b"), limits = ExtractionLimits(maxTotalBytes = 1024 * 1024, maxRatio = Long.MAX_VALUE))
            fail("should hit limit")
        } catch (e: IOException) { assertTrue(e.message!!.contains("limit")) }
        try {
            ArchiveManager.extract(bomb, tmp.newFolder("c"), limits = ExtractionLimits(maxRatio = 10))
            fail("should flag ratio")
        } catch (e: IOException) { assertTrue(e.message!!.contains("ratio")) }
    }

    @Test fun createAndExtractEncryptedZipPreservesDirectories() = runTest {
        val src = tmp.newFolder("folder"); File(src, "a/b").mkdirs(); File(src, "a/b/c.txt").writeText("deep"); File(src, "top.txt").writeText("top")
        val zip = File(tmp.root, "out.zip")
        ArchiveManager.createZip(listOf(src), zip, "pässwörd".toCharArray(), level = 9)
        assertTrue(ArchiveManager.list(zip).any { it.encrypted })
        try { ArchiveManager.extract(zip, tmp.newFolder("nopw")); fail() } catch (_: ArchivePasswordException) {}
        try { ArchiveManager.extract(zip, tmp.newFolder("wrong"), "nope".toCharArray()); fail() } catch (_: IOException) {}
        val dest = tmp.newFolder("ok")
        val n = ArchiveManager.extract(zip, dest, "pässwörd".toCharArray())
        assertEquals(2, n)
        assertEquals("deep", File(dest, "folder/a/b/c.txt").readText())
        assertTrue(ArchiveManager.test(zip, "pässwörd".toCharArray()).isSuccess)
    }

    @Test fun extractNeverOverwrites() = runTest {
        val z = zipWith("x.zip", mapOf("f.txt" to "new".toByteArray()))
        val dest = tmp.newFolder("d"); File(dest, "f.txt").writeText("old")
        try { ArchiveManager.extract(z, dest); fail() } catch (_: IOException) {}
        assertEquals("old", File(dest, "f.txt").readText())
    }

    @Test fun corruptedArchiveReportsError() = runTest {
        val bad = File(tmp.root, "bad.zip").apply { writeBytes(ByteArray(100) { 7 }) }
        try { ArchiveManager.extract(bad, tmp.newFolder("z")); fail() } catch (_: Exception) {}
    }
}
