package com.lyvt.files.feature.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.database.SnapshotDao
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.Pill
import com.lyvt.files.ui.SectionHeader
import com.lyvt.files.ui.color
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class TimelineViewModel @Inject constructor(dao: SnapshotDao) : ViewModel() {
    val snapshots = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/** Shows only snapshots that were actually recorded (every ~12 h by WorkManager, and when Home opens). */
@Composable
fun TimelineScreen(nav: NavHostController, vm: TimelineViewModel = hiltViewModel()) {
    val all by vm.snapshots.collectAsStateWithLifecycle()
    var days by remember { mutableIntStateOf(30) }
    val volumes = all.map { it.volumeId }.distinct()
    val primary = all.filter { it.volumeId == (volumes.firstOrNull { it == "primary" } ?: volumes.firstOrNull()) }
        .filter { days == 0 || it.timestamp >= System.currentTimeMillis() - days * 86_400_000L }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Storage timeline", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(7 to "7 days", 30 to "30 days", 365 to "1 year", 0 to "All").forEach { (d, l) -> Pill(l, days == d) { days = d } } } }
        if (primary.size < 2) item {
            EmptyState(Icons.Outlined.Timeline, "Collecting history", "Astrum records a snapshot about every 12 hours. Charts appear once at least two real snapshots exist (${primary.size} so far). Nothing is estimated or back-filled.")
        } else {
            val first = primary.first(); val last = primary.last()
            item {
                LyvtCard {
                    SectionHeader("Used space")
                    TrendChart(primary.map { it.timestamp to it.usedBytes })
                    val delta = last.usedBytes - first.usedBytes
                    Text("${if (delta >= 0) "+" else "−"}${Formatters.size(kotlin.math.abs(delta))} since ${Formatters.shortDate(first.timestamp)} · ${primary.size} snapshots",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Free space")
                    TrendChart(primary.map { it.timestamp to (it.totalBytes - it.usedBytes) })
                    Text("Now ${Formatters.size(last.totalBytes - last.usedBytes)} free", style = MaterialTheme.typography.bodySmall)
                }
            }
            val counted = primary.filter { it.fileCount >= 0 }
            if (counted.size >= 2) item {
                LyvtCard {
                    SectionHeader("Indexed media files")
                    TrendChart(counted.map { it.timestamp to it.fileCount.toLong() })
                    Text("${counted.first().fileCount} → ${counted.last().fileCount} files (MediaStore index)", style = MaterialTheme.typography.bodySmall)
                }
            }
            val withCats = primary.filter { it.categoryJson.isNotBlank() }
            if (withCats.size >= 2) item {
                LyvtCard {
                    SectionHeader("Category growth")
                    val a = JSONObject(withCats.first().categoryJson); val b = JSONObject(withCats.last().categoryJson)
                    FileCategory.entries.forEach { c ->
                        val d = b.optLong(c.name) - a.optLong(c.name)
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(c.name.lowercase().replaceFirstChar(Char::uppercase), color = c.color(), modifier = Modifier.weight(1f))
                            Text("${Formatters.size(b.optLong(c.name))}  (${if (d >= 0) "+" else "−"}${Formatters.size(kotlin.math.abs(d))})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
