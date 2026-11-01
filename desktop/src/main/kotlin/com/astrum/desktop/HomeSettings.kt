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
        item { UpdatesCard() }
        item { AstrumCard { SectionHeader("Data & privacy")
            Text("Settings, rules and history are stored in ${Store.dir.absolutePath}. Astrum Files has no ads, no tracking and no account. Network is used only for Astrum Transfer sessions you start and the daily update check (GitHub; can be turned off).",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(Store.dir) } }) { Text("Open data folder") } } }
        item { AstrumCard { Row(verticalAlignment = Alignment.CenterVertically) { Image(painterResource("astrum.png"), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))); Spacer(Modifier.width(12.dp))
            Column { Text("Astrum Files for Windows", style = MaterialTheme.typography.titleMedium); Text("Version ${Updates.VERSION} · Java ${System.getProperty("java.version")}", style = MaterialTheme.typography.labelSmall) } } } }
    }
}

@Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!value); AppState.save() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, { onChange(it); AppState.save() }) }
