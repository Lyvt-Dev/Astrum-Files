package com.lyvt.files.feature.organizer

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.database.OrganizerRuleEntity
import com.lyvt.files.core.database.OrganizerRunEntity
import com.lyvt.files.core.organizer.OrganizerEngine
import com.lyvt.files.core.organizer.OrganizerPlan
import com.lyvt.files.core.organizer.OrganizerRepository
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.IconBadge
import com.lyvt.files.ui.LoadingState
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class OrganizerViewModel @Inject constructor(private val repo: OrganizerRepository) : ViewModel() {
    val rules = repo.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val runs = repo.runs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preview = MutableStateFlow<Pair<OrganizerRuleEntity, OrganizerPlan>?>(null)
    val busy = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun save(r: OrganizerRuleEntity) = viewModelScope.launch { repo.save(r) }
    fun delete(r: OrganizerRuleEntity) = viewModelScope.launch { repo.delete(r.id) }
    fun dryRun(r: OrganizerRuleEntity) = viewModelScope.launch { busy.value = "Calculating preview…"; preview.value = r to repo.plan(r); busy.value = null }
    fun approve() = viewModelScope.launch {
        val (r, plan) = preview.value ?: return@launch
        preview.value = null
        busy.value = "Moving 0 / ${plan.moves.size}"
        val res = repo.execute(r, plan) { busy.value = "Moving $it / ${plan.moves.size}" }
        busy.value = null
        messages.emit("Moved ${res.journal.size} file(s)" + if (res.failed.isNotEmpty()) ", ${res.failed.size} skipped (${res.failed.first().second})" else "" + " — undo available below")
    }
    fun undo(run: OrganizerRunEntity) = viewModelScope.launch { val (ok, fail) = repo.undo(run); messages.emit("Restored $ok file(s)" + if (fail > 0) ", $fail could not be restored" else "") }
}

@Composable
fun OrganizerScreen(nav: NavHostController, vm: OrganizerViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<OrganizerRuleEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val root = Environment.getExternalStorageDirectory().absolutePath

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { editing = OrganizerRuleEntity(name = "", sourceDir = "$root/Download", destination = "{category}") },
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New rule") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column {
                        Text("Smart Organizer", style = MaterialTheme.typography.headlineSmall)
                        Text("Every run shows a preview first and can be undone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            busy?.let { item { LoadingState(it) } }
            if (rules.isEmpty()) item {
                LyvtCard {
                    SectionHeader("Start from a template")
                    OrganizerEngine.templates(root).forEach { t ->
                        TextButton(onClick = { editing = OrganizerRuleEntity(name = t.name, sourceDir = t.sourceDir, destination = t.destination, extensions = t.extensions.joinToString(",")) }) {
                            Text(t.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            items(rules, key = { it.id }) { r ->
                LyvtCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Filled.AutoFixHigh, MaterialTheme.colorScheme.primary, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Text("${r.sourceDir.removePrefix(root).ifEmpty { "/" }} → ${r.destination}" + if (r.extensions.isNotBlank()) " · ${r.extensions}" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (r.automatic) Text("Runs automatically once a day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        IconButton(onClick = { editing = r }) { Icon(Icons.Filled.Edit, "Edit") }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.dryRun(r) }, enabled = busy == null, shape = RoundedCornerShape(14.dp)) { Text("Preview & run") }
                        OutlinedButton(onClick = { vm.delete(r) }, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Filled.Delete, "Delete rule") }
                    }
                }
            }
            if (runs.isNotEmpty()) {
                item { SectionHeader("History") }
                items(runs, key = { "run${it.id}" }) { run ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${run.ruleName} · ${run.moved} moved" + if (run.failed > 0) ", ${run.failed} skipped" else "", style = MaterialTheme.typography.bodyMedium)
                            Text(Formatters.date(run.timestamp) + if (run.undone) " · undone" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!run.undone && run.moved > 0) TextButton(onClick = { vm.undo(run) }) { Text("Undo") }
                    }
                }
            }
            if (rules.isEmpty() && runs.isEmpty()) item { EmptyState(Icons.Outlined.AutoFixHigh, "No rules yet", "Rules move matching files into folders you choose. Nothing runs without your approval.") }
        }
    }
    editing?.let { RuleEditor(it, onSave = { r -> vm.save(r); editing = null }, onDismiss = { editing = null }) }
    preview?.let { (r, plan) ->
        AlertDialog(onDismissRequest = { vm.preview.value = null }, title = { Text("Preview: ${r.name}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                plan.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("${plan.moves.size} file(s), ${Formatters.size(plan.totalBytes)} will be moved. Nothing is overwritten.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                plan.moves.take(60).forEach { m ->
                    Text("${m.from.name}\n   → ${m.to.parentFile?.absolutePath?.removePrefix(root)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
                if (plan.moves.size > 60) Text("… and ${plan.moves.size - 60} more", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = plan.moves.isNotEmpty(), onClick = vm::approve) { Text("Move ${plan.moves.size} files") } },
            dismissButton = { TextButton(onClick = { vm.preview.value = null }) { Text("Cancel") } })
    }
}

@Composable
private fun RuleEditor(initial: OrganizerRuleEntity, onSave: (OrganizerRuleEntity) -> Unit, onDismiss: () -> Unit) {
    var r by remember { mutableStateOf(initial) }
    val regexOk = r.namePattern.isBlank() || runCatching { Regex(r.namePattern) }.isSuccess
    val srcOk = File(r.sourceDir).isDirectory
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.id == 0L) "New rule" else "Edit rule") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(r.sourceDir, { r = r.copy(sourceDir = it) }, label = { Text("Source folder") }, isError = !srcOk,
                supportingText = { if (!srcOk) Text("Folder does not exist") })
            OutlinedTextField(r.destination, { r = r.copy(destination = it) }, label = { Text("Destination") },
                supportingText = { Text("Relative to source, or absolute. Tokens: {ext} {category} {yyyy} {MM} {lang}") })
            OutlinedTextField(r.extensions, { r = r.copy(extensions = it) }, label = { Text("Extensions (comma separated, empty = all)") }, singleLine = true)
            OutlinedTextField(r.namePattern, { r = r.copy(namePattern = it) }, label = { Text("Filename regex (optional)") }, singleLine = true, isError = !regexOk)
            Row {
                OutlinedTextField(if (r.minSizeKb > 0) r.minSizeKb.toString() else "", { r = r.copy(minSizeKb = it.filter(Char::isDigit).toLongOrNull() ?: 0) },
                    label = { Text("Min KB") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(if (r.olderThanDays > 0) r.olderThanDays.toString() else "", { r = r.copy(olderThanDays = it.filter(Char::isDigit).toIntOrNull() ?: 0) },
                    label = { Text("Older than days") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Include subfolders", Modifier.weight(1f)); Switch(r.recursive, { r = r.copy(recursive = it) }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Run automatically")
                    Text("Daily, while battery is not low. Every run is listed in history and can be undone.", style = MaterialTheme.typography.labelSmall)
                }
                Switch(r.automatic, { r = r.copy(automatic = it) })
            }
        }
    }, confirmButton = { TextButton(enabled = r.name.isNotBlank() && srcOk && regexOk && r.destination.isNotBlank(), onClick = { onSave(r) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
