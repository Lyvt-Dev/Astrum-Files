package com.lyvt.files.core.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.lyvt.files.core.database.ConnectionEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.common.SecurityUtils
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.security.PublicKey
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RemoteEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

/** Thrown on first SFTP connect so the user can verify and pin the server's host key. Never auto-accepted. */
class UnknownHostKeyException(val fingerprint: String) : IOException("Unknown SSH host key")
class HostKeyMismatchException(val fingerprint: String) : IOException("SSH host key changed! Possible man-in-the-middle attack.")

interface RemoteFs : AutoCloseable {
    suspend fun list(path: String): List<RemoteEntry>
    suspend fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit)
    suspend fun upload(input: InputStream, length: Long, remotePath: String, onBytes: (Long) -> Unit)
    fun rename(from: String, to: String)
    fun delete(entry: RemoteEntry)
    fun mkdir(path: String)
}

internal suspend fun pump(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit) {
    val buf = ByteArray(64 * 1024)
    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); onBytes(n.toLong()) }
    out.flush()
}

fun joinPath(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"
fun parentPath(p: String) = p.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

object RemoteFactory {
    init {
        // Use the full BouncyCastle provider shipped with sshj/smbj instead of Android's stripped one.
        runCatching {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").getDeclaredConstructor().newInstance() as java.security.Provider, 1)
        }
    }

    fun open(c: ConnectionEntity): RemoteFs {
        val pw = CredentialCipher.decrypt(c.secret)
        return when (c.protocol) {
            "SFTP" -> SftpFs(c, pw)
            "FTP" -> FtpFs(c, pw)
            "WEBDAV" -> WebDavFs(c, pw)
            "SMB" -> SmbFs(c, pw)
            else -> throw IOException("Unsupported protocol ${c.protocol}")
        }
    }

    fun describe(e: Throwable): String = when (e) {
        is UnknownHostKeyException -> "New server key: ${e.fingerprint}"
        is HostKeyMismatchException -> e.message!!
        is java.net.UnknownHostException -> "Host not found. Check the address and that you are on the right network."
        is java.net.ConnectException -> "Connection refused. Is the server running and the port correct?"
        is java.net.SocketTimeoutException -> "Connection timed out."
        is javax.net.ssl.SSLHandshakeException -> "TLS certificate not trusted: ${e.message}. Certificate validation is never disabled."
        is net.schmizz.sshj.userauth.UserAuthException -> "Authentication failed: wrong username or password."
        is com.hierynomus.mssmb2.SMBApiException -> "SMB error: ${e.status}"
        else -> e.message ?: e.javaClass.simpleName
    }
}

class SftpFs(c: ConnectionEntity, pw: String) : RemoteFs {
    private val ssh = SSHClient()
    private val sftp: SFTPClient
    init {
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(host: String, port: Int, key: PublicKey): Boolean {
                val fp = SecurityUtils.getFingerprint(key)
                return when {
                    c.hostKey.isEmpty() -> throw UnknownHostKeyException(fp)
                    c.hostKey == fp -> true
                    else -> throw HostKeyMismatchException(fp)
                }
            }
            override fun findExistingAlgorithms(host: String, port: Int): MutableList<String> = mutableListOf()
        })
        ssh.connectTimeout = 15000; ssh.timeout = 30000
        try {
            ssh.connect(c.host, c.port)
        } catch (e: Exception) {
            // sshj wraps verifier exceptions; unwrap ours.
            var t: Throwable? = e
            while (t != null) { if (t is UnknownHostKeyException || t is HostKeyMismatchException) throw t; t = t.cause }
            throw e
        }
        ssh.authPassword(c.username, pw)
        sftp = ssh.newSFTPClient()
    }
    override suspend fun list(path: String) = sftp.ls(path).filterNot { it.name == "." || it.name == ".." }.map {
        RemoteEntry(it.path, it.name, it.isDirectory, it.attributes.size, it.attributes.mtime * 1000)
    }
    override suspend fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit) =
        sftp.open(path).use { f -> f.RemoteFileInputStream().use { pump(it, out, onBytes) } }
    override suspend fun upload(input: InputStream, length: Long, remotePath: String, onBytes: (Long) -> Unit) =
        sftp.open(remotePath, EnumSet.of(net.schmizz.sshj.sftp.OpenMode.WRITE, net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.EXCL))
            .use { f -> f.RemoteFileOutputStream().use { pump(input, it, onBytes) } }
    override fun rename(from: String, to: String) = sftp.rename(from, to)
    override fun delete(entry: RemoteEntry) = if (entry.isDirectory) sftp.rmdir(entry.path) else sftp.rm(entry.path)
    override fun mkdir(path: String) = sftp.mkdir(path)
    override fun close() { runCatching { sftp.close() }; runCatching { ssh.disconnect() } }
}

/** Plain FTP: legacy and unencrypted. The UI labels it insecure. */
class FtpFs(c: ConnectionEntity, pw: String) : RemoteFs {
    private val ftp = FTPClient()
    init {
        ftp.connectTimeout = 15000; ftp.setDataTimeout(java.time.Duration.ofSeconds(30))
        ftp.connect(c.host, c.port)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) { ftp.disconnect(); throw IOException("FTP server refused connection") }
        if (!ftp.login(c.username.ifEmpty { "anonymous" }, pw)) throw IOException("FTP login failed")
        ftp.enterLocalPassiveMode(); ftp.setFileType(FTP.BINARY_FILE_TYPE); ftp.controlEncoding = "UTF-8"
    }
    override suspend fun list(path: String) = ftp.listFiles(path).filterNot { it.name == "." || it.name == ".." }.map {
        RemoteEntry(joinPath(path, it.name), it.name, it.isDirectory, it.size, it.timestamp?.timeInMillis ?: 0)
    }
    override suspend fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit) {
        val s = ftp.retrieveFileStream(path) ?: throw IOException("Download failed: ${ftp.replyString}")
        s.use { pump(it, out, onBytes) }; ftp.completePendingCommand()
    }
    override suspend fun upload(input: InputStream, length: Long, remotePath: String, onBytes: (Long) -> Unit) {
        if (ftp.listFiles(remotePath).isNotEmpty()) throw IOException("A file with this name already exists on the server")
        val s = ftp.storeFileStream(remotePath) ?: throw IOException("Upload failed: ${ftp.replyString}")
        s.use { pump(input, it, onBytes) }; if (!ftp.completePendingCommand()) throw IOException("Upload failed: ${ftp.replyString}")
    }
    override fun rename(from: String, to: String) { if (!ftp.rename(from, to)) throw IOException(ftp.replyString) }
    override fun delete(entry: RemoteEntry) { if (!(if (entry.isDirectory) ftp.removeDirectory(entry.path) else ftp.deleteFile(entry.path))) throw IOException(ftp.replyString) }
    override fun mkdir(path: String) { if (!ftp.makeDirectory(path)) throw IOException(ftp.replyString) }
    override fun close() { runCatching { ftp.logout() }; runCatching { ftp.disconnect() } }
}

/** WebDAV over HTTP(S). HTTPS uses the system trust store; certificate errors are reported, never bypassed. */
class WebDavFs(private val c: ConnectionEntity, pw: String) : RemoteFs {
    private val auth = Credentials.basic(c.username, pw)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val base = c.host.trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }.let { url ->
        if (c.port > 0 && !Regex(":\\d+").containsMatchIn(url.substringAfter("://").substringBefore('/'))) url.replaceFirst(Regex("(https?://[^/]+)"), "$1:${c.port}") else url
    }
    private fun url(p: String) = base + p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun req(p: String) = Request.Builder().url(url(p)).header("Authorization", auth)
    private fun exec(r: Request) = http.newCall(r).execute().also { if (!it.isSuccessful && it.code != 207) { it.close(); throw IOException("HTTP ${it.code} ${it.message}") } }
    private val basePath = java.net.URI(base).path.trimEnd('/')

    override suspend fun list(path: String): List<RemoteEntry> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val xml = exec(req(path).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()).use { it.body!!.string() }
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser(); p.setInput(xml.reader())
        val out = mutableListOf<RemoteEntry>()
        var href = ""; var dir = false; var size = 0L; var mod = 0L; var tag = ""
        val df = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        while (p.eventType != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> { tag = p.name; if (tag == "response") { href = ""; dir = false; size = 0; mod = 0 }; if (tag == "collection") dir = true }
                XmlPullParser.TEXT -> when (tag) {
                    "href" -> href += p.text.trim(); "getcontentlength" -> size = p.text.trim().toLongOrNull() ?: 0
                    "getlastmodified" -> mod = runCatching { df.parse(p.text.trim())!!.time }.getOrDefault(0)
                }
                XmlPullParser.END_TAG -> { if (p.name == "response") {
                    val rel = URLDecoder.decode(href, "UTF-8").let { h -> java.net.URI(h).path ?: h }.removePrefix(basePath).trimEnd('/').ifEmpty { "/" }
                    if (rel.trimEnd('/') != path.trimEnd('/').ifEmpty { "/" }) out += RemoteEntry(rel, rel.substringAfterLast('/'), dir, size, mod)
                }; tag = "" }
            }
            p.next()
        }
        return out
    }
    override suspend fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit) =
        exec(req(path).get().build()).use { r -> r.body!!.byteStream().use { pump(it, out, onBytes) } }
    override suspend fun upload(input: InputStream, length: Long, remotePath: String, onBytes: (Long) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) { val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; sink.write(buf, 0, n); onBytes(n.toLong()) } }
        }
        exec(req(remotePath).header("If-None-Match", "*").put(body).build()).close()
    }
    override fun rename(from: String, to: String) = exec(req(from).header("Destination", url(to)).header("Overwrite", "F").method("MOVE", null).build()).close()
    override fun delete(entry: RemoteEntry) = exec(req(entry.path).delete().build()).close()
    override fun mkdir(path: String) = exec(req(path).method("MKCOL", null).build()).close()
    override fun close() {}
}

class SmbFs(c: ConnectionEntity, pw: String) : RemoteFs {
    private val client = SMBClient()
    private val conn = client.connect(c.host, if (c.port > 0) c.port else 445)
    private val session = conn.authenticate(AuthenticationContext(c.username, pw.toCharArray(), null))
    private val share = session.connectShare(c.share.ifEmpty { throw IOException("Share name is required for SMB") }) as DiskShare
    private fun p(path: String) = path.trim('/').replace('/', '\\')
    override suspend fun list(path: String) = share.list(p(path)).filterNot { it.fileName == "." || it.fileName == ".." }.map {
        val dir = it.fileAttributes and 0x10L != 0L
        RemoteEntry(joinPath(path, it.fileName), it.fileName, dir, it.endOfFile, it.lastWriteTime.toEpochMillis())
    }
    override suspend fun download(path: String, out: OutputStream, onBytes: (Long) -> Unit) =
        share.openFile(p(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            .use { f -> f.inputStream.use { pump(it, out, onBytes) } }
    override suspend fun upload(input: InputStream, length: Long, remotePath: String, onBytes: (Long) -> Unit) =
        share.openFile(p(remotePath), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, null)
            .use { f -> f.outputStream.use { pump(input, it, onBytes) } }
    override fun rename(from: String, to: String) = share.openFile(p(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_WRITE), null,
        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(p(to), false) }
    override fun delete(entry: RemoteEntry) = if (entry.isDirectory) share.rmdir(p(entry.path), false) else share.rm(p(entry.path))
    override fun mkdir(path: String) = share.mkdir(p(path))
    override fun close() { runCatching { share.close() }; runCatching { session.close() }; runCatching { conn.close() }; runCatching { client.close() } }
}

fun File.isSafeLocalName(name: String) = !name.contains('/') && name != "." && name != ".."
