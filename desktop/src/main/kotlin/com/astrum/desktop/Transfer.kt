package com.astrum.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.FileOperations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

data class Incoming(val name: String, val size: Long, val from: String, val answer: CompletableDeferred<Boolean>)
data class TransferRecord(val direction: String, val name: String, val size: Long, val peer: String, val ok: Boolean, val at: Long = System.currentTimeMillis())

/**
 * Desktop side of Astrum Transfer — same protocol and security model as the Android app:
 * random 128-bit token in the URL/QR, 15 minute expiry, per-file approval for uploads, never overwrites, HTTP Range for resume.
 * Plain HTTP on the LAN (not encrypted) — documented and shown in the UI.
 */
class DesktopTransferServer(private val shared: List<File>, ttlMs: Long) {
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val expiresAt = System.currentTimeMillis() + ttlMs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(0, 16, lanAddress())
    private val failures = HashMap<String, Int>()
    val url get() = "http://${server.inetAddress.hostAddress}:${server.localPort}/$token/"
    val receiveDir = File(AppState.knownFolder("Downloads"), "Astrum Received").apply { mkdirs() }

    fun start() {
        scope.launch { while (isActive && !server.isClosed) { val s = runCatching { server.accept() }.getOrNull() ?: break
            if (System.currentTimeMillis() > expiresAt) { s.close(); stop(); break }; launch { runCatching { s.use { handle(it) } } } } }
        scope.launch { delay(expiresAt - System.currentTimeMillis()); stop() }
    }
    fun stop() { runCatching { server.close() }; Transfer.incoming?.answer?.complete(false); scope.cancel() }

    private fun line(i: InputStream): String { val sb = StringBuilder(); while (true) { val c = i.read(); if (c < 0 || c == 10) break; if (c != 13) sb.append(c.toChar()); if (sb.length > 8192) break }; return sb.toString() }
    private fun respond(o: OutputStream, code: Int, type: String, body: ByteArray) {
        o.write("HTTP/1.1 $code ${if (code < 300) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray()); o.write(body); o.flush()
    }

    private suspend fun handle(s: Socket) {
        s.soTimeout = 30_000
        val peer = s.inetAddress.hostAddress ?: "?"
        if ((failures[peer] ?: 0) >= 5) return
        val i = BufferedInputStream(s.getInputStream()); val o = s.getOutputStream()
        val (m, t) = line(i).split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val h = HashMap<String, String>(); while (true) { val l = line(i); if (l.isEmpty()) break; l.split(':', limit = 2).takeIf { it.size == 2 }?.let { h[it[0].trim().lowercase()] = it[1].trim() } }
        val path = t.substringBefore('?'); val q = t.substringAfter('?', "")
        if (m == "GET" && path == "/knock") return knock(q, o, peer)
        if (!path.startsWith("/$token/")) { failures[peer] = (failures[peer] ?: 0) + 1; respond(o, 404, "text/plain", "Not found".toByteArray()); return }
        val rest = path.removePrefix("/$token/")
        when {
            m == "GET" && rest.isEmpty() -> respond(o, 200, "text/html; charset=utf-8", page().toByteArray())
            m == "GET" && rest == "list.json" -> respond(o, 200, "application/json", JSONArray(shared.mapIndexed { idx, f -> JSONObject().put("i", idx).put("name", f.name).put("size", f.length()) }).toString().toByteArray())
            m == "GET" && rest.startsWith("f/") -> serve(rest.removePrefix("f/").toIntOrNull(), h["range"], o, peer)
            m == "POST" && rest == "upload" -> receive(q, h, i, o, peer)
            else -> respond(o, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private suspend fun knock(q: String, o: OutputStream, peer: String) {
        if (Transfer.incoming != null) return respond(o, 429, "text/plain", "Busy".toByteArray())
        val name = URLDecoder.decode(q.split('&').firstOrNull { it.startsWith("name=") }?.removePrefix("name=") ?: "", "UTF-8").take(40).ifBlank { "Unknown device" }
        val req = Incoming(name, -1, peer, CompletableDeferred()); Transfer.incoming = req
        val ok = withTimeoutOrNull(120_000) { req.answer.await() } ?: false
        Transfer.incoming = null
        if (ok) respond(o, 200, "text/plain", url.toByteArray()) else { failures[peer] = (failures[peer] ?: 0) + 1; respond(o, 403, "text/plain", "Declined".toByteArray()) }
    }
    val port get() = server.localPort

    private fun serve(idx: Int?, range: String?, o: OutputStream, peer: String) {
        val f = idx?.let { shared.getOrNull(it) } ?: return respond(o, 404, "text/plain", "Not found".toByteArray())
        val start = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        o.write(("HTTP/1.1 ${if (start > 0) 206 else 200} OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${f.length() - start}\r\nAccept-Ranges: bytes\r\n" +
            (if (start > 0) "Content-Range: bytes $start-${f.length() - 1}/${f.length()}\r\n" else "") +
            "Content-Disposition: attachment; filename*=UTF-8''${java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")}\r\nConnection: close\r\n\r\n").toByteArray())
        var done = 0L; var ok = false
        try { f.inputStream().use { inp -> inp.skip(start); val b = ByteArray(65536); while (true) { val n = inp.read(b); if (n < 0) break; o.write(b, 0, n); done += n; Transfer.live = Triple(f.name, done + start, f.length()) } }; o.flush(); ok = true }
        finally { Transfer.live = null; Transfer.history.add(0, TransferRecord("sent", f.name, done, peer, ok)); Transfer.saveHistory() }
    }

    private suspend fun receive(q: String, h: Map<String, String>, i: InputStream, o: OutputStream, peer: String) {
        val name = URLDecoder.decode(q.split('&').firstOrNull { it.startsWith("name=") }?.removePrefix("name=") ?: "", "UTF-8").substringAfterLast('/').substringAfterLast('\\').ifBlank { "upload.bin" }
        val size = h["content-length"]?.toLongOrNull() ?: return respond(o, 411, "text/plain", "Length required".toByteArray())
        if (FileOperations.validateName(name) != null) return respond(o, 400, "text/plain", "Invalid file name".toByteArray())
        if (receiveDir.usableSpace < size) return respond(o, 507, "text/plain", "Not enough space on the PC".toByteArray())
        val req = Incoming(name, size, peer, CompletableDeferred()); Transfer.incoming = req
        val approved = withTimeoutOrNull(120_000) { req.answer.await() } ?: false
        Transfer.incoming = null
        if (!approved) return respond(o, 403, "text/plain", "Declined on the PC".toByteArray())
        val target = FileOperations.uniqueTarget(receiveDir, name); val part = File(receiveDir, target.name + FileOperations.PART_SUFFIX); var done = 0L
        try {
            part.outputStream().use { out -> val b = ByteArray(65536); while (done < size) { val n = i.read(b, 0, minOf(b.size.toLong(), size - done).toInt()); if (n < 0) break; out.write(b, 0, n); done += n; Transfer.live = Triple(name, done, size) } }
            if (done != size || !part.renameTo(target)) error("Transfer interrupted")
            respond(o, 200, "text/plain", "Saved ${target.name}".toByteArray()); Transfer.history.add(0, TransferRecord("received", target.name, size, peer, true))
        } catch (e: Exception) { part.delete(); Transfer.history.add(0, TransferRecord("received", name, done, peer, false)); runCatching { respond(o, 500, "text/plain", (e.message ?: "error").toByteArray()) } }
        finally { Transfer.live = null; Transfer.saveHistory() }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun page() = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Astrum Transfer</title>
<style>:root{color-scheme:light dark;--p:#7548F8}body{font-family:system-ui,sans-serif;max-width:720px;margin:0 auto;padding:24px}h1 span{color:var(--p)}.card{border:1px solid #8884;border-radius:18px;padding:16px;margin:14px 0}
a.f{display:flex;justify-content:space-between;padding:10px 4px;border-bottom:1px solid #8882;color:inherit;text-decoration:none}label.b{background:var(--p);color:#fff;border-radius:12px;padding:10px 16px;display:inline-block;cursor:pointer}progress{width:100%}small{opacity:.7}</style></head><body>
<h1>✦ <span>Astrum</span> Transfer · PC</h1><small>Session expires automatically · files you send must be accepted on the PC</small>
<div class="card"><b>Files from the PC</b>${if (shared.isEmpty()) "<p><small>Nothing shared.</small></p>" else shared.mapIndexed { i, f -> "<a class=f href=\"f/$i\" download><span>${esc(f.name)}</span><small>${Formatters.size(f.length())}</small></a>" }.joinToString("")}</div>
<div class="card"><b>Send to the PC</b><p><label class="b">Choose files<input id="i" type="file" multiple hidden></label></p><progress id="pr" value="0" max="1" hidden></progress><div id="log"></div></div>
<script>const i=document.getElementById('i'),log=document.getElementById('log'),pr=document.getElementById('pr');
i.onchange=async()=>{for(const f of i.files){const d=document.createElement('div');d.textContent='⏳ '+f.name+' — waiting for approval on the PC…';log.prepend(d);
await new Promise(r=>{const x=new XMLHttpRequest();x.open('POST','upload?name='+encodeURIComponent(f.name));pr.hidden=false;x.upload.onprogress=e=>{pr.value=e.loaded/e.total};
x.onload=()=>{d.textContent=(x.status==200?'✅ ':'❌ ')+f.name+' — '+x.responseText;r()};x.onerror=()=>{d.textContent='❌ '+f.name+' — connection lost';r()};x.send(f)});}pr.hidden=true;i.value='';};</script></body></html>"""

    companion object {
        fun lanAddress(): java.net.InetAddress? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { it.displayName.contains("VirtualBox", true) || it.displayName.contains("VMware", true) || it.displayName.contains("Hyper-V", true) || it.displayName.contains("WSL", true) }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }
}

object Transfer {
    val deviceName: String = (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC").take(40)
    val nearby = com.lyvt.files.core.network.NearbyDiscovery(deviceName, "pc")
    var connecting by mutableStateOf<String?>(null)
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    fun connect(p: com.lyvt.files.core.network.NearbyPeer) {
        connecting = p.name; error = null
        CoroutineScope(Dispatchers.IO).launch {
            com.lyvt.files.core.network.NearbyDiscovery.requestAccess(p, deviceName)
                .onSuccess { runCatching { Desktop.getDesktop().browse(URI(it)) } }.onFailure { error = it.message }
            connecting = null
        }
    }
    /** Opens the Windows Bluetooth file transfer wizard (fsquirt) — works with any phone, incl. Samsung. */
    fun bluetooth(receive: Boolean) {
        if (!isWindows) { error = "Bluetooth file transfer uses the Windows wizard and is only available on Windows."; return }
        runCatching { ProcessBuilder("fsquirt.exe", if (receive) "-receive" else "-send").start() }.onFailure { error = "Could not open Bluetooth transfer: ${it.message}" }
    }
    val queue = mutableStateListOf<File>()
    var server by mutableStateOf<DesktopTransferServer?>(null)
    var incoming by mutableStateOf<Incoming?>(null)
    var live by mutableStateOf<Triple<String, Long, Long>?>(null)
    val history = mutableStateListOf<TransferRecord>()
    var error by mutableStateOf<String?>(null)

    fun share(files: List<File>) { files.filter { it.isFile }.forEach { if (it !in queue) queue.add(it) }
        files.filter { it.isDirectory }.forEach { d -> d.walkTopDown().filter { it.isFile }.take(2000).forEach { if (it !in queue) queue.add(it) } } }
    fun start() {
        if (DesktopTransferServer.lanAddress() == null) { error = "No local network found. Connect the PC to the same Wi-Fi/LAN as your phone."; return }
        stop(); runCatching { DesktopTransferServer(queue.toList(), 15 * 60_000L).also { it.start() } }.onSuccess { server = it; error = null; nearby.advertisePort = it.port }.onFailure { error = "Could not start: ${it.message}" }
    }
    fun stop() { nearby.advertisePort = null; server?.stop(); server = null }
    fun load() = runCatching { JSONArray(Store.read("transfers.json") ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.forEach { o ->
        history.add(TransferRecord(o.getString("d"), o.getString("n"), o.getLong("s"), o.getString("p"), o.getBoolean("ok"), o.getLong("at"))) } } }
    fun saveHistory() = Store.write("transfers.json", JSONArray(history.take(200).map { JSONObject().put("d", it.direction).put("n", it.name).put("s", it.size).put("p", it.peer).put("ok", it.ok).put("at", it.at) }).toString())
}

private fun qr(text: String, size: Int = 520) = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB).also { img ->
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size); for (x in 0 until size) for (y in 0 until size) img.setRGB(x, y, if (m[x, y]) 0x101018 else 0xFFFFFF) }.toComposeImageBitmap()

@Composable
fun TransferScreen() {
    val s = Transfer.server
    var remaining by remember { mutableLongStateOf(0L) }
    var phoneUrl by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { Transfer.nearby.start() }
    LaunchedEffect(s) { while (s != null && Transfer.server == s) { remaining = (s.expiresAt - System.currentTimeMillis()) / 1000; if (remaining <= 0) { Transfer.stop(); break }; delay(1000) } }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Astrum Transfer", style = MaterialTheme.typography.headlineMedium)
            Text("Move files between this PC and your phone over your local network.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AstrumCard {
                SectionHeader("PC → phone and phone → PC") {
                    if (Transfer.queue.isNotEmpty() && s == null) TextButton(onClick = { Transfer.queue.clear() }) { Text("Clear") } }
                Text("${Transfer.queue.size} file(s) to offer · ${Formatters.size(Transfer.queue.sumOf { it.length() })}", style = MaterialTheme.typography.bodyMedium)
                Transfer.queue.take(8).forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodySmall) }
                if (Transfer.queue.size > 8) Text("… and ${Transfer.queue.size - 8} more", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = s == null, onClick = { pickFiles()?.let { Transfer.share(it) } }) { Text("Add files…") }
                    OutlinedButton(enabled = s == null, onClick = { pickFolder()?.let { Transfer.share(listOf(it)) } }) { Text("Add folder…") }
                }
                Text("Tip: right-click files in Files → “Send to phone”. Received files go to Downloads\\Astrum Received.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (s == null) {
                Button(onClick = Transfer::start, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Start session (15 min)") }
                Transfer.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            QuickShareCard()
            AstrumCard {
                SectionHeader("Open a session started on your phone")
                Text("On the phone: Tools → Astrum Transfer → Start session, then type the address shown under the QR code here.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(phoneUrl, { phoneUrl = it.trim() }, placeholder = { Text("http://192.168.x.x:port/…/") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = phoneUrl.startsWith("http://"), onClick = { runCatching { Desktop.getDesktop().browse(URI(phoneUrl)) } }) { Text("Open") }
                }
            }
            SectionHeader("History")
            LazyColumn { items(Transfer.history) { t ->
                Row(Modifier.padding(vertical = 3.dp)) { Text(if (t.ok) "✓ " else "✗ ", color = if (t.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("${t.direction} · ${t.name} · ${Formatters.size(t.size)} · ${t.peer} · ${Formatters.date(t.at)}", style = MaterialTheme.typography.bodySmall) } } }
        }
        if (s != null) AstrumCard(Modifier.width(380.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Scan with your phone's camera", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Image(remember(s.url) { qr(s.url) }, "QR code", Modifier.size(280.dp).clip(RoundedCornerShape(16.dp)).background(Color.White))
                Spacer(Modifier.height(10.dp))
                SelectionContainer { Text(s.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Text("Expires in ${remaining / 60}:${"%02d".format(remaining % 60)}", style = MaterialTheme.typography.labelMedium)
                Text("Local HTTP, not encrypted — use trusted networks. Windows Firewall may ask to allow Astrum Files on private networks.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Transfer.live?.let { (n, d, t) -> Text("$n · ${Formatters.size(d)} / ${Formatters.size(t)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    LinearProgressIndicator(progress = { if (t > 0) d.toFloat() / t else 0f }, modifier = Modifier.fillMaxWidth()) }
                Row { TextButton(onClick = { Desktop.getDesktop().open(s.receiveDir) }) { Text("Open received folder") }; TextButton(onClick = Transfer::stop) { Text("End session") } }
            }
        }
    }
    Transfer.incoming?.let { r ->
        if (r.size < 0) AlertDialog(onDismissRequest = {}, title = { Text("Nearby device wants to connect") },
            text = { Text("“${r.name}” (${r.from}) wants to connect to this PC.\n\nIf you allow it, it can download the files you offer and send you files (each one still needs your OK).") },
            confirmButton = { TextButton(onClick = { r.answer.complete(true) }) { Text("Allow") } }, dismissButton = { TextButton(onClick = { r.answer.complete(false) }) { Text("Decline") } })
        else AlertDialog(onDismissRequest = {}, title = { Text("Incoming file") }, text = { Text("${r.from} wants to send:\n\n${r.name}\n${Formatters.size(r.size)}\n\nSaved to Downloads\\Astrum Received.") },
            confirmButton = { TextButton(onClick = { r.answer.complete(true) }) { Text("Accept") } }, dismissButton = { TextButton(onClick = { r.answer.complete(false) }) { Text("Decline") } })
    }
}

fun pickFiles(): List<File>? = java.awt.FileDialog(null as java.awt.Frame?, "Choose files", java.awt.FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }.files.toList().takeIf { it.isNotEmpty() }
fun pickFolder(): File? = javax.swing.JFileChooser().apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY }.let { if (it.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) it.selectedFile else null }

@Composable
private fun QuickShareCard() {
    val peers by Transfer.nearby.peers.collectAsState()
    val discErr by Transfer.nearby.error.collectAsState()
    AstrumCard {
        SectionHeader("Quick Share · nearby devices")
        Text("Phones and PCs with Astrum Files on the same Wi-Fi appear here while they have a session running. Click one to connect — the other device must tap Allow.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (peers.isEmpty()) Text(discErr ?: "Searching… (start a session on the other device)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        peers.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (p.platform == "pc") "🖥  " else "📱  ")
                Column(Modifier.weight(1f)) { Text(p.name, style = MaterialTheme.typography.bodyMedium); Text(p.host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(enabled = Transfer.connecting == null, onClick = { Transfer.connect(p) }) { Text(if (Transfer.connecting == p.name) "Waiting for OK…" else "Connect") }
            }
        }
        SectionHeader("Bluetooth")
        Text("Send or receive files via Bluetooth with any phone (Samsung, Pixel, …) using the Windows Bluetooth wizard. The PC must have Bluetooth and be paired with the phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = Transfer.isWindows, onClick = { Transfer.bluetooth(false) }) { Text("Send via Bluetooth") }
            OutlinedButton(enabled = Transfer.isWindows, onClick = { Transfer.bluetooth(true) }) { Text("Receive via Bluetooth") }
        }
    }
}
