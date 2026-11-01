package com.lyvt.files.feature.viewer

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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lyvt.files.core.archive.ArchiveEntryInfo
import com.lyvt.files.core.archive.ArchiveManager
import com.lyvt.files.core.archive.ArchivePasswordException
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.FileOperations
import com.lyvt.files.ui.Routes
import com.lyvt.files.ui.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ArchiveScreen(nav: NavHostController, path: String) {
    val archive = File(path)
    var entries by remember { mutableStateOf<List<ArchiveEntryInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var bytes by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, password) {
        withContext(Dispatchers.IO) {
            runCatching { ArchiveManager.list(archive, password?.toCharArray()) }
                .onSuccess { entries = it; error = null; if (it.any { e -> e.encrypted } && password == null) askPassword = true }
                .onFailure { error = it.message ?: "Cannot read archive" }
        }
    }

    fun extract(only: Set<String>?) {
        job = scope.launch {
            val dest = FileOperations.uniqueTarget(archive.parentFile!!, archive.name.substringBefore('.'))
            status = "Extracting…"; bytes = 0
            val r = withContext(Dispatchers.IO) {
                runCatching { ArchiveManager.extract(archive, dest, password?.toCharArray(), only) { n -> bytes += n } }
            }
            status = r.fold({ "Extracted $it file(s) to ${dest.name}" }, {
                if (it is ArchivePasswordException) { askPassword = true; it.message } else if (it is kotlinx.coroutines.CancellationException) "Cancelled" else "Failed: ${it.message}"
            })
            if (r.isFailure) dest.takeIf { it.exists() && (it.list()?.isEmpty() == true) }?.delete()
            job = null
            if (r.isSuccess) nav.navigate(Routes.files(dest.absolutePath))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(archive.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        Text("${Formatters.size(archive.length())}${entries?.let { " · ${it.size} entries" } ?: ""}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp)) {
                    Button(enabled = entries != null && job == null, onClick = { extract(null) }) { Text("Extract all") }
                    TextButton(enabled = selected.isNotEmpty() && job == null, onClick = { extract(selected) }) { Text("Extract ${selected.size}") }
                    TextButton(enabled = job == null, onClick = {
                        scope.launch {
                            status = "Testing integrity…"
                            status = ArchiveManager.test(archive, password?.toCharArray()).fold({ "OK — $it entries verified" }, { "Integrity check failed: ${it.message}" })
                        }
                    }) { Text("Test") }
                }
                status?.let {
                    Text(it + if (job != null) " ${Formatters.size(bytes)}" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                    if (job != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.weight(1f).padding(8.dp)); TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            }
        }
        items(entries.orEmpty(), key = { it.path }) { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(e.path in selected, { selected = if (it) selected + e.path else selected - e.path })
                Column(Modifier.weight(1f)) {
                    Text((if (e.isDirectory) "📁 " else "") + e.path + if (e.encrypted) " 🔒" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!e.isDirectory && e.size >= 0) Text(Formatters.size(e.size) + if (e.compressedSize > 0) " → ${Formatters.size(e.compressedSize)}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (askPassword) TextInputDialog("Archive password", password = true, label = "Password", onConfirm = { password = it }, onDismiss = { askPassword = false })
}
