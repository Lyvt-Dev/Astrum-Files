package com.lyvt.files.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

data class NearbyPeer(val id: String, val name: String, val platform: String, val host: String, val port: Int, val seen: Long)

/**
 * "Astrum Quick Share": finds other Astrum devices (phone or PC) on the same Wi-Fi without scanning a QR code.
 *
 * Protocol (pure JVM, shared by Android and desktop):
 *  - While a transfer session runs, the device broadcasts a small UDP beacon on port [PORT] every 2 s:
 *    `ASTRUM1|<instanceId>|<platform>|<httpPort>|<name>`. The beacon deliberately contains NO access token.
 *  - A device that wants to connect calls `GET /knock?name=…` on the peer's transfer server. The peer's user sees
 *    "<name> wants to connect" and must tap Allow; only then is the token URL returned.
 *  - All actual transfers use the existing token-protected HTTP session (with per-file approval for uploads).
 */
class NearbyDiscovery(private val selfName: String, private val platform: String) {
    companion object {
        const val PORT = 47823
        private const val MAGIC = "ASTRUM1"
        private const val TTL_MS = 7_000L

        fun parse(text: String, host: String, now: Long = System.currentTimeMillis()): NearbyPeer? {
            val p = text.split('|', limit = 5)
            if (p.size != 5 || p[0] != MAGIC) return null
            val port = p[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return NearbyPeer(p[1].take(32), p[4].take(40).ifBlank { "Astrum device" }, p[2].take(12), host, port, now)
        }

        fun beacon(id: String, platform: String, port: Int, name: String) = "$MAGIC|$id|$platform|$port|${name.replace('|', ' ').take(40)}"

        /** Asks [peer] for access. Blocks until the other user allows/declines (max ~2 min). Returns the session URL. */
        fun requestAccess(peer: NearbyPeer, myName: String): Result<String> = runCatching {
            val c = URL("http://${peer.host}:${peer.port}/knock?name=${URLEncoder.encode(myName.take(40), "UTF-8")}").openConnection() as HttpURLConnection
            c.connectTimeout = 5_000; c.readTimeout = 130_000
            try {
                when (c.responseCode) {
                    200 -> c.inputStream.bufferedReader().readText().trim().also { require(it.startsWith("http://${peer.host}:${peer.port}/")) { "Unexpected answer" } }
                    403 -> error("${peer.name} declined")
                    429 -> error("${peer.name} is busy — try again in a moment")
                    else -> error("${peer.name} is not accepting connections (${c.responseCode})")
                }
            } finally { c.disconnect() }
        }

        fun broadcastAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }.filter { it.address is Inet4Address && it.address.isSiteLocalAddress }.mapNotNull { it.broadcast }
        }.getOrDefault(emptyList()) + InetAddress.getByName("255.255.255.255")
    }

    val id: String = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    /** Set to the transfer server's port while a session runs; null = don't advertise. */
    @Volatile var advertisePort: Int? = null
    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (scope != null) return
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        val sock = runCatching { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(PORT)) } }
            .onFailure { error.value = "Nearby discovery unavailable: ${it.message}" }.getOrNull() ?: return
        socket = sock
        sc.launch {
            val buf = ByteArray(512)
            while (isActive && !sock.isClosed) {
                val pk = DatagramPacket(buf, buf.size)
                val got = runCatching { sock.receive(pk); true }.getOrDefault(false)
                val now = System.currentTimeMillis()
                if (got) {
                    val peer = parse(String(pk.data, 0, pk.length, Charsets.UTF_8), pk.address.hostAddress ?: continue, now)
                    if (peer != null && peer.id != id) peers.value = peers.value.filterNot { it.id == peer.id } + peer
                }
                peers.value.filter { now - it.seen > TTL_MS }.takeIf { it.isNotEmpty() }?.let { old -> peers.value = peers.value - old.toSet() }
            }
        }
        sc.launch {
            while (isActive) {
                advertisePort?.let { port ->
                    val data = beacon(id, platform, port, selfName).toByteArray(Charsets.UTF_8)
                    broadcastAddresses().distinct().forEach { a -> runCatching { sock.send(DatagramPacket(data, data.size, a, PORT)) } }
                }
                delay(2_000)
            }
        }
    }

    fun stop() { runCatching { socket?.close() }; socket = null; scope?.cancel(); scope = null; peers.value = emptyList() }
}
