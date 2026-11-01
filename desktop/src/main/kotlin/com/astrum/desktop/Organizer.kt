package com.astrum.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.organizer.OrganizerEngine
import com.lyvt.files.core.organizer.OrganizerPlan
import com.lyvt.files.core.organizer.OrganizerRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Run(val name: String, val at: Long, val journal: List<Pair<String, String>>, val failed: Int, val undone: Boolean = false)

object Organizer {
    val rules = mutableStateListOf<OrganizerRule>()
    val runs = mutableStateListOf<Run>()
    fun load() {
        runCatching { JSONArray(Store.read("rules.json") ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.forEach { o ->
            rules.add(OrganizerRule(o.getString("name"), o.getString("src"), o.getString("dest"), o.optString("ext").split(',').filter { it.isNotBlank() }.toSet(),
                o.optString("re"), o.optLong("min"), 0, o.optInt("days"), o.optBoolean("rec"))) } } }
        runCatching { JSONArray(Store.read("organizer-runs.json") ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.forEach { o ->
            runs.add(Run(o.getString("name"), o.getLong("at"), OrganizerEngine.journalFromJson(o.getString("j")), o.optInt("failed"), o.optBoolean("undone"))) } } }
    }
    fun save() {
        Store.write("rules.json", JSONArray(rules.map { JSONObject().put("name", it.name).put("src", it.sourceDir).put("dest", it.destination).put("ext", it.extensions.joinToString(","))
            .put("re", it.namePattern).put("min", it.minSizeBytes).put("days", it.olderThanDays).put("rec", it.recursive) }).toString(2))
        Store.write("organizer-runs.json", JSONArray(runs.take(100).map { JSONObject().put("name", it.name).put("at", it.at).put("j", OrganizerEngine.journalToJson(it.journal)).put("failed", it.failed).put("undone", it.undone) }).toString())
    }
    fun recordRun(name: String, journal: List<Pair<String, String>>, failed: Int) { if (journal.isNotEmpty()) { runs.add(0, Run(name, System.currentTimeMillis(), journal, failed)); save() }; AppState.log("organize", "$name: ${journal.size} moved", failed == 0) }
}

@Composable
fun OrganizerScreen() {
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<Pair<Int, OrganizerRule>?>(null) }
    var preview by remember { mutableStateOf<OrganizerPlan?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val dl = AppState.knownFolder("Downloads").absolutePath
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Smart Organizer", style = MaterialTheme.typography.headlineMedium)
                Text("Rules move matching files into folders. Every run shows a preview first and can be undone.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }; Spacer(Modifier.width(12.dp))
            Button(onClick = { edit = -1 to OrganizerRule("", dl, "{category}") }) { Text("New rule") }
        } }
        if (Organizer.rules.isEmpty()) item { AstrumCard { SectionHeader("Start from a template")
            listOf(OrganizerRule("Installers → Downloads\\Installers", dl, "Installers", setOf("exe", "msi")), OrganizerRule("Archives → Downloads\\Archives", dl, "Archives", setOf("zip", "7z", "rar", "tar", "gz")),
                OrganizerRule("Downloads by category", dl, "{category}"), OrganizerRule("Screenshots by month", File(AppState.knownFolder("Pictures"), "Screenshots").absolutePath, "{yyyy}-{MM}", setOf("png", "jpg")),
                OrganizerRule("Code by language", dl, "Code/{lang}", setOf("kt", "java", "py", "js", "ts", "c", "cpp", "cs", "html", "css", "json", "xml", "md", "sh")))
                .forEach { t -> TextButton(onClick = { edit = -1 to t }) { Text(t.name, Modifier.fillMaxWidth()) } } } }
        items(Organizer.rules.size) { i -> val r = Organizer.rules[i]
            AstrumCard(padding = 14.dp) { Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.AutoFixHigh, Gold); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text("${r.sourceDir} → ${r.destination}${if (r.extensions.isNotEmpty()) " · " + r.extensions.joinToString(",") else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(onClick = { scope.launch { preview = withContext(Dispatchers.IO) { OrganizerEngine.plan(r) } } }) { Text("Preview & run") }; Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { edit = i to r }) { Text("Edit") }; TextButton(onClick = { Organizer.rules.removeAt(i); Organizer.save() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            } } }
        if (Organizer.runs.isNotEmpty()) { item { SectionHeader("History") }
            items(Organizer.runs.size) { i -> val run = Organizer.runs[i]; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${run.name} · ${run.journal.size} moved${if (run.failed > 0) ", ${run.failed} skipped" else ""} · ${Formatters.date(run.at)}${if (run.undone) " · undone" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (!run.undone) TextButton(onClick = { scope.launch { val (ok, f) = withContext(Dispatchers.IO) { OrganizerEngine.undo(run.journal) }; Organizer.runs[i] = run.copy(undone = true); Organizer.save(); msg = "Restored $ok" + if (f > 0) ", $f failed" else "" } }) { Text("Undo") }
            } } }
    }
    preview?.let { p -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("Preview: ${p.rule.name}") }, text = {
        Column(Modifier.width(520.dp).verticalScroll(rememberScrollState())) {
            p.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("${p.moves.size} file(s), ${Formatters.size(p.totalBytes)} will move. Nothing is overwritten.")
            p.moves.take(80).forEach { Text("${it.from.name}  →  ${it.to.parentFile.absolutePath.removePrefix(p.rule.sourceDir)}", style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(enabled = p.moves.isNotEmpty(), onClick = { preview = null; scope.launch { val r = withContext(Dispatchers.IO) { OrganizerEngine.execute(p) }
            Organizer.recordRun(p.rule.name, r.journal, r.failed.size); msg = "Moved ${r.journal.size} file(s)" } }) { Text("Move ${p.moves.size} files") } },
        dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } }) }
    edit?.let { (idx, r0) -> var r by remember(idx) { mutableStateOf(r0) }; var ext by remember(idx) { mutableStateOf(r0.extensions.joinToString(",")) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (idx < 0) "New rule" else "Edit rule") }, text = { Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(r.sourceDir, { r = r.copy(sourceDir = it) }, label = { Text("Source folder") }, singleLine = true, modifier = Modifier.weight(1f), isError = !File(r.sourceDir).isDirectory)
                TextButton(onClick = { pickFolder()?.let { r = r.copy(sourceDir = it.absolutePath) } }) { Text("Browse") } }
            OutlinedTextField(r.destination, { r = r.copy(destination = it) }, label = { Text("Destination ({ext} {category} {yyyy} {MM} {lang})") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ext, { ext = it }, label = { Text("Extensions (empty = all)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(r.namePattern, { r = r.copy(namePattern = it) }, label = { Text("Filename regex (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(if (r.olderThanDays > 0) r.olderThanDays.toString() else "", { r = r.copy(olderThanDays = it.filter(Char::isDigit).toIntOrNull() ?: 0) }, label = { Text("Only files older than (days)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Include subfolders", Modifier.weight(1f)); Switch(r.recursive, { r = r.copy(recursive = it) }) }
        } }, confirmButton = { TextButton(enabled = r.name.isNotBlank() && File(r.sourceDir).isDirectory && r.destination.isNotBlank(), onClick = {
            val rule = r.copy(extensions = ext.split(',').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet())
            if (idx < 0) Organizer.rules.add(rule) else Organizer.rules[idx] = rule; Organizer.save(); edit = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } }) }
}
