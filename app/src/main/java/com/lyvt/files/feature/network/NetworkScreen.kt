package com.lyvt.files.feature.network

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.database.ConnectionDao
import com.lyvt.files.core.database.ConnectionEntity
import com.lyvt.files.core.database.TransferDao
import com.lyvt.files.core.database.TransferEntity
import com.lyvt.files.core.network.CredentialCipher
import com.lyvt.files.core.network.RemoteEntry
import com.lyvt.files.core.network.RemoteFactory
import com.lyvt.files.core.network.RemoteFs
import com.lyvt.files.core.network.UnknownHostKeyException
import com.lyvt.files.core.network.joinPath
import com.lyvt.files.core.network.parentPath
import com.lyvt.files.core.storage.FileOperations
import com.lyvt.files.ui.ConfirmDialog
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.IconBadge
import com.lyvt.files.ui.LoadingState
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.Pill
import com.lyvt.files.ui.TextInputDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class Session(val conn: ConnectionEntity, val path: String, val entries: List<RemoteEntry>?)
data class Progress(val label: String, val done: Long, val total: Long)

@HiltViewModel
class NetworkViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context, private val dao: ConnectionDao, private val transfers: TransferDao,
) : ViewModel() {
    val connections = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val session = MutableStateFlow<Session?>(null)
    val busy = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow<Progress?>(null)
    val hostKeyPrompt = MutableStateFlow<Pair<ConnectionEntity, String>?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var fs: RemoteFs? = null
    private val lock = Mutex()
    private var job: Job? = null

    fun save(c: ConnectionEntity, password: String?) = viewModelScope.launch(Dispatchers.IO) {
        dao.upsert(if (password != null) c.copy(secret = CredentialCipher.encrypt(password)) else c)
    }
    fun delete(c: ConnectionEntity) = viewModelScope.launch { dao.delete(c.id) }

    fun connect(c: ConnectionEntity, test: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        busy.value = "Connecting to ${c.host}…"
        runCatching {
            lock.withLock {
                fs?.close()
                val f = RemoteFactory.open(c)
                val start = c.path.ifBlank { "/" }
                val list = f.list(start)
                if (test) { f.close(); messages.emit("Connection OK — ${list.size} item(s) in $start") }
                else { fs = f; session.value = Session(c, start, sorted(list)) }
            }
        }.onFailure { e ->
            if (e is UnknownHostKeyException) hostKeyPrompt.value = c to e.fingerprint
            else messages.emit(RemoteFactory.describe(e))
        }
        busy.value = null
    }

    /** Pins the host key only after the user compared the fingerprint and approved it. */
    fun trustHostKey(c: ConnectionEntity, fp: String) = viewModelScope.launch(Dispatchers.IO) {
        val updated = c.copy(hostKey = fp); dao.upsert(updated); hostKeyPrompt.value = null; connect(updated)
    }

    private fun sorted(l: List<RemoteEntry>) = l.sortedWith(compareBy<RemoteEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })

    fun open(path: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = session.value ?: return@launch
        session.value = s.copy(path = path, entries = null)
        runCatching { lock.withLock { fs!!.list(path) } }
            .onSuccess { session.value = s.copy(path = path, entries = sorted(it)) }
            .onFailure { session.value = s.copy(path = path, entries = emptyList()); messages.emit(RemoteFactory.describe(it)) }
    }
    fun refresh() { session.value?.let { open(it.path) } }
    fun disconnect() { viewModelScope.launch(Dispatchers.IO) { lock.withLock { fs?.close(); fs = null } }; session.value = null }

    fun download(e: RemoteEntry) {
        job = viewModelScope.launch(Dispatchers.IO) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Astrum").apply { mkdirs() }
            val target = FileOperations.uniqueTarget(dir, e.name)
            val part = File(dir, target.name + FileOperations.PART_SUFFIX)
            var done = 0L
            val r = runCatching {
                lock.withLock { part.outputStream().use { out -> fs!!.download(e.path, out) { n -> done += n; progress.value = Progress("Downloading ${e.name}", done, e.size) } } }
                if (!part.renameTo(target)) throw java.io.IOException("Could not finalize file")
            }
            part.delete(); progress.value = null
            transfers.insert(TransferEntity(direction = "download", name = e.name, size = done, peer = session.value?.conn?.name ?: "", success = r.isSuccess))
            messages.emit(r.fold({ "Saved to Download/Astrum/${target.name}" }, { if (it is kotlinx.coroutines.CancellationException) "Download cancelled" else RemoteFactory.describe(it) }))
        }
    }

    fun upload(uris: List<Uri>) {
        val s = session.value ?: return
        job = viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            for (u in uris) {
                val (name, size) = meta(u)
                var done = 0L
                val r = runCatching {
                    lock.withLock { ctx.contentResolver.openInputStream(u)!!.use { input -> fs!!.upload(input, size, joinPath(s.path, name)) { n -> done += n; progress.value = Progress("Uploading $name", done, size) } } }
                }
                transfers.insert(TransferEntity(direction = "upload", name = name, size = done, peer = s.conn.name, success = r.isSuccess))
                if (r.isSuccess) ok++ else { messages.emit("$name: ${RemoteFactory.describe(r.exceptionOrNull()!!)}"); if (r.exceptionOrNull() is kotlinx.coroutines.CancellationException) break }
            }
            progress.value = null; messages.emit("Uploaded $ok of ${uris.size}"); refresh()
        }
    }

    private fun meta(u: Uri): Pair<String, Long> = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) (c.getString(0) ?: "file") to (if (c.isNull(1)) -1L else c.getLong(1)) else null
    } ?: ("file" to -1L)

    fun cancel() { job?.cancel(); progress.value = null }

    fun remote(block: RemoteFs.() -> Unit, okMsg: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { lock.withLock { fs!!.block() } }.onSuccess { messages.emit(okMsg); refresh() }.onFailure { messages.emit(RemoteFactory.describe(it)) }
    }

    override fun onCleared() { fs?.close() }
}

private val defaultPorts = mapOf("SFTP" to 22, "SMB" to 445, "WEBDAV" to 443, "FTP" to 21)

@Composable
fun NetworkScreen(nav: NavHostController, vm: NetworkViewModel = hiltViewModel()) {
    val conns by vm.connections.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val hostKey by vm.hostKeyPrompt.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<ConnectionEntity?>(null) }
    var menuFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var mkdir by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.upload(it) }
    val s = session
    BackHandler(s != null) { if (s!!.path != "/" && s.path != s.conn.path) vm.open(parentPath(s.path)) else vm.disconnect() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
        if (s == null) ExtendedFloatingActionButton(onClick = { edit = ConnectionEntity(name = "", protocol = "SFTP", host = "", port = 22, username = "", secret = "") },
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add server") })
        else ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }, icon = { Icon(Icons.Filled.CloudUpload, null) }, text = { Text("Upload") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (s != null) vm.disconnect() else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(s?.conn?.name ?: "Network", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                        Text(s?.path ?: "SFTP · SMB · WebDAV · FTP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    if (s != null) IconButton(onClick = { mkdir = true }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
                }
            }
            progress?.let { p -> item {
                LyvtCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.label} · ${Formatters.size(p.done)}${if (p.total > 0) " / ${Formatters.size(p.total)}" else ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                    if (p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } }
            busy?.let { item { LoadingState(it) } }
            if (s == null) {
                if (conns.isEmpty() && busy == null) item { EmptyState(Icons.Outlined.Dns, "No servers yet", "Add an SFTP, SMB (Windows/NAS share), WebDAV or FTP server. Passwords are encrypted with the Android Keystore.") }
                items(conns, key = { it.id }) { c ->
                    LyvtCard(padding = 14.dp, onClick = { vm.connect(c) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Filled.Dns, MaterialTheme.colorScheme.primary, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                                Text("${c.protocol.lowercase()}://${c.username.ifEmpty { "" }}${if (c.username.isNotEmpty()) "@" else ""}${c.host}:${c.port}${if (c.protocol == "SMB") "/" + c.share else ""}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.protocol == "FTP") Text("Unencrypted — use only on trusted networks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { edit = c }) { Icon(Icons.Filled.Edit, "Edit") }
                        }
                    }
                }
            } else {
                val entries = s.entries
                if (entries == null) item { LoadingState("Loading ${s.path}…") }
                else if (entries.isEmpty()) item { EmptyState(Icons.Filled.Folder, "Empty folder", "Upload files with the button below.") }
                else items(entries, key = { it.path }) { e ->
                    Row(Modifier.fillMaxWidth().clickable { if (e.isDirectory) vm.open(e.path) else vm.download(e) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(if (e.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, MaterialTheme.colorScheme.primary, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(listOfNotNull(if (!e.isDirectory) Formatters.size(e.size) else null, if (e.modified > 0) Formatters.shortDate(e.modified) else null).joinToString(" · ") +
                                if (!e.isDirectory) "  ·  tap to download" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { menuFor = e }) { Icon(Icons.Filled.MoreVert, null) }
                            DropdownMenu(menuFor == e, { menuFor = null }) {
                                if (!e.isDirectory) DropdownMenuItem(text = { Text("Download") }, onClick = { vm.download(e); menuFor = null })
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { renameFor = e; menuFor = null })
                                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { deleteFor = e; menuFor = null })
                            }
                        }
                    }
                }
            }
        }
    }
    edit?.let { ConnectionEditor(it, vm, onDismiss = { edit = null }) }
    hostKey?.let { (c, fp) ->
        AlertDialog(onDismissRequest = { vm.hostKeyPrompt.value = null }, title = { Text("Verify server identity") }, text = {
            Text("This is the first connection to ${c.host}. Compare this SSH host key fingerprint with the one shown on the server " +
                "(e.g. ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub -E md5):\n\n$fp\n\nIf it ever changes later, Astrum Files will refuse to connect.")
        }, confirmButton = { TextButton(onClick = { vm.trustHostKey(c, fp) }) { Text("Trust and connect") } },
            dismissButton = { TextButton(onClick = { vm.hostKeyPrompt.value = null }) { Text("Cancel") } })
    }
    renameFor?.let { e -> TextInputDialog("Rename", initial = e.name, label = "New name", validate = { FileOperations.validateName(it) },
        onConfirm = { n -> vm.remote({ rename(e.path, joinPath(parentPath(e.path), n)) }, "Renamed") }, onDismiss = { renameFor = null }) }
    deleteFor?.let { e -> ConfirmDialog("Delete ${e.name}?", "This deletes the item on the server. Remote deletion cannot be undone" +
        if (e.isDirectory) " (folders must be empty)." else ".", "Delete", destructive = true, onConfirm = { vm.remote({ delete(e) }, "Deleted") }, onDismiss = { deleteFor = null }) }
    if (mkdir) TextInputDialog("New folder", label = "Name", validate = { FileOperations.validateName(it) },
        onConfirm = { n -> vm.remote({ mkdir(joinPath(s!!.path, n)) }, "Folder created") }, onDismiss = { mkdir = false })
}

@Composable
private fun ConnectionEditor(initial: ConnectionEntity, vm: NetworkViewModel, onDismiss: () -> Unit) {
    var c by remember { mutableStateOf(initial) }
    var pw by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.id == 0L) "Add server" else "Edit server") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SFTP", "SMB", "WEBDAV", "FTP").forEach { p -> Pill(if (p == "WEBDAV") "WebDAV" else p, c.protocol == p) { c = c.copy(protocol = p, port = defaultPorts[p]!!, hostKey = "") } }
            }
            if (c.protocol == "FTP") Text("FTP sends passwords and files unencrypted. Prefer SFTP or WebDAV over HTTPS.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(c.name, { c = c.copy(name = it) }, label = { Text("Display name") }, singleLine = true)
            OutlinedTextField(c.host, { c = c.copy(host = it.trim()) }, label = { Text(if (c.protocol == "WEBDAV") "URL (https://host/remote.php/dav/…)" else "Host or IP") }, singleLine = true)
            OutlinedTextField(c.port.toString(), { c = c.copy(port = it.filter(Char::isDigit).toIntOrNull() ?: 0) }, label = { Text("Port") }, singleLine = true)
            if (c.protocol == "SMB") OutlinedTextField(c.share, { c = c.copy(share = it.trim()) }, label = { Text("Share name") }, singleLine = true)
            OutlinedTextField(c.username, { c = c.copy(username = it) }, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(pw ?: "", { pw = it }, label = { Text(if (initial.secret.isNotEmpty()) "Password (unchanged if empty)" else "Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(c.path, { c = c.copy(path = it.ifBlank { "/" }) }, label = { Text("Start folder") }, singleLine = true)
            Row {
                TextButton(enabled = c.host.isNotBlank(), onClick = { vm.save(c, pw?.takeIf { it.isNotEmpty() } ?: if (initial.secret.isEmpty()) "" else null); vm.connect(c.copy(secret = pw?.let { CredentialCipher.encrypt(it) } ?: c.secret), test = true) }) { Text("Test") }
                if (initial.id != 0L) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = { TextButton(enabled = c.host.isNotBlank() && c.name.isNotBlank(), onClick = { vm.save(c, pw?.takeIf { it.isNotEmpty() } ?: if (initial.secret.isEmpty()) "" else null); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (confirmDelete) ConfirmDialog("Remove ${c.name}?", "The saved connection and its encrypted password are removed. Files on the server are untouched.", "Remove", true,
        onConfirm = { vm.delete(initial); onDismiss() }, onDismiss = { confirmDelete = false })
}
