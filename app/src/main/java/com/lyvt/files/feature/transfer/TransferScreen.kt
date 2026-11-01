package com.lyvt.files.feature.transfer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.database.TransferDao
import com.lyvt.files.core.database.TransferEntity
import com.lyvt.files.core.network.SharedItem
import com.lyvt.files.core.network.TransferServer
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(@ApplicationContext private val ctx: Context, private val dao: TransferDao) : ViewModel() {
    val history = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val shared = MutableStateFlow<List<SharedItem>>(emptyList())
    val server = MutableStateFlow<TransferServer?>(null)
    val error = MutableStateFlow<String?>(null)

    fun add(uris: List<Uri>) { shared.value = (shared.value + uris.map { TransferServer.itemFor(ctx, it) }).distinctBy { it.uri } }
    fun clear() { shared.value = emptyList() }

    fun start(minutes: Int = 15) {
        if (TransferServer.lanAddress() == null) { error.value = "Connect to Wi-Fi (or enable hotspot) first — the PC/phone must be on the same network."; return }
        stop()
        runCatching {
            TransferServer(ctx, shared.value, minutes * 60_000L) { e ->
                viewModelScope.launch { dao.insert(TransferEntity(direction = if (e.incoming) "received" else "sent", name = e.name, size = e.size, peer = e.peer, success = e.ok)) }
            }.also { it.start() }
        }.onSuccess { server.value = it; error.value = null }.onFailure { error.value = "Could not start: ${it.message}" }
    }
    fun stop() { server.value?.stop(); server.value = null }
    override fun onCleared() { stop() }
}

private fun qr(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply { for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (m[x, y]) 0xFF101018.toInt() else -1) }
}

@Composable
fun TransferScreen(nav: NavHostController, vm: TransferViewModel = hiltViewModel()) {
    val shared by vm.shared.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }
    val s = server
    val incoming by (s?.incoming ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    val live by (s?.live ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    var remaining by remember { mutableLongStateOf(0L) }
    LaunchedEffect(s) { while (s != null) { remaining = (s.expiresAt - System.currentTimeMillis()) / 1000; if (remaining <= 0) { vm.stop(); break }; delay(1000) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.stop(); nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column { Text("Astrum Transfer", style = MaterialTheme.typography.headlineSmall)
                    Text("Phone ↔ PC or phone ↔ phone over your local network", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            LyvtCard {
                SectionHeader("Files to send (${shared.size})")
                shared.forEach { Text("• ${it.name}  ${if (it.size >= 0) Formatters.size(it.size) else ""}", style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = s == null) { Text("Add files") }
                    if (shared.isNotEmpty() && s == null) TextButton(onClick = vm::clear) { Text("Clear") }
                }
                Text("Receiving needs no selection — the other device can send files to this phone, and you approve each one.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            if (s == null) {
                Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text("Start session (15 min)") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            } else LyvtCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val bmp = remember(s.url) { qr(s.url) }
                    Image(bmp.asImageBitmap(), "QR code for ${s.url}", Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Scan with the other device's camera, or open in a browser:", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(s.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    Text("Expires in ${remaining / 60}:${"%02d".format(remaining % 60)} · not encrypted (local HTTP) — use trusted networks only",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    live?.let { l ->
                        Text("${if (l.incoming) "Receiving" else "Sending"} ${l.name} · ${Formatters.size(l.done)}${if (l.total > 0) " / ${Formatters.size(l.total)}" else ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        if (l.total > 0) LinearProgressIndicator(progress = { (l.done.toFloat() / l.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    Button(onClick = vm::stop, modifier = Modifier.padding(top = 10.dp)) { Text("End session") }
                }
            }
        }
        item { SectionHeader("History") }
        if (history.isEmpty()) item { EmptyState(Icons.Outlined.WifiTethering, "No transfers yet", "Received files are saved to Download/Astrum Received.") }
        items(history, key = { it.id }) { t: TransferEntity ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(if (t.success) "✓" else "✗", color = if (t.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 10.dp))
                Column {
                    Text("${t.direction.replaceFirstChar(Char::uppercase)} · ${t.name}", style = MaterialTheme.typography.bodyMedium)
                    Text("${Formatters.size(t.size)} · ${t.peer} · ${Formatters.date(t.at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    incoming?.let { r ->
        AlertDialog(onDismissRequest = {}, title = { Text("Incoming file") }, text = {
            Text("${r.from} wants to send:\n\n${r.name}\n${Formatters.size(r.size)}\n\nIt will be saved to Download/Astrum Received.")
        }, confirmButton = { TextButton(onClick = { r.answer.complete(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { r.answer.complete(false) }) { Text("Decline") } })
    }
}
