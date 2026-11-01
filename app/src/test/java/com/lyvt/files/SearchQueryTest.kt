package com.lyvt.files

import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileSearch
import com.lyvt.files.core.storage.SearchQuery
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchQueryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesOperators() {
        val q = SearchQuery.parse("holiday ext:jpg,PNG size>1.5mb size<1gb after:2024-01-01 type:image \"two words\"")
        assertEquals("holiday two words", q.text)
        assertEquals(setOf("jpg", "png"), q.extensions)
        assertEquals((1.5 * 1024 * 1024).toLong(), q.minSize)
        assertEquals(1024L * 1024 * 1024, q.maxSize)
        assertNotNull(q.after)
        assertEquals(FileCategory.IMAGE, q.category)
    }

    @Test fun recursiveSearchFindsNestedAndRespectsHidden() = runTest {
        val root = tmp.newFolder("root")
        java.io.File(root, "a/b/c").mkdirs()
        java.io.File(root, "a/b/c/Report-2024.pdf").writeText("x")
        java.io.File(root, "a/report.txt").writeText("y")
        java.io.File(root, ".hidden").mkdirs(); java.io.File(root, ".hidden/report.pdf").writeText("z")
        val res = FileSearch.search(root, SearchQuery.parse("report ext:pdf")).toList().flatten()
        assertEquals(listOf("Report-2024.pdf"), res.map { it.name })
        val withHidden = FileSearch.search(root, SearchQuery.parse("report ext:pdf", includeHidden = true)).toList().flatten()
        assertEquals(2, withHidden.size)
    }

    @Test fun sizeFilterExcludesFolders() {
        val f = tmp.newFolder("dir")
        assertFalse(SearchQuery.parse("size>0").matches(f))
        val file = tmp.newFile("big.bin").apply { writeBytes(ByteArray(2048)) }
        assertTrue(SearchQuery.parse("size>1kb").matches(file))
        assertFalse(SearchQuery.parse("size<1kb").matches(file))
    }
}
