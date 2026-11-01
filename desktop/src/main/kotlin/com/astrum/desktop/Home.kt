package com.astrum.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.FastFs
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** Dashboard: everything shown is read live from this PC — no placeholder numbers. */
@Composable
fun HomeScreen() {
    val drives = remember { File.listRoots().filter { it.totalSpace > 0 } }
    var downloads by remember { mutableStateOf<List<FileItem>?>(null) }
    LaunchedEffect(Unit) {
        downloads = withContext(Dispatchers.IO) {
            FastFs.list(AppState.knownFolder("Downloads"))?.filter { !it.isDirectory && !it.hidden }?.sortedByDescending { it.mtime }?.take(8)?.map { it.toItem() } ?: emptyList()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 1000.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Hero(drives) }
            item { StatRow(drives) }
            item {
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1.65f), verticalArrangement = Arrangement.spacedBy(20.dp)) { DrivesCard(drives); RecentCard(downloads) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { QuickAccessCard(); BreakdownCard(); FeaturesCard() }
                } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { DrivesCard(drives); QuickAccessCard(); RecentCard(downloads); BreakdownCard(); FeaturesCard() }
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

@Composable
private fun Hero(drives: List<File>) {
    val free = drives.sumOf { it.usableSpace }
    val user = System.getProperty("user.name").orEmpty().replaceFirstChar { it.uppercase() }
    Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF241046), Color(0xFF42217D), Color(0xFF7548F8)), start = Offset.Zero, end = Offset(1600f, 400f)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Color(0x55E8B95A), Color.Transparent), center = Offset(size.width * 0.86f, size.height * 0.3f), radius = size.height * 1.1f), radius = size.height * 1.1f, center = Offset(size.width * 0.86f, size.height * 0.3f))
            // subtle star field (deterministic)
            val r = java.util.Random(7)
            repeat(40) { drawCircle(Color.White.copy(alpha = 0.15f + r.nextFloat() * 0.35f), 1f + r.nextFloat() * 1.6f, Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${greeting()}${if (user.isNotBlank()) ", $user" else ""}", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("${Formatters.size(free)} free across ${drives.size} drive${if (drives.size == 1) "" else "s"} · everything stays on your PC",
                    style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { AppState.screen = Screen.FILES }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF42217D))) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Browse files") }
                    HeroButton("Clean up", Icons.Filled.AutoAwesome) { AppState.screen = Screen.CLEANER }
                    HeroButton("Send to phone", Icons.Filled.PhoneAndroid) { AppState.screen = Screen.TRANSFER }
                }
            }
            Image(painterResource("astrum.png"), null, Modifier.size(150.dp).clip(RoundedCornerShape(36.dp)))
        }
    }
}

@Composable private fun HeroButton(t: String, icon: ImageVector, onClick: () -> Unit) =
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(t) }

@Composable
private fun StatRow(drives: List<File>) {
    val total = drives.sumOf { it.totalSpace }; val free = drives.sumOf { it.usableSpace }
    val scan = Scanner.result
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat(Modifier.weight(1f), "Total capacity", Formatters.size(total), "${drives.size} drive(s)", Icons.Filled.Storage, Accents[1])
        Stat(Modifier.weight(1f), "Used", Formatters.size(total - free), "${if (total > 0) ((total - free) * 100 / total) else 0}% of all drives", Icons.Filled.Folder, Accents[0])
        Stat(Modifier.weight(1f), "Free", Formatters.size(free), if (total > 0 && free * 10 < total) "Running low — try the Cleaner" else "Healthy", Icons.Filled.AutoAwesome, Accents[5])
        Stat(Modifier.weight(1f), "Last scan", scan?.let { "${"%,d".format(it.fileCount)} files" } ?: "Not yet",
            scan?.let { "${Formatters.size(it.totalBytes)} · ${Formatters.date(it.scannedAt)}" } ?: "Open Analytics to scan", Icons.Filled.AutoFixHigh, Gold)
    }
}

@Composable
private fun Stat(m: Modifier, label: String, value: String, sub: String, icon: ImageVector, tint: Color) = AstrumCard(m, padding = 18.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); IconBadge(icon, tint, 34.dp) }
    Text(value, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp))
    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CardTitle(t: String, action: String? = null, onAction: () -> Unit = {}) = Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(t, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); if (action != null) TextButton(onClick = onAction) { Text(action) } }

@Composable
private fun DrivesCard(drives: List<File>) = AstrumCard(padding = 22.dp) {
    CardTitle("Drives", "Analyse") { AppState.screen = Screen.ANALYTICS }
    drives.forEach { r ->
        val f = (r.totalSpace - r.usableSpace).toFloat() / r.totalSpace
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { AppState.openInExplorer(r.absolutePath) }.padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Storage, MaterialTheme.colorScheme.primary, 44.dp); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row { Text("Local disk ${r.path}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${Formatters.size(r.usableSpace)} free", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                    color = if (f > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
                Spacer(Modifier.height(4.dp))
                Text("${Formatters.size(r.totalSpace - r.usableSpace)} used of ${Formatters.size(r.totalSpace)} · ${(f * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecentCard(downloads: List<FileItem>?) = AstrumCard(padding = 22.dp) {
    val recents = AppState.recents.map(::File).filter { it.exists() }.take(8).map { FileItem.from(it) }
    var tab by remember { mutableStateOf(if (recents.isEmpty()) 1 else 0) }
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Activity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Pill("Recently opened", tab == 0) { tab = 0 }; Spacer(Modifier.width(8.dp)); Pill("New in Downloads", tab == 1) { tab = 1 }
    }
    val list = if (tab == 0) recents else downloads.orEmpty()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text("NAME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("SIZE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text("MODIFIED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(160.dp))
    }
    if (list.isEmpty()) Text(if (tab == 0) "Files you open in Astrum appear here." else if (downloads == null) "Loading…" else "Your Downloads folder is empty.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
    list.forEach { f ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { AppState.open(File(f.path)) }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(f, 36.dp, AppState.thumbnails); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(File(f.path).parent ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(if (f.isDirectory) "—" else Formatters.size(f.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(100.dp))
            Text(Formatters.date(f.lastModified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(160.dp))
        }
    }
}

@Composable
private fun QuickAccessCard() = AstrumCard(padding = 22.dp) {
    CardTitle("Quick access", "All files") { AppState.screen = Screen.FILES }
    listOf(Triple("Desktop", Icons.Filled.DesktopWindows, Accents[1]), Triple("Downloads", Icons.Filled.Download, Accents[0]), Triple("Documents", Icons.Filled.Description, Accents[5]),
        Triple("Pictures", Icons.Filled.Image, Accents[2]), Triple("Music", Icons.Filled.MusicNote, Color(0xFFFF8A3D)), Triple("Videos", Icons.Filled.Movie, Accents[3]))
        .map { Triple(it, AppState.knownFolder(it.first), 0) }.filter { it.second.exists() }
        .ifEmpty { listOf(Triple(Triple("Home", Icons.Filled.Folder, Accents[0]), AppState.home, 0)) }.forEach { (t, dir, _) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { AppState.openInExplorer(dir.absolutePath) }.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(t.second, t.third, 38.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(t.first, style = MaterialTheme.typography.titleSmall); Text(dir.absolutePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    if (AppState.favorites.isNotEmpty()) {
        Text("Favorites", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, start = 6.dp, bottom = 4.dp))
        AppState.favorites.take(6).forEach { p -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { if (File(p).isDirectory) AppState.openInExplorer(p) else AppState.open(File(p)) }.padding(6.dp)) {
            Text("★  ", color = Gold); Text(File(p).name.ifEmpty { p }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    }
}

@Composable
private fun BreakdownCard() = AstrumCard(padding = 22.dp) {
    val scope = rememberCoroutineScope()
    CardTitle("Storage breakdown", if (Scanner.result != null) "Details" else null) { AppState.screen = Screen.ANALYTICS }
    val s = Scanner.result
    if (s == null) {
        Text("Scan your home folder to see what takes up space (photos, videos, documents…). Nothing leaves your PC.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Scanner.running) {
            Spacer(Modifier.height(12.dp)); Text("${"%,d".format(Scanner.files)} files · ${Formatters.size(Scanner.bytes)}", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { Scanner.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        } else Button(onClick = { scope.launch { Scanner.get() } }, modifier = Modifier.padding(top = 12.dp)) { Text("Scan now") }
        return@AstrumCard
    }
    val cats = s.byCategory.entries.sortedByDescending { it.value.first }
    val total = s.totalBytes.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Donut(cats.map { it.value.first.toFloat() / total to it.key.color() }, Modifier.size(130.dp), 16.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(Formatters.size(s.totalBytes), style = MaterialTheme.typography.titleSmall); Text("scanned", style = MaterialTheme.typography.labelSmall) } }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) { cats.take(6).forEach { (c, v) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c.color())); Spacer(Modifier.width(8.dp))
                Text(c.label(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text(Formatters.size(v.first), style = MaterialTheme.typography.bodySmall) } } }
    }
}

private fun FileCategory.label() = when (this) { FileCategory.IMAGE -> "Images"; FileCategory.VIDEO -> "Videos"; FileCategory.AUDIO -> "Audio"; FileCategory.DOCUMENT -> "Documents"
    FileCategory.ARCHIVE -> "Archives"; FileCategory.APK -> "Apps"; FileCategory.OTHER -> "Other" }

@Composable
private fun FeaturesCard() = AstrumCard(padding = 22.dp) {
    CardTitle("Do more with Astrum")
    Feature(Icons.Filled.Lock, Accents[2], "Private Vault", "Encrypt files with AES-256 — only you can open them.") { AppState.screen = Screen.VAULT }
    Feature(Icons.Filled.AutoFixHigh, Gold, "Organizer", "Sort messy folders automatically, with preview and undo.") { AppState.screen = Screen.ORGANIZER }
    Feature(Icons.Filled.PhoneAndroid, Accents[0], "Quick Share", "Send files between this PC and your phone over Wi-Fi.") { AppState.screen = Screen.TRANSFER }
}

@Composable
private fun ColumnScope.Feature(icon: ImageVector, tint: Color, title: String, text: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint, 38.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
