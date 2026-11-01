package com.lyvt.files

import com.lyvt.files.core.scan.DuplicateFinder
import com.lyvt.files.core.scan.StorageScanner
import com.lyvt.files.core.storage.FileCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class DuplicateFinderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun findsExactDuplicatesOnly() = runTest {
        val content = Random(1).nextBytes(100_000)
        val a = tmp.newFile("a.bin").apply { writeBytes(content) }
        val b = File(tmp.newFolder("sub"), "b copy.bin").apply { writeBytes(content) }
        // Same size and same first 16 KiB, different tail -> must NOT be grouped.
        val c = tmp.newFile("c.bin").apply { writeBytes(content.copyOf().also { it[99_999] = (it[99_999] + 1).toByte() }) }
        val groups = DuplicateFinder.find(listOf(a, b, c))
        assertEquals(1, groups.size)
        assertEquals(setOf(a, b), groups[0].files.toSet())
        assertEquals(100_000L, groups[0].wasted)
    }

    @Test fun defaultSelectionKeepsOneCopy() = runTest {
        val bytes = ByteArray(5000) { 3 }
        val files = (1..3).map { tmp.newFile("d$it").apply { writeBytes(bytes) } }
        val groups = DuplicateFinder.find(files)
        val sel = DuplicateFinder.defaultSelection(groups)
        assertEquals(2, sel.size)
        assertTrue(DuplicateFinder.groupsFullySelected(groups, sel).isEmpty())
        assertEquals(1, DuplicateFinder.groupsFullySelected(groups, files.map { it.absolutePath }.toSet()).size)
    }

    @Test fun scannerCountsCategoriesAndEmptyFolders() = runTest {
        tmp.newFolder("empty")
        File(tmp.newFolder("pics"), "x.jpg").writeBytes(ByteArray(10))
        tmp.newFile("doc.pdf").writeBytes(ByteArray(20))
        val r = StorageScanner.scan(tmp.root)
        assertEquals(2, r.fileCount)
        assertEquals(30L, r.totalBytes)
        assertEquals(10L, r.byCategory[FileCategory.IMAGE]?.first)
        assertTrue(r.emptyFolders.any { it.endsWith("empty") })
        assertEquals("pics", r.topFolders.first().name)
    }
}
