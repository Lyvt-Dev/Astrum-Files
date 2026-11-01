package com.lyvt.files.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyvt.files.core.database.SnapshotDao
import com.lyvt.files.core.database.StorageSnapshotEntity
import com.lyvt.files.core.scan.DuplicateFinder
import com.lyvt.files.core.scan.ScanCache
import com.lyvt.files.core.storage.StorageVolumeInfo
import com.lyvt.files.core.storage.StorageVolumes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val cache: ScanCache,
    volumes: StorageVolumes,
    snapshots: SnapshotDao,
) : ViewModel() {
    val scan = cache.result
    val running = MutableStateFlow(false)
    val progress = cache.progress
    val volume = MutableStateFlow<StorageVolumeInfo?>(null)
    val duplicateWaste = MutableStateFlow<Long?>(null)
    val history: StateFlow<List<StorageSnapshotEntity>> = volume.flatMapLatest { v -> v?.let { snapshots.observe(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) { volume.value = volumes.volumes().firstOrNull { it.isPrimary } }
    }

    fun scan(force: Boolean) = viewModelScope.launch {
        running.value = true
        try { cache.get(force); duplicateWaste.value = null } finally { running.value = false }
    }

    fun computeWaste() = viewModelScope.launch(Dispatchers.IO) {
        val s = cache.get()
        duplicateWaste.value = -1
        duplicateWaste.value = DuplicateFinder.find(s.allFiles, 1024).sumOf { it.wasted }
    }
}
