package com.lyvt.files

import com.lyvt.files.core.common.NaturalOrder
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.core.storage.SortField
import com.lyvt.files.core.storage.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test fun numbersSortNumerically() {
        val names = listOf("file10.txt", "file2.txt", "File1.txt", "file02.txt", "img99999999999999999999.jpg", "img1.jpg")
        assertEquals(listOf("File1.txt", "file2.txt", "file02.txt", "file10.txt", "img1.jpg", "img99999999999999999999.jpg"), names.sortedWith(NaturalOrder))
    }

    @Test fun foldersFirstAndDescending() {
        fun item(n: String, dir: Boolean, size: Long = 0) = FileItem("/x/$n", n, dir, size, 0, false)
        val items = listOf(item("b.txt", false, 5), item("A", true), item("a.txt", false, 10), item("C", true))
        assertEquals(listOf("A", "C", "a.txt", "b.txt"), items.sortedWith(SortOrder().comparator()).map { it.name })
        assertEquals(listOf("A", "C", "a.txt", "b.txt"), items.sortedWith(SortOrder(SortField.SIZE, ascending = false).comparator()).map { it.name })
        assertEquals(listOf("a.txt", "b.txt", "A", "C"),
            items.sortedWith(SortOrder(SortField.SIZE, ascending = false, foldersFirst = false).comparator()).map { it.name })
    }

    @Test fun unicodeNames() {
        val sorted = listOf("日本", "Zebra", "äpfel", "apple").sortedWith(NaturalOrder)
        assertEquals("apple", sorted.first())
        assertEquals(4, sorted.toSet().size)
    }
}
