package com.lyvt.files.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyvt.files.core.database.FavoriteEntity
import com.lyvt.files.core.database.RecentEntity
import com.lyvt.files.core.database.SnapshotDao
import com.lyvt.files.core.database.StorageSnapshotEntity
import com.lyvt.files.core.settings.AppSettings
import com.lyvt.files.core.settings.SettingsRepository
import com.lyvt.files.core.storage.CategoryStat
import com.lyvt.files.core.storage.FileRepository
import com.lyvt.files.core.storage.MediaStats
import com.lyvt.files.core.storage.StorageVolumeInfo
import com.lyvt.files.core.storage.StorageVolumes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val volumesSrc: StorageVolumes,
    private val mediaStats: MediaStats,
    private val repo: FileRepository,
    private val snapshots: SnapshotDao,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val volumes = MutableStateFlow<List<StorageVolumeInfo>>(emptyList())
    val categories = MutableStateFlow<List<CategoryStat>?>(null)
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val recents: StateFlow<List<RecentEntity>> = repo.recentFiles(12).map { l -> l.filter { File(it.path).exists() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val frequent: StateFlow<List<RecentEntity>> = repo.frequentFiles(6).map { l -> l.filter { it.openCount > 1 && File(it.path).exists() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favorites: StateFlow<List<FavoriteEntity>> = repo.favoriteList.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trashCount = repo.trash.items.map { it.size to it.sumOf { e -> e.size } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 0L)

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        val v = volumesSrc.volumes()
        volumes.value = v
        // Record at most one snapshot per volume per 6 hours for the trend chart.
        v.forEach { vol ->
            val last = snapshots.latest(vol.id)
            if (last == null || System.currentTimeMillis() - last.timestamp > 6 * 3_600_000L)
                snapshots.insert(StorageSnapshotEntity(volumeId = vol.id, totalBytes = vol.totalBytes, usedBytes = vol.usedBytes, timestamp = System.currentTimeMillis()))
        }
        categories.value = mediaStats.categories()
    }

    fun toggleCompact() = viewModelScope.launch { settingsRepo.update { it.copy(compactDashboard = !it.compactDashboard) } }
    fun setCards(cards: List<String>) = viewModelScope.launch { settingsRepo.update { it.copy(dashboardCards = cards) } }
    fun unpin(f: FavoriteEntity) = viewModelScope.launch {
        repo.toggleFavorite(com.lyvt.files.core.storage.FileItem(f.path, f.name, f.isDirectory, 0, 0, false), true)
    }
}
