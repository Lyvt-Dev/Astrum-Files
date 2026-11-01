package com.lyvt.files.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lyvt.files.core.database.SnapshotDao
import com.lyvt.files.core.database.StorageSnapshotEntity
import com.lyvt.files.core.organizer.OrganizerRepository
import com.lyvt.files.core.storage.MediaStats
import com.lyvt.files.core.storage.StorageVolumes
import com.lyvt.files.widget.StorageWidget
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Records a real storage snapshot (StatFs + MediaStore category totals) for the Storage Timeline. */
@HiltWorker
class SnapshotWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val volumes: StorageVolumes, private val snapshots: SnapshotDao, private val media: MediaStats,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val cats = runCatching { media.categories() }.getOrDefault(emptyList())
        val json = JSONObject().apply { cats.forEach { put(it.category.name, it.bytes) } }.toString()
        volumes.volumes().forEach { v ->
            snapshots.insert(StorageSnapshotEntity(volumeId = v.id, totalBytes = v.totalBytes, usedBytes = v.usedBytes,
                timestamp = System.currentTimeMillis(), fileCount = if (v.isPrimary) cats.sumOf { it.count } else -1,
                categoryJson = if (v.isPrimary) json else ""))
        }
        StorageWidget.refreshAll(applicationContext)
        return Result.success()
    }
}

/** Executes only organizer rules the user explicitly marked "run automatically". Moves are undoable from history. */
@HiltWorker
class OrganizerWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters, private val repo: OrganizerRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching { repo.runAutomatic(); Result.success() }.getOrElse { Result.retry() }
}

object Schedules {
    fun ensure(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork("snapshot", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SnapshotWorker>(12, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        wm.enqueueUniquePeriodicWork("organizer", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OrganizerWorker>(1, TimeUnit.DAYS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
    }
}
