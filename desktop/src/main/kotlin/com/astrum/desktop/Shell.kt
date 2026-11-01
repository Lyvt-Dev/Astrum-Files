package com.astrum.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import java.io.File

/** Wide desktop sidebar with grouped navigation and a live system-drive meter. */
@Composable
fun Sidebar() {
    Column(Modifier.width(250.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 22.dp)) {
            Image(painterResource("astrum.png"), "Astrum Files", Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)))
            Spacer(Modifier.width(12.dp))
            Column { Text("Astrum Files", style = MaterialTheme.typography.titleLarge); Text("for Windows", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Group("Browse"); NavItem(Screen.HOME, Icons.Filled.Home); NavItem(Screen.FILES, Icons.Filled.Folder)
        Group("Tools"); NavItem(Screen.CLEANER, Icons.Filled.AutoAwesome); NavItem(Screen.ANALYTICS, Icons.Filled.Analytics); NavItem(Screen.ORGANIZER, Icons.Filled.AutoFixHigh)
        Group("Share & protect"); NavItem(Screen.TRANSFER, Icons.Filled.WifiTethering); NavItem(Screen.VAULT, Icons.Filled.Lock)
        Spacer(Modifier.weight(1f))
        val sys = File.listRoots().firstOrNull { it.totalSpace > 0 }
        if (sys != null) {
            val f = (sys.totalSpace - sys.usableSpace).toFloat() / sys.totalSpace
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { AppState.openFile = null; AppState.screen = Screen.ANALYTICS }.padding(14.dp)) {
                Row { Text("Storage ${sys.path}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)); Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = if (f > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
                Spacer(Modifier.height(6.dp))
                Text("${Formatters.size(sys.usableSpace)} free of ${Formatters.size(sys.totalSpace)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
        }
        NavItem(Screen.SETTINGS, Icons.Filled.Settings)
    }
}

@Composable private fun Group(t: String) =
    Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))

@Composable
private fun NavItem(s: Screen, icon: ImageVector) {
    val selected = AppState.screen == s && AppState.openFile == null
    val src = remember { MutableInteractionSource() }; val hover by src.collectIsHoveredAsState()
    val bg = when { selected -> MaterialTheme.colorScheme.primaryContainer; hover -> MaterialTheme.colorScheme.surfaceContainerHigh; else -> Color.Transparent }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp)).background(bg).hoverable(src)
        .clickable { AppState.openFile = null; AppState.screen = s }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Text(s.title, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

/** Top bar: where you are, global search (searches the current Files folder), theme switch. */
@Composable
fun TopBar(dark: Boolean) {
    var q by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Astrum Files", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  /  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Text(if (AppState.openFile != null) AppState.openFile!!.name else AppState.screen.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        Row(Modifier.width(420.dp).height(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search files…  (ext:pdf  size>100mb  type:image)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth().onKeyEvent {
                        if (it.key == Key.Enter && q.isNotBlank()) { AppState.pendingSearch = q; AppState.openFile = null; AppState.screen = Screen.FILES; q = ""; true } else false })
            }
        }
        Spacer(Modifier.width(10.dp))
        IconButton(onClick = { AppState.themeMode = if (dark) "light" else "dark"; AppState.save() }) {
            Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Switch theme", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
