package com.lyvt.files.feature.cleaner

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.ui.Accents
import com.lyvt.files.ui.CategoryColors
import com.lyvt.files.ui.ConfirmDialog
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.FileRow
import com.lyvt.files.ui.IconBadge
import com.lyvt.files.ui.LoadingState
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.openFile

private data class Tool(val mode: CleanerMode, val title: Int, val desc: String, val icon: ImageVector, val color: androidx.compose.ui.graphics.Color)

private val tools = listOf(
    Tool(CleanerMode.DUPLICATES, R.string.cleaner_duplicates, "Exact copies verified by SHA-256", Icons.Filled.ContentCopy, Accents[0]),
    Tool(CleanerMode.SIMILAR, R.string.cleaner_similar, "Near-identical shots via perceptual hash", Icons.Filled.PhotoLibrary, CategoryColors.image),
    Tool(CleanerMode.BLURRY, R.string.cleaner_blurry, "Possibly out-of-focus photos", Icons.Filled.BlurOn, CategoryColors.video),
    Tool(CleanerMode.LARGE, R.string.cleaner_large, "Files above a size you choose", Icons.Filled.Straighten, CategoryColors.audio),
    Tool(CleanerMode.OLD, R.string.cleaner_old, "Not modified for a long time", Icons.Filled.History, CategoryColors.archive),
    Tool(CleanerMode.EMPTY, R.string.cleaner_empty, "Folders with nothing inside", Icons.Filled.FolderOff, CategoryColors.other),
    Tool(CleanerMode.DOWNLOADS, R.string.cleaner_downloads, "Old downloads, APKs and partial files", Icons.Filled.Download, CategoryColors.document),
    Tool(CleanerMode.SCREENSHOTS, R.string.cleaner_screenshots, "Screenshots grouped by day", Icons.Filled.Screenshot, CategoryColors.apk),
    Tool(CleanerMode.APPS, R.string.cleaner_apps, "Apps not opened for 30+ days", Icons.Filled.Apps, Accents[3]),
)

@Composable
fun CleanerScreen(nav: NavHostController, vm: CleanerViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scanned by vm.scanProgress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(ui.mode != null) { vm.open(null) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (ui.mode != null && ui.mode != CleanerMode.APPS && ui.selected.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${ui.selected.size} selected", style = MaterialTheme.typography.titleSmall)
                        Text("Frees about ${Formatters.size(ui.selectedBytes)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { confirm = true }, shape = RoundedCornerShape(16.dp)) { Text("Review & delete") }
                }
            }
        }) { pad ->
        val mode = ui.mode
        if (mode == null) {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(Modifier.statusBarsPadding()) {
                        Text(stringResource(R.string.nav_cleaner), style = MaterialTheme.typography.headlineMedium)
                        Text("Everything is reviewed by you before anything is removed. Deleted items go to the trash.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(tools) { t ->
                    LyvtCard(onClick = { vm.open(t.mode) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(t.icon, t.color)
                            Spacer(Modifier.width(14.dp))
                            Column { Text(stringResource(t.title), style = MaterialTheme.typography.titleMedium)
                                Text(t.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        } else {
            val tool = tools.first { it.mode == mode }
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.open(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text(stringResource(tool.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (ui.running) TextButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) }
                        else TextButton(onClick = { vm.run(force = true) }) { Text(stringResource(R.string.rescan)) }
                    }
                }
                item { ModeOptions(mode, ui, vm) }
                if (mode in setOf(CleanerMode.SIMILAR, CleanerMode.BLURRY)) item {
                    Text(stringResource(R.string.heuristic_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                if (ui.running) item { LoadingState(stringResource(R.string.scanning), ui.progress.ifEmpty { "$scanned files indexed" }) }
                else if (ui.progress.isNotEmpty()) item { Text(ui.progress, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
                else if (mode == CleanerMode.APPS) {
                    if (ui.appsPermissionMissing) item {
                        EmptyState(Icons.Filled.Apps, "Usage access needed", "Android only shares app-usage history if you grant “Usage access”. Without it, unused apps cannot be determined.") {
                            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Open usage access settings") }
                        }
                    } else if (ui.apps.isEmpty()) item { EmptyState(Icons.Outlined.CheckCircle, "No unused apps", "All installed apps were used in the last 30 days.") }
                    else items(ui.apps, key = { it.packageName }) { a ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.label, style = MaterialTheme.typography.bodyLarge)
                                Text(a.lastUsed?.let { "Last used ${Formatters.shortDate(it)}" } ?: "No recorded use in the past year",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) }) { Text("Details") }
                            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.packageName}"))) }) { Text("Uninstall") }
                        }
                    }
                } else if (ui.groups.isEmpty()) item { EmptyState(Icons.Outlined.CheckCircle, "Nothing found", ui.lastScanSummary ?: "Your storage looks tidy.") }
                else {
                    item {
                        val total = ui.groups.sumOf { g -> if (mode == CleanerMode.DUPLICATES) g.items.drop(1).sumOf { it.size } else g.items.sumOf { it.size } }
                        Text("${ui.lastScanSummary ?: ""}\nPotential savings: ${Formatters.size(total)}", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                    ui.groups.forEachIndexed { gi, g ->
                        item(key = "g$gi") {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.title, style = MaterialTheme.typography.titleSmall)
                                    g.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                val dupLike = mode == CleanerMode.DUPLICATES || mode == CleanerMode.SIMILAR
                                TextButton(onClick = { vm.selectGroup(g, keepFirst = dupLike) }) { Text(if (dupLike) "Keep first" else "Select all") }
                            }
                        }
                        items(g.items, key = { "g$gi" + it.path }) { item ->
                            FileRow(item, item.path in ui.selected, false, compact = true, showThumbnails = true,
                                onClick = { if (!nav.openFile(item.path)) vm.toggle(item.path) }, onLongClick = { vm.toggle(item.path) },
                                trailing = { Checkbox(item.path in ui.selected, { vm.toggle(item.path) }) })
                        }
                    }
                }
            }
        }
    }
    if (confirm) ConfirmDialog("Remove ${ui.selected.size} item(s)?",
        "About ${Formatters.size(ui.selectedBytes)} will be freed. Items are moved to the trash when enabled in settings, so you can restore them.",
        stringResource(R.string.delete), destructive = true, onConfirm = vm::deleteSelected, onDismiss = { confirm = false })
}

@Composable
private fun ModeOptions(mode: CleanerMode, ui: CleanerUi, vm: CleanerViewModel) {
    val (label, value, range, set) = when (mode) {
        CleanerMode.LARGE -> Quad("Minimum size: ${ui.largeThresholdMb} MB", ui.largeThresholdMb.toFloat(), 10f..2000f) { v: Float -> vm.setLarge(v.toInt()) }
        CleanerMode.OLD -> Quad("Not modified for: ${ui.oldMonths} months", ui.oldMonths.toFloat(), 1f..60f) { v: Float -> vm.setOld(v.toInt()) }
        CleanerMode.SIMILAR -> Quad("Similarity tolerance: ${ui.similarity} bits (lower = stricter)", ui.similarity.toFloat(), 0f..20f) { v: Float -> vm.setSimilarity(v.toInt()) }
        CleanerMode.BLURRY -> Quad("Sharpness threshold: ${ui.blurThreshold}", ui.blurThreshold.toFloat(), 10f..300f) { v: Float -> vm.setBlur(v.toInt()) }
        else -> return
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value, set, valueRange = range, onValueChangeFinished = { vm.run() })
    }
}

private data class Quad(val a: String, val b: Float, val c: ClosedFloatingPointRange<Float>, val d: (Float) -> Unit)

