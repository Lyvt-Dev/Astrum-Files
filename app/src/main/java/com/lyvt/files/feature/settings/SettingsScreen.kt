package com.lyvt.files.feature.settings

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.database.OperationDao
import com.lyvt.files.core.settings.AppSettings
import com.lyvt.files.core.settings.SettingsRepository
import com.lyvt.files.core.settings.ThemeMode
import com.lyvt.files.ui.Accents
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.Pill
import com.lyvt.files.ui.Routes
import com.lyvt.files.ui.SectionHeader
import com.lyvt.files.ui.TextInputDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val repo: SettingsRepository, private val ops: OperationDao) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val history = ops.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(t) }
    fun clearHistory() = viewModelScope.launch { ops.clear() }
    private val backupFile get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "lyvt-settings.json")
    suspend fun exportSettings(): String = withContext(Dispatchers.IO) {
        runCatching { backupFile.parentFile?.mkdirs(); backupFile.writeText(repo.exportJson()); "Saved to Documents/${backupFile.name}" }.getOrElse { "Export failed: ${it.message}" }
    }
    suspend fun importSettings(): String = withContext(Dispatchers.IO) {
        runCatching { repo.importJson(backupFile.readText()); "Settings restored" }.getOrElse { "Import failed: ${it.message}" }
    }
}

@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editList by remember { mutableStateOf<String?>(null) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Appearance")
                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        Pill(stringResource(R.string.theme_system), s.themeMode == ThemeMode.SYSTEM) { vm.update { it.copy(themeMode = ThemeMode.SYSTEM) } }
                        Pill(stringResource(R.string.theme_light), s.themeMode == ThemeMode.LIGHT) { vm.update { it.copy(themeMode = ThemeMode.LIGHT) } }
                        Pill(stringResource(R.string.theme_dark), s.themeMode == ThemeMode.DARK) { vm.update { it.copy(themeMode = ThemeMode.DARK) } }
                    }
                    Toggle(stringResource(R.string.dynamic_color), "Android 12+", s.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }
                    Text(stringResource(R.string.accent), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        Accents.forEachIndexed { i, c ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(c)
                                .border(3.dp, if (s.accent == i) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .clickable { vm.update { it.copy(accent = i, dynamicColor = false) } })
                        }
                    }
                    Text("Font size: ${(s.fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(s.fontScale, { v -> vm.update { it.copy(fontScale = (v * 20).toInt() / 20f) } }, valueRange = 0.85f..1.3f)
                    Toggle("Reduce animations", null, s.reduceMotion) { v -> vm.update { it.copy(reduceMotion = v) } }
                    Toggle("Compact dashboard", null, s.compactDashboard) { v -> vm.update { it.copy(compactDashboard = v) } }
                    Toggle("Start in Files", "Instead of Home", s.startPage == "files") { v -> vm.update { it.copy(startPage = if (v) "files" else "home") } }
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Browsing")
                    Toggle(stringResource(R.string.show_hidden), null, s.showHidden) { v -> vm.update { it.copy(showHidden = v) } }
                    Toggle(stringResource(R.string.grid_view), null, s.gridView) { v -> vm.update { it.copy(gridView = v) } }
                    Toggle("Dense list", null, s.compactList) { v -> vm.update { it.copy(compactList = v) } }
                    Toggle("Thumbnails", "Preview images and videos", s.showThumbnails) { v -> vm.update { it.copy(showThumbnails = v) } }
                    Toggle("Folders first", null, s.sort.foldersFirst) { v -> vm.update { it.copy(sort = it.sort.copy(foldersFirst = v)) } }
                }
            }
            item {
                LyvtCard {
                    SectionHeader(stringResource(R.string.trash))
                    Toggle("Use trash when deleting", "Off = delete permanently (always confirmed)", s.useTrash) { v -> vm.update { it.copy(useTrash = v) } }
                    Text("Keep items for ${s.trashRetentionDays} days", style = MaterialTheme.typography.labelLarge)
                    Slider(s.trashRetentionDays.toFloat(), { v -> vm.update { it.copy(trashRetentionDays = v.toInt()) } }, valueRange = 1f..90f)
                    Text("Maximum trash size: ${Formatters.size(s.trashLimitMb * 1024L * 1024)}", style = MaterialTheme.typography.labelLarge)
                    Slider(s.trashLimitMb.toFloat(), { v -> vm.update { it.copy(trashLimitMb = (v / 256).toInt() * 256) } }, valueRange = 256f..20480f)
                    TextButton(onClick = { nav.navigate(Routes.TRASH) }) { Text("Open trash") }
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Vault")
                    val opts = listOf(0 to "Immediately", 30 to "30 s", 60 to "1 min", 300 to "5 min", 900 to "15 min")
                    Text("Auto-lock after leaving the app", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        opts.forEach { (sec, l) -> Pill(l, s.vaultAutoLockSeconds == sec) { vm.update { it.copy(vaultAutoLockSeconds = sec) } } }
                    }
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Cleaner & scanning")
                    Text("Excluded folders: ${s.scanExclusions.joinToString().ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editList = "excl" }) { Text("Edit exclusions") }
                    Text("Protected folders (never suggested for cleanup): ${s.protectedDirs.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editList = "prot" }) { Text("Edit protected folders") }
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Data")
                    TextButton(onClick = { scope.launch { snackbar.showSnackbar(vm.exportSettings()) } }) { Text("Back up settings (JSON)") }
                    TextButton(onClick = { scope.launch { snackbar.showSnackbar(vm.importSettings()) } }) { Text("Restore settings from Documents/lyvt-settings.json") }
                    TextButton(onClick = { nav.navigate(Routes.HISTORY) }) { Text(stringResource(R.string.history)) }
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Privacy")
                    Text("Lyvt Files contains no ads, no trackers and no analytics. It never uploads anything unless you explicitly start a transfer. " +
                        "All scanning, hashing and image analysis run on this device.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(6.dp))
                    Text("Version ${com.lyvt.files.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    editList?.let { which ->
        val initial = if (which == "excl") s.scanExclusions.joinToString(", ") else s.protectedDirs.joinToString(", ")
        TextInputDialog(if (which == "excl") "Excluded folders" else "Protected folders", initial = initial,
            label = "Relative to storage root, comma separated (e.g. DCIM/Camera)", onConfirm = { text ->
                val set = text.split(',').map { it.trim().trim('/') }.filter { it.isNotEmpty() }.toSet()
                vm.update { if (which == "excl") it.copy(scanExclusions = set) else it.copy(protectedDirs = set) }
            }, onDismiss = { editList = null })
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun HistoryScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val items by vm.history.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(stringResource(R.string.history), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (items.isNotEmpty()) TextButton(onClick = vm::clearHistory) { Text("Clear") }
            }
        }
        if (items.isEmpty()) item { Text("No operations yet.", Modifier.padding(16.dp)) }
        items(items, key = { it.id }) { op ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(if (op.success) "✓" else "✗", color = if (op.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.width(24.dp))
                Column {
                    Text("${op.type.replaceFirstChar(Char::uppercase)} · ${op.summary}", style = MaterialTheme.typography.bodyMedium)
                    Text(Formatters.date(op.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
