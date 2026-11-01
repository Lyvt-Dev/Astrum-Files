package com.lyvt.files.core.storage

import android.content.Context
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class CategoryStat(val category: FileCategory, val bytes: Long, val count: Int)

/** Fast per-category totals from the MediaStore index (covers files indexed by the system). */
@Singleton
class MediaStats @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun categories(): List<CategoryStat> = withContext(Dispatchers.IO) {
        val acc = HashMap<FileCategory, LongArray>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE),
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} OR ${MediaStore.Files.FileColumns.SIZE} > 0",
                null, null,
            )?.use { c ->
                val n = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val s = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(n) ?: continue
                    if (!name.contains('.')) continue
                    val cat = FileCategory.of(name.substringAfterLast('.'))
                    acc.getOrPut(cat) { LongArray(2) }.let { it[0] += c.getLong(s); it[1]++ }
                }
            }
        }
        FileCategory.entries.map { CategoryStat(it, acc[it]?.get(0) ?: 0, (acc[it]?.get(1) ?: 0).toInt()) }
    }
}
