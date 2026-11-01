package com.lyvt.files.feature.trash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.settings.SettingsRepository
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.core.storage.OpResult
import com.lyvt.files.core.storage.TrashManager
import com.lyvt.files.ui.ConfirmDialog
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.FileRow
import com.lyvt.files.ui.Pill
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(private val trash: TrashManager, settings: SettingsRepository) : ViewModel() {
    val items = trash.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, com.lyvt.files.core.settings.AppSettings())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private fun msg(r: OpResult, v: String) = messages.tryEmit(when (r) {
        is OpResult.Success -> "$v ${r.count} item(s)"; is OpResult.Partial -> "$v ${r.done}; failed: ${r.failed.joinToString { it.first + " (" + it.second + ")" }}"; is OpResult.Failure -> r.reason })
    fun restore(ids: List<Long>) = viewModelScope.launch { msg(trash.restore(ids), "Restored") }
    fun delete(ids: List<Long>) = viewModelScope.launch { msg(trash.deleteForever(ids), "Permanently deleted") }
    fun empty() = viewModelScope.launch { msg(trash.emptyAll(), "Permanently deleted") }
}

@Composable
fun TrashScreen(nav: NavHostController, vm: TrashViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var byDate by remember { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it); selected = emptySet() } }
    val sorted = if (byDate) items.sortedByDescending { it.deletedAt } else items.sortedByDescending { it.size }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text(stringResource(R.string.trash), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (selected.isNotEmpty()) {
                            TextButton(onClick = { vm.restore(selected.toList()) }) { Text(stringResource(R.string.restore)) }
                            TextButton(onClick = { confirm = "sel" }) { Text(stringResource(R.string.delete_forever), color = MaterialTheme.colorScheme.error) }
                        } else if (items.isNotEmpty()) TextButton(onClick = { confirm = "all" }) { Text(stringResource(R.string.empty_trash)) }
                    }
                    Text("${items.size} item(s) · ${Formatters.size(items.sumOf { it.size })} · auto-deleted after ${settings.trashRetentionDays} days, limit ${settings.trashLimitMb} MB",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                    Row(Modifier.padding(12.dp)) {
                        Pill("Newest", byDate) { byDate = true }; Text("  "); Pill("Largest", !byDate) { byDate = false }
                        Text("  "); Pill("Select all", false) { selected = items.map { it.id }.toSet() }
                    }
                }
            }
            if (items.isEmpty()) item { EmptyState(Icons.Outlined.DeleteOutline, "Trash is empty", "Files you delete in Lyvt appear here and can be restored.") }
            items(sorted, key = { it.id }) { e ->
                val fi = FileItem(e.trashPath, e.name, e.isDirectory, e.size, e.deletedAt, false)
                Column {
                    FileRow(fi, e.id in selected, false, compact = false, showThumbnails = true,
                        onClick = { selected = if (e.id in selected) selected - e.id else selected + e.id }, onLongClick = { selected = selected + e.id },
                        trailing = { Checkbox(e.id in selected, { selected = if (it) selected + e.id else selected - e.id }) })
                    Text("From ${e.originalPath} · deleted ${Formatters.date(e.deletedAt)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(start = 82.dp, end = 16.dp, bottom = 6.dp))
                }
            }
        }
    }
    when (confirm) {
        "sel" -> ConfirmDialog("Delete ${selected.size} item(s) forever?", "They cannot be restored afterwards.", stringResource(R.string.delete_forever), true,
            { vm.delete(selected.toList()) }, { confirm = null })
        "all" -> ConfirmDialog("Empty trash?", "All ${items.size} item(s) will be permanently deleted.", stringResource(R.string.empty_trash), true, vm::empty) { confirm = null }
    }
}
