package com.lyvt.files.core.scan

import com.lyvt.files.core.settings.SettingsRepository
import com.lyvt.files.core.storage.StorageVolumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Shares one full-storage scan between Cleaner and Analytics; invalidated after 10 minutes or on demand. */
@Singleton
class ScanCache @Inject constructor(private val volumes: StorageVolumes, private val settings: SettingsRepository) {
    private val mutex = Mutex()
    private val _result = MutableStateFlow<ScanResult?>(null)
    val result: StateFlow<ScanResult?> = _result.asStateFlow()
    val progress = MutableStateFlow(0)

    suspend fun get(force: Boolean = false): ScanResult = mutex.withLock {
        val cur = _result.value
        if (!force && cur != null && System.currentTimeMillis() - cur.scannedAt < 10 * 60_000) return cur
        val excl = settings.current().scanExclusions.map { File(volumes.primaryRoot(), it).absolutePath }.toSet()
        val r = withContext(Dispatchers.IO) { StorageScanner.scan(File(volumes.primaryRoot()), excl) { progress.value = it } }
        _result.value = r
        r
    }

    fun invalidate() { _result.value = null }
}
