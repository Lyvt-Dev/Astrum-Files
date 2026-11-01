package com.lyvt.files.feature.home

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.ui.Accents
import com.lyvt.files.ui.DonutChart
import com.lyvt.files.ui.FileThumb
import com.lyvt.files.ui.IconBadge
import com.lyvt.files.ui.LocalLyvt
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.Routes
import com.lyvt.files.ui.SectionHeader
import com.lyvt.files.ui.UsageBar
import com.lyvt.files.ui.color
import com.lyvt.files.ui.icon
import com.lyvt.files.ui.openFile
import java.io.File

private val cardTitles = mapOf("storage" to "Storage", "categories" to "Categories", "quick" to "Quick access",
    "recent" to "Recent files", "favorites" to "Favorites & pinned", "cleanup" to "Cleanup suggestions")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(nav: NavHostController, vm: HomeViewModel = hiltViewModel()) {
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val frequent by vm.frequent.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val trash by vm.trashCount.collectAsStateWithLifecycle()
    var customize by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }
    val lyvt = LocalLyvt.current
    val compact = settings.compactDashboard

    LazyColumn(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to lyvt.gradientStart, 0.35f to MaterialTheme.colorScheme.background)),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    ) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Astrum Files", style = MaterialTheme.typography.headlineMedium)
                    Text("Everything on this device, organised.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { customize = true }) { Icon(Icons.Filled.Tune, "Customize dashboard") }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
            }
        }
        settings.dashboardCards.forEach { card ->
            when (card) {
                "storage" -> items(volumes, key = { "vol" + it.id }) { v ->
                    LyvtCard(onClick = { nav.navigate(Routes.files(v.path)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!compact) {
                                DonutChart(listOf(v.usedFraction to MaterialTheme.colorScheme.primary), Modifier.size(112.dp), stroke = 14.dp) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("${(v.usedFraction * 100).toInt()}%", style = MaterialTheme.typography.titleLarge)
                                        Text(stringResource(R.string.used), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.width(20.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (v.isRemovable) Icons.Filled.SdCard else Icons.Filled.Smartphone, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(v.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("${Formatters.size(v.usedBytes)} of ${Formatters.size(v.totalBytes)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${Formatters.size(v.freeBytes)} ${stringResource(R.string.free).lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (compact) { Spacer(Modifier.height(8.dp)); UsageBar(v.usedFraction) }
                            }
                        }
                    }
                }
                "categories" -> item(key = "cats") {
                    LyvtCard {
                        SectionHeader(stringResource(R.string.categories))
                        val list = cats
                        if (list == null) Text("Reading media index…", style = MaterialTheme.typography.bodySmall)
                        else {
                            val total = list.sumOf { it.bytes }.coerceAtLeast(1)
                            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                                list.filter { it.bytes > 0 }.forEach { s -> Box(Modifier.weight(s.bytes.toFloat() / total).fillMaxSize().background(s.category.color())) }
                            }
                            Spacer(Modifier.height(12.dp))
                            list.filter { it.category != FileCategory.OTHER }.chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { s -> CategoryChip(s.category, s.count, s.bytes, Modifier.weight(1f)) { nav.navigate(Routes.files(null, categoryQuery(s.category))) } }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            Text("Based on the system media index; open Analytics for a full scan.", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
                "quick" -> item(key = "quick") {
                    Column {
                        SectionHeader(stringResource(R.string.quick_access))
                        val root = Environment.getExternalStorageDirectory().absolutePath
                        val tiles: List<Triple<String, Pair<ImageVector, androidx.compose.ui.graphics.Color>, () -> Unit>> = listOf(
                            Triple(stringResource(R.string.downloads), Icons.Filled.Download to Accents[0]) { nav.navigate(Routes.files("$root/${Environment.DIRECTORY_DOWNLOADS}")) },
                            Triple("Camera", FileCategory.IMAGE.icon() to FileCategory.IMAGE.color()) { nav.navigate(Routes.files("$root/${Environment.DIRECTORY_DCIM}")) },
                            Triple(stringResource(R.string.documents), FileCategory.DOCUMENT.icon() to FileCategory.DOCUMENT.color()) { nav.navigate(Routes.files("$root/${Environment.DIRECTORY_DOCUMENTS}")) },
                            Triple(stringResource(R.string.apps), FileCategory.APK.icon() to FileCategory.APK.color()) { nav.navigate(Routes.files(root, "type:apk")) },
                            Triple(stringResource(R.string.trash) + if (trash.first > 0) " ${trash.first}" else "", Icons.Filled.Delete to FileCategory.OTHER.color()) { nav.navigate(Routes.TRASH) },
                            Triple(stringResource(R.string.nav_vault), Icons.Outlined.Lock to Accents[2]) { nav.navigate(Routes.VAULT) },
                            Triple("History", Icons.Filled.History to Accents[1]) { nav.navigate(Routes.HISTORY) },
                            Triple(stringResource(R.string.nav_cleaner), Icons.Filled.AutoAwesome to Accents[3]) { nav.navigate(Routes.CLEANER) },
                        )
                        LyvtCard(padding = 12.dp) {
                            tiles.chunked(4).forEachIndexed { i, row ->
                                if (i > 0) Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { (label, iv, action) -> QuickTile(label, iv.first, iv.second, Modifier.weight(1f), action) }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                "recent" -> if (recents.isNotEmpty() || frequent.isNotEmpty()) item(key = "recent") {
                    Column {
                        if (recents.isNotEmpty()) {
                            SectionHeader(stringResource(R.string.recent_files))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(recents, key = { it.path }) { r -> RecentCard(r.path, settings.showThumbnails) { if (!nav.openFile(r.path)) nav.navigate(Routes.files(File(r.path).parent)) } }
                            }
                        }
                        if (frequent.isNotEmpty()) {
                            SectionHeader(stringResource(R.string.frequent_files))
                            frequent.forEach { r ->
                                TextButton(onClick = { if (!nav.openFile(r.path)) nav.navigate(Routes.files(File(r.path).parent)) }) {
                                    Text("${r.name}  ·  opened ${r.openCount}×", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                "favorites" -> if (favorites.isNotEmpty()) item(key = "favs") {
                    LyvtCard(padding = 8.dp) {
                        SectionHeader(stringResource(R.string.favorites), Modifier.padding(horizontal = 10.dp))
                        favorites.take(10).forEach { f ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .let { m -> m }
                                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(if (f.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    if (f.pinned) Accents.getOrElse(f.color) { Accents[0] } else MaterialTheme.colorScheme.primary, 36.dp)
                                Spacer(Modifier.width(12.dp))
                                TextButton(onClick = { if (f.isDirectory) nav.navigate(Routes.files(f.path)) else if (!nav.openFile(f.path)) nav.navigate(Routes.files(File(f.path).parent)) },
                                    modifier = Modifier.weight(1f)) {
                                    Text(f.name + if (f.pinned) "  📌" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                                }
                                IconButton(onClick = { vm.unpin(f) }) { Icon(Icons.Filled.Delete, "Remove", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                "cleanup" -> item(key = "cleanup") {
                    LyvtCard(onClick = { nav.navigate(Routes.CLEANER) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Filled.AutoAwesome, MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.cleanup_tips), style = MaterialTheme.typography.titleMedium)
                                val tips = buildList {
                                    if (trash.first > 0) add("Trash holds ${Formatters.size(trash.second)}")
                                    volumes.firstOrNull { it.isPrimary }?.let { if (it.usedFraction > 0.85f) add("Storage is ${(it.usedFraction * 100).toInt()}% full") }
                                    add("Find duplicates, large & old files")
                                }
                                Text(tips.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (customize) {
        var cards by remember { mutableStateOf(settings.dashboardCards) }
        AlertDialog(onDismissRequest = { customize = false }, title = { Text("Customize dashboard") }, text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Compact mode", Modifier.weight(1f)); Switch(compact, { vm.toggleCompact() }) }
                val all = cards + cardTitles.keys.filterNot { it in cards }
                all.forEach { key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(key in cards, { on -> cards = if (on) cards + key else cards - key })
                        Text(cardTitles[key] ?: key, Modifier.weight(1f))
                        val i = cards.indexOf(key)
                        IconButton(enabled = i > 0, onClick = { cards = cards.toMutableList().apply { add(i - 1, removeAt(i)) } }) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                        IconButton(enabled = i >= 0 && i < cards.lastIndex, onClick = { cards = cards.toMutableList().apply { add(i + 1, removeAt(i)) } }) { Icon(Icons.Filled.ArrowDownward, "Move down") }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { vm.setCards(cards); customize = false }) { Text(stringResource(R.string.ok)) } })
    }
}

fun categoryQuery(c: FileCategory) = when (c) {
    FileCategory.IMAGE -> "type:image"; FileCategory.VIDEO -> "type:video"; FileCategory.AUDIO -> "type:audio"
    FileCategory.DOCUMENT -> "type:doc"; FileCategory.ARCHIVE -> "type:archive"; FileCategory.APK -> "type:apk"; FileCategory.OTHER -> ""
}

@Composable
private fun CategoryChip(c: FileCategory, count: Int, bytes: Long, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(c.color().copy(alpha = 0.12f)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(c.icon(), null, tint = c.color(), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(c.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("$count · ${Formatters.size(bytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun QuickTile(label: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, tint, 48.dp)
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecentCard(path: String, thumbs: Boolean, onClick: () -> Unit) {
    val f = File(path)
    val item = FileItem.from(f)
    LyvtCard(Modifier.width(132.dp), padding = 10.dp, onClick = onClick) {
        FileThumb(item, 112.dp, thumbs)
        Spacer(Modifier.height(8.dp))
        Text(item.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text(Formatters.size(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
