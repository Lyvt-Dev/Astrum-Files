package com.lyvt.files.core.network

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.OpenableColumns
import com.lyvt.files.core.storage.FileOperations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

data class SharedItem(val uri: Uri, val name: String, val size: Long)
data class IncomingRequest(val id: Long, val name: String, val size: Long, val from: String, val answer: CompletableDeferred<Boolean>)
data class LiveTransfer(val name: String, val done: Long, val total: Long, val incoming: Boolean)
data class TransferEvent(val incoming: Boolean, val name: String, val size: Long, val peer: String, val ok: Boolean)

/**
 * Minimal HTTP server for local-network transfers.
 *  - Bound to the device's LAN address on a random port; every URL contains a 128-bit random token (in the QR code).
 *  - Session expires automatically; wrong tokens get 404 and repeated failures from one address are blocked.
 *  - Every incoming upload must be approved on the phone. Uploads never overwrite and land in Download/Astrum Received.
 *  - Downloads support HTTP Range so interrupted transfers can be resumed by the browser.
 *  - Limitation: plain HTTP on the local network (no TLS). The token prevents unauthorised access but traffic is not
 *    encrypted; use it only on networks you trust. This is shown in the UI.
 */
class TransferServer(private val ctx: Context, private val shared: List<SharedItem>, private val ttlMs: Long, private val onEvent: (TransferEvent) -> Unit) {
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val expiresAt = System.currentTimeMillis() + ttlMs
    val incoming = MutableStateFlow<IncomingRequest?>(null)
    val live = MutableStateFlow<LiveTransfer?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(0, 16, lanAddress())
    private val failures = HashMap<String, Int>()
    private var nsd: NsdManager.RegistrationListener? = null
    val url: String get() = "http://${server.inetAddress.hostAddress}:${server.localPort}/$token/"

    fun start() {
        scope.launch {
            while (isActive && !server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                if (System.currentTimeMillis() > expiresAt) { s.close(); stop(); break }
                launch { runCatching { s.use { handle(it) } } }
            }
        }
        scope.launch { kotlinx.coroutines.delay(ttlMs); stop() }
        runCatching {
            val nm = ctx.getSystemService(NsdManager::class.java)
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(p0: NsdServiceInfo?) {}
                override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
                override fun onServiceUnregistered(p0: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
            }
            nm.registerService(NsdServiceInfo().apply { serviceName = "Astrum Transfer"; serviceType = "_http._tcp."; port = server.localPort }, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = l
        }
    }

    fun stop() {
        runCatching { nsd?.let { ctx.getSystemService(NsdManager::class.java).unregisterService(it) } }; nsd = null
        runCatching { server.close() }
        incoming.value?.answer?.complete(false)
        scope.cancel()
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) { val c = i.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()); if (sb.length > 8192) break }
        return sb.toString()
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        out.write("HTTP/1.1 $code ${if (code < 300) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n$extra\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private suspend fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        val peer = sock.inetAddress.hostAddress ?: "?"
        if ((failures[peer] ?: 0) >= 5) return
        val input = BufferedInputStream(sock.getInputStream()); val out = sock.getOutputStream()
        val (method, target) = readLine(input).split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val headers = HashMap<String, String>()
        while (true) { val l = readLine(input); if (l.isEmpty()) break; l.split(':', limit = 2).takeIf { it.size == 2 }?.let { headers[it[0].trim().lowercase()] = it[1].trim() } }
        val path = target.substringBefore('?'); val query = target.substringAfter('?', "")
        if (!path.startsWith("/$token/")) { failures[peer] = (failures[peer] ?: 0) + 1; respond(out, 404, "text/plain", "Not found".toByteArray()); return }
        val rest = path.removePrefix("/$token/")
        when {
            method == "GET" && rest.isEmpty() -> respond(out, 200, "text/html; charset=utf-8", page().toByteArray())
            method == "GET" && rest.startsWith("f/") -> serve(rest.removePrefix("f/").toIntOrNull(), headers["range"], out, peer)
            method == "POST" && rest == "upload" -> receive(query, headers, input, out, peer)
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun serve(index: Int?, range: String?, out: OutputStream, peer: String) {
        val item = index?.let { shared.getOrNull(it) } ?: return respond(out, 404, "text/plain", "Not found".toByteArray())
        val start = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
        val len = if (item.size >= 0) item.size - start else -1
        val disp = "Content-Disposition: attachment; filename*=UTF-8''${java.net.URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")}\r\nAccept-Ranges: bytes\r\n" +
            if (start > 0 && item.size > 0) "Content-Range: bytes $start-${item.size - 1}/${item.size}\r\n" else ""
        out.write("HTTP/1.1 ${if (start > 0) 206 else 200} OK\r\nContent-Type: application/octet-stream\r\n${if (len >= 0) "Content-Length: $len\r\n" else ""}${disp}Connection: close\r\n\r\n".toByteArray())
        var done = 0L; var ok = false
        try {
            ctx.contentResolver.openInputStream(item.uri)!!.use { i ->
                i.skip(start)
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; live.value = LiveTransfer(item.name, done + start, item.size, false) }
            }
            out.flush(); ok = true
        } finally { live.value = null; onEvent(TransferEvent(false, item.name, done, peer, ok)) }
    }

    private suspend fun receive(query: String, headers: Map<String, String>, input: InputStream, out: OutputStream, peer: String) {
        val name = URLDecoder.decode(query.split('&').firstOrNull { it.startsWith("name=") }?.removePrefix("name=") ?: "", "UTF-8")
            .substringAfterLast('/').substringAfterLast('\\').ifBlank { "upload.bin" }
        val size = headers["content-length"]?.toLongOrNull() ?: return respond(out, 411, "text/plain", "Length required".toByteArray())
        if (FileOperations.validateName(name) != null) return respond(out, 400, "text/plain", "Invalid file name".toByteArray())
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Astrum Received").apply { mkdirs() }
        if (dir.usableSpace < size) return respond(out, 507, "text/plain", "Not enough space on the phone".toByteArray())
        val req = IncomingRequest(System.nanoTime(), name, size, peer, CompletableDeferred())
        incoming.value = req
        val approved = withTimeoutOrNull(120_000) { req.answer.await() } ?: false
        incoming.value = null
        if (!approved) return respond(out, 403, "text/plain", "Declined on the phone".toByteArray())
        val target = FileOperations.uniqueTarget(dir, name)
        val part = File(dir, target.name + FileOperations.PART_SUFFIX)
        var done = 0L
        try {
            part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (done < size) { val n = input.read(buf, 0, minOf(buf.size.toLong(), size - done).toInt()); if (n < 0) break; o.write(buf, 0, n); done += n; live.value = LiveTransfer(name, done, size, true) }
            }
            if (done != size || !part.renameTo(target)) throw java.io.IOException("Transfer interrupted")
            respond(out, 200, "text/plain", "Saved ${target.name}".toByteArray())
            onEvent(TransferEvent(true, target.name, size, peer, true))
        } catch (e: Exception) {
            part.delete(); onEvent(TransferEvent(true, name, done, peer, false))
            runCatching { respond(out, 500, "text/plain", (e.message ?: "error").toByteArray()) }
        } finally { live.value = null }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Astrum Transfer</title><style>
:root{color-scheme:light dark;--p:#7548F8;--g:#E8B95A}body{font-family:system-ui,sans-serif;max-width:720px;margin:0 auto;padding:24px;background:Canvas;color:CanvasText}
h1{font-size:22px}h1 span{color:var(--p)}.card{border:1px solid #8884;border-radius:18px;padding:16px;margin:14px 0}
a.f{display:flex;justify-content:space-between;padding:10px 4px;border-bottom:1px solid #8882;color:inherit;text-decoration:none}a.f:hover{color:var(--p)}
button,label.b{background:var(--p);color:#fff;border:0;border-radius:12px;padding:10px 16px;font-size:15px;cursor:pointer;display:inline-block}
#log div{font-size:14px;padding:4px 0}progress{width:100%}small{opacity:.7}</style></head><body>
<h1>✦ <span>Astrum</span> Transfer</h1><small>Local session · expires automatically · files you send must be approved on the phone</small>
<div class="card"><b>Files from the phone</b>${if (shared.isEmpty()) "<p><small>Nothing shared.</small></p>" else shared.mapIndexed { i, it ->
        "<a class=f href=\"f/$i\" download><span>${esc(it.name)}</span><small>${if (it.size >= 0) com.lyvt.files.core.common.Formatters.size(it.size) else ""}</small></a>" }.joinToString("")}</div>
<div class="card"><b>Send to the phone</b><p><label class="b">Choose files<input id="i" type="file" multiple hidden></label></p><progress id="pr" value="0" max="1" hidden></progress><div id="log"></div></div>
<script>
const i=document.getElementById('i'),log=document.getElementById('log'),pr=document.getElementById('pr');
i.onchange=async()=>{for(const f of i.files){const d=document.createElement('div');d.textContent='⏳ '+f.name+' — waiting for approval on the phone…';log.prepend(d);
await new Promise(r=>{const x=new XMLHttpRequest();x.open('POST','upload?name='+encodeURIComponent(f.name));pr.hidden=false;
x.upload.onprogress=e=>{pr.value=e.loaded/e.total};x.onload=()=>{d.textContent=(x.status==200?'✅ ':'❌ ')+f.name+' — '+x.responseText;r()};
x.onerror=()=>{d.textContent='❌ '+f.name+' — connection lost';r()};x.send(f)});}pr.hidden=true;i.value='';};
</script></body></html>"""

    companion object {
        fun lanAddress(): java.net.InetAddress? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

        fun itemFor(ctx: Context, uri: Uri): SharedItem = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) SharedItem(uri, c.getString(0) ?: "file", if (c.isNull(1)) -1 else c.getLong(1)) else null
        } ?: SharedItem(uri, uri.lastPathSegment ?: "file", -1)
    }
}

