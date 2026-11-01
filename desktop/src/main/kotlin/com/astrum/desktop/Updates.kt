package com.astrum.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.update.UpdateCheck
import com.lyvt.files.core.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

/** Automatic updates for the PC app from GitHub Releases (tag `desktop-v<version>`, asset `*Setup*.exe`). */
object Updates {
    const val VERSION = "1.3.0"
    private val windows = System.getProperty("os.name").startsWith("Windows")
    var available by mutableStateOf<UpdateInfo?>(null)
    var prompt by mutableStateOf<UpdateInfo?>(null)
    var status by mutableStateOf<String?>(null)
    var progress by mutableStateOf<Float?>(null)
    var checking by mutableStateOf(false)
    private val prefs get() = runCatching { JSONObject(Store.read("updates.json") ?: "{}") }.getOrDefault(JSONObject())
    var auto: Boolean
        get() = prefs.optBoolean("auto", true)
        set(v) = Store.write("updates.json", prefs.put("auto", v).toString())

    fun checkIfDue() { if (auto && System.currentTimeMillis() - prefs.optLong("last") > 24 * 3600_000L) check(auto = true) }

    fun check(auto: Boolean = false) {
        if (checking) return
        checking = true; if (!auto) status = "Checking…"
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { UpdateCheck.latest("desktop-v", ".exe", VERSION) }
                .onSuccess { u -> Store.write("updates.json", prefs.put("last", System.currentTimeMillis()).toString())
                    available = u; status = if (u == null) "You have the latest version ($VERSION)." else "Version ${u.version} is available."
                    if (u != null && auto && prefs.optString("skipped") != u.version) prompt = u }
                .onFailure { if (!auto) status = "Could not check: ${it.message}" }
            checking = false
        }
    }

    fun skip(u: UpdateInfo) { Store.write("updates.json", prefs.put("skipped", u.version).toString()); prompt = null }

    /** Downloads the installer, verifies it, starts it and closes the app so files can be replaced. */
    fun install(u: UpdateInfo) {
        prompt = null
        if (!windows) { runCatching { java.awt.Desktop.getDesktop().browse(URI(u.page)) }; return }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val f = UpdateCheck.download(u, File(System.getProperty("java.io.tmpdir"), "AstrumFiles-Setup-${u.version}.exe")) { d, t ->
                    progress = if (t > 0) d.toFloat() / t else null; status = "Downloading ${Formatters.size(d)}${if (t > 0) " / " + Formatters.size(t) else ""}" }
                status = "Starting installer…"
                ProcessBuilder(f.absolutePath).start()
                Transfer.stop(); Vault.lock(); AppState.save()
                exitProcess(0)
            }.onFailure { status = "Update failed: ${it.message}"; progress = null }
        }
    }
}

@Composable
fun UpdatePrompt() {
    val u = Updates.prompt ?: return
    AlertDialog(onDismissRequest = { Updates.prompt = null }, title = { Text("Update available: ${u.version}") },
        text = { Column(Modifier.width(460.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("A new version of Astrum Files is ready${if (u.size > 0) " (${Formatters.size(u.size)})" else ""}. The app closes, the installer updates it, and your settings and vault are kept.")
            if (u.notes.isNotBlank()) Text("\n" + u.notes.take(2000), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = { Updates.install(u) }) { Text("Update now") } },
        dismissButton = { Row { TextButton(onClick = { Updates.skip(u) }) { Text("Skip this version") }; TextButton(onClick = { Updates.prompt = null }) { Text("Later") } } })
}

@Composable
fun UpdatesCard() = AstrumCard {
    SectionHeader("Updates")
    var auto by androidx.compose.runtime.remember { mutableStateOf(Updates.auto) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Check for updates automatically"); Text("Once a day from GitHub (Lyvt-Dev/Astrum-Files). Installing always asks first.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(auto, { auto = it; Updates.auto = it })
    }
    Updates.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Updates.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    Row { TextButton(onClick = { Updates.check() }) { Text("Check now") }
        Updates.available?.let { u -> Button(onClick = { Updates.install(u) }) { Text("Install ${u.version}") } } }
}
