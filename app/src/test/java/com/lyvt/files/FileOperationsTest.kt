package com.lyvt.files

import com.lyvt.files.core.storage.BatchRename
import com.lyvt.files.core.storage.ConflictPolicy
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.core.storage.FileOperations
import com.lyvt.files.core.storage.OpResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileOperationsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun copyKeepsBothOnConflict() = runTest {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        File(src, "photo.jpg").writeText("new"); File(dst, "photo.jpg").writeText("old")
        val r = FileOperations.copy(listOf(File(src, "photo.jpg")), dst, ConflictPolicy.KEEP_BOTH)
        assertTrue(r is OpResult.Success)
        assertEquals("old", File(dst, "photo.jpg").readText())
        assertEquals("new", File(dst, "photo (1).jpg").readText())
    }

    @Test fun skipAndReplacePolicies() = runTest {
        val src = tmp.newFolder("s"); val dst = tmp.newFolder("d")
        File(src, "a.txt").writeText("new"); File(dst, "a.txt").writeText("old")
        FileOperations.copy(listOf(File(src, "a.txt")), dst, ConflictPolicy.SKIP)
        assertEquals("old", File(dst, "a.txt").readText())
        FileOperations.copy(listOf(File(src, "a.txt")), dst, ConflictPolicy.REPLACE)
        assertEquals("new", File(dst, "a.txt").readText())
    }

    @Test fun moveNestedUnicodeDirectory() = runTest {
        val src = tmp.newFolder("Ünïcødé 📁"); File(src, "sub/深い").mkdirs()
        File(src, "sub/深い/файл.txt").writeText("hello")
        val dst = tmp.newFolder("target")
        val r = FileOperations.move(listOf(src), dst, ConflictPolicy.KEEP_BOTH)
        assertTrue(r is OpResult.Success)
        assertFalse(src.exists())
        assertEquals("hello", File(dst, "Ünïcødé 📁/sub/深い/файл.txt").readText())
    }

    @Test fun refusesCopyIntoItself() = runTest {
        val src = tmp.newFolder("loop"); File(src, "inner").mkdirs()
        val r = FileOperations.copy(listOf(src), File(src, "inner"), ConflictPolicy.KEEP_BOTH)
        assertTrue(r is OpResult.Failure)
    }

    @Test fun missingSourceReportsFailure() = runTest {
        val r = FileOperations.copy(listOf(File(tmp.root, "ghost.txt")), tmp.newFolder("x"), ConflictPolicy.KEEP_BOTH)
        assertTrue(r is OpResult.Failure)
    }

    @Test fun cancelledCopyLeavesNoPartialFile() = runTest {
        val src = tmp.newFile("big.bin").apply { writeBytes(ByteArray(8 * 1024 * 1024)) }
        val dst = tmp.newFolder("out")
        val job = launch { FileOperations.copy(listOf(src), dst) { p -> if (p.bytesDone > 1024 * 1024) throw kotlinx.coroutines.CancellationException("stop") }.let { } }
        job.join()
        assertFalse(File(dst, "big.bin").exists())
        assertFalse(File(dst, "big.bin" + FileOperations.PART_SUFFIX).exists())
    }

    private suspend fun FileOperations.copy(s: List<File>, d: File, onP: (com.lyvt.files.core.storage.TransferProgress) -> Unit) =
        copy(s, d, ConflictPolicy.KEEP_BOTH, onP)

    @Test fun validationRejectsBadNames() {
        assertNotNull(FileOperations.validateName("a/b"))
        assertNotNull(FileOperations.validateName(".."))
        assertNotNull(FileOperations.validateName("x".repeat(300)))
        assertEquals(null, FileOperations.validateName("Ok name (1).txt"))
    }

    @Test(expected = IOException::class) fun renameDoesNotOverwrite() {
        tmp.newFile("a.txt"); tmp.newFile("b.txt")
        FileOperations.rename(File(tmp.root, "a.txt"), "b.txt")
    }

    @Test fun emptyFolderAndLongNames() = runTest {
        val empty = tmp.newFolder("empty")
        val long = "n".repeat(200) + ".txt"
        File(empty, long).writeText("1")
        val r = FileOperations.copy(listOf(empty), tmp.newFolder("dest"), ConflictPolicy.KEEP_BOTH)
        assertTrue(r is OpResult.Success)
        assertTrue(File(tmp.root, "dest/empty/$long").exists())
    }

    @Test fun batchRenamePatterns() {
        val items = listOf("IMG_1.jpg", "IMG_2.jpg", "notes.txt").map { FileItem("/x/$it", it, false, 1, 0, false) }
        val plan = BatchRename.plan(items, "Trip_{n}", start = 1, pad = 3)
        assertEquals(listOf("Trip_001.jpg", "Trip_002.jpg", "Trip_003.txt"), plan.map { it.to })
        val regex = BatchRename.plan(items.take(2), "{name}", find = "IMG_(\\d+)", replace = "photo-$1", useRegex = true)
        assertEquals(listOf("photo-1.jpg", "photo-2.jpg"), regex.map { it.to })
        val dup = BatchRename.plan(items.take(2), "same")
        assertNotNull(dup[1].error)
    }
}
