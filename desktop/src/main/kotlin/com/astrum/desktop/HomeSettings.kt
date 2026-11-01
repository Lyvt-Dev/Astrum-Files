package com.astrum.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.FileItem
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen() {
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), 0.3f to MaterialTheme.colorScheme.background)),
        contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource("astrum.png"), null, Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))); Spacer(Modifier.width(14.dp))
            Column { Text("Astrum Files", style = MaterialTheme.typography.headlineMedium); Text("Your files, in orbit.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            File.listRoots().filter { it.totalSpace > 0 }.forEach { r -> val f = (r.totalSpace - r.usableSpace).toFloat() / r.totalSpace
                AstrumCard(Modifier.width(300.dp), onClick = { AppState.openInExplorer(r.absolutePath) }) { Row(verticalAlignment = Alignment.CenterVertically) {
                    Donut(listOf(f to MaterialTheme.colorScheme.primary), Modifier.size(90.dp), 12.dp) { Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.width(16.dp)); Column { Text("Drive ${r.path}", style = MaterialTheme.typography.titleMedium)
                        Text("${Formatters.size(r.totalSpace - r.usableSpace)} of ${Formatters.size(r.totalSpace)}", style = MaterialTheme.typography.bodyMedium)
                        Text("${Formatters.size(r.usableSpace)} free", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } } } }
        item { SectionHeader("Quick access") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("Desktop", Icons.Filled.DesktopWindows, "Desktop"), Triple("Downloads", Icons.Filled.Download, "Downloads"), Triple("Documents", Icons.Filled.Description, "Documents"),
                Triple("Pictures", Icons.Filled.Image, "Pictures"), Triple("Music", Icons.Filled.MusicNote, "Music"), Triple("Videos", Icons.Filled.Movie, "Videos")).filter { AppState.knownFolder(it.third).exists() }.forEachIndexed { i, (n, ic, d) ->
                Tile(n, ic, Accents[i % Accents.size]) { AppState.openInExplorer(AppState.knownFolder(d).absolutePath) } }
            Tile("Send to phone", Icons.Filled.PhoneAndroid, Accents[0]) { AppState.screen = Screen.TRANSFER }
            Tile("Cleaner", Icons.Filled.AutoAwesome, Accents[3]) { AppState.screen = Screen.CLEANER }
            Tile("Organizer", Icons.Filled.AutoFixHigh, Gold) { AppState.screen = Screen.ORGANIZER }
            Tile("Vault", Icons.Filled.Lock, Accents[2]) { AppState.screen = Screen.VAULT }
        } }
        if (AppState.recents.isNotEmpty()) { item { SectionHeader("Recent files") }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppState.recents.filter { File(it).exists() }.take(12).forEach { p -> val f = FileItem.from(File(p))
                    AstrumCard(Modifier.width(150.dp), padding = 10.dp, onClick = { AppState.open(File(p)) }) { FileThumb(f, 130.dp); Spacer(Modifier.height(6.dp))
                        Text(f.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) } } } } }
        if (AppState.favorites.isNotEmpty()) { item { SectionHeader("Favorites") }
            items(AppState.favorites.toList()) { p -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { if (File(p).isDirectory) AppState.openInExplorer(p) else AppState.open(File(p)) }.padding(8.dp)) {
                Text("★  ", color = Gold); Text(p, style = MaterialTheme.typography.bodyMedium) } } }
    }
}

@Composable private fun Tile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) =
    AstrumCard(Modifier.width(150.dp), padding = 14.dp, onClick = onClick) { IconBadge(icon, tint, 42.dp); Spacer(Modifier.height(10.dp)); Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }

@Composable
fun SettingsScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item { AstrumCard { SectionHeader("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to "Follow Windows", "light" to "Light", "dark" to "Dark").forEach { (k, l) -> Pill(l, AppState.themeMode == k) { AppState.themeMode = k; AppState.save() } } }
            Text("Accent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) { Accents.forEachIndexed { i, c ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(c).border(3.dp, if (AppState.accent == i) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape).clickable { AppState.accent = i; AppState.save() }) } }
            Toggle("Reduce animations", AppState.reduceMotion) { AppState.reduceMotion = it }
        } }
        item { AstrumCard { SectionHeader("Files")
            Toggle("Show hidden files", AppState.showHidden) { AppState.showHidden = it }; Toggle("Grid view", AppState.gridView) { AppState.gridView = it }
            Toggle("Thumbnails", AppState.thumbnails) { AppState.thumbnails = it }; Toggle("Delete to Recycle Bin (recommended)", AppState.useRecycleBin) { AppState.useRecycleBin = it }
            Toggle("Folders first", AppState.sort.foldersFirst) { AppState.sort = AppState.sort.copy(foldersFirst = it) }
        } }
        item { AstrumCard { SectionHeader("Cleaner & scanning")
            Text("Excluded folders: ${AppState.scanExclusions.joinToString().ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall)
            Row { TextButton(onClick = { pickFolder()?.let { AppState.scanExclusions = AppState.scanExclusions + it.absolutePath; AppState.save() } }) { Text("Add exclusion…") }
                if (AppState.scanExclusions.isNotEmpty()) TextButton(onClick = { AppState.scanExclusions = emptySet(); AppState.save() }) { Text("Clear") } } } }
        item { AstrumCard { SectionHeader("Operation history")
            if (AppState.log.isEmpty()) Text("No operations in this session.", style = MaterialTheme.typography.bodySmall)
            AppState.log.take(40).forEach { Text("${if (it.ok) "✓" else "✗"} ${it.type} · ${it.summary} · ${Formatters.date(it.at)}", style = MaterialTheme.typography.bodySmall) } } }
        item { AstrumCard { SectionHeader("Data & privacy")
            Text("Settings, rules and history are stored in ${Store.dir.absolutePath}. Astrum Files has no ads, no tracking and no account. Network is used only for Astrum Transfer sessions you start.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(Store.dir) } }) { Text("Open data folder") } } }
        item { AstrumCard { Row(verticalAlignment = Alignment.CenterVertically) { Image(painterResource("astrum.png"), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))); Spacer(Modifier.width(12.dp))
            Column { Text("Astrum Files for Windows", style = MaterialTheme.typography.titleMedium); Text("Version 1.0.0 · Java ${System.getProperty("java.version")}", style = MaterialTheme.typography.labelSmall) } } } }
    }
}

@Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!value); AppState.save() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, { onChange(it); AppState.save() }) }
