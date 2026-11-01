package com.lyvt.files.core.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StorageVolumeInfo(
    val id: String,
    val label: String,
    val path: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes == 0L) 0f else usedBytes.toFloat() / totalBytes
}

@Singleton
class StorageVolumes @Inject constructor(@ApplicationContext private val context: Context) {

    /** Mounted volumes reported by the system with real StatFs numbers. Removed SD cards simply disappear. */
    fun volumes(): List<StorageVolumeInfo> {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v ->
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            val dir: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory ?: return@mapNotNull null
            else if (v.isPrimary) Environment.getExternalStorageDirectory()
            else File("/storage/${v.uuid ?: return@mapNotNull null}")
            runCatching {
                val st = StatFs(dir.path)
                StorageVolumeInfo(
                    id = v.uuid ?: "primary",
                    label = v.getDescription(context),
                    path = dir.absolutePath,
                    isPrimary = v.isPrimary,
                    isRemovable = v.isRemovable,
                    totalBytes = st.totalBytes,
                    freeBytes = st.availableBytes,
                )
            }.getOrNull()
        }
    }

    fun primaryRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
