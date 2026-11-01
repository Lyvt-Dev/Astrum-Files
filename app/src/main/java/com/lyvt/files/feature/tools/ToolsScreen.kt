package com.lyvt.files.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lyvt.files.ui.Accents
import com.lyvt.files.ui.IconBadge
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.Routes

private data class ToolEntry(val title: String, val desc: String, val icon: ImageVector, val color: androidx.compose.ui.graphics.Color, val route: String)

@Composable
fun ToolsScreen(nav: NavHostController) {
    val tools = listOf(
        ToolEntry("Astrum Transfer", "Send and receive files with a PC or phone via QR code", Icons.Filled.WifiTethering, Accents[0], Routes.TRANSFER),
        ToolEntry("Network", "SFTP, SMB / NAS, WebDAV and FTP servers", Icons.Filled.Dns, Accents[1], Routes.NETWORK),
        ToolEntry("Smart Organizer", "Rules that tidy folders, with preview and undo", Icons.Filled.AutoFixHigh, Accents[4], Routes.ORGANIZER),
        ToolEntry("Collections & notes", "Collections, tags and personal file notes", Icons.Filled.CollectionsBookmark, Accents[2], Routes.LIBRARY),
        ToolEntry("Storage timeline", "Recorded storage history and growth", Icons.Filled.Timeline, Accents[5], Routes.TIMELINE),
        ToolEntry("Trash", "Restore or permanently remove deleted files", Icons.Filled.Delete, Accents[3], Routes.TRASH),
        ToolEntry("Operation history", "Every copy, move, rename and delete", Icons.Filled.History, Accents[1], Routes.HISTORY),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Tools", style = MaterialTheme.typography.headlineSmall)
            }
        }
        items(tools) { t ->
            LyvtCard(padding = 14.dp, onClick = { nav.navigate(t.route) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(t.icon, t.color); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text(t.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
