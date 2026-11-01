package com.lyvt.files.feature.devtools

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.Hashing
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.Routes
import com.lyvt.files.ui.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object EncodingDetector {
    /** Detects BOMs, validates UTF-8 on the first 64 KiB, flags binary content, and reports line endings. */
    fun detect(f: File): List<Pair<String, String>> {
        val head = f.inputStream().use { s -> ByteArray(minOf(f.length(), 65536L).toInt()).also { var o = 0; while (o < it.size) { val n = s.read(it, o, it.size - o); if (n < 0) break; o += n } } }
        val bom = when {
            head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte() -> "UTF-8 (BOM)"
            head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> "UTF-16 LE (BOM)"
            head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> "UTF-16 BE (BOM)"
            else -> null
        }
        val nulls = head.count { it == 0.toByte() }
        val utf8 = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(head.copyOf(trimIncomplete(head)))) }.isSuccess
        val enc = bom ?: when { nulls > 0 -> "Binary (contains NUL bytes)"; head.all { it >= 0 } -> "ASCII"; utf8 -> "UTF-8"; else -> "Unknown 8-bit (likely ISO-8859-1 / Windows-1252)" }
        val text = String(head, Charsets.ISO_8859_1)
        val crlf = text.split("\r\n").size - 1; val lf = text.count { it == '\n' } - crlf
        val endings = when { nulls > 0 -> "—"; crlf > 0 && lf > 0 -> "Mixed (CRLF $crlf, LF $lf)"; crlf > 0 -> "CRLF (Windows)"; lf > 0 -> "LF (Unix)"; else -> "None in first 64 KiB" }
        return listOf("Encoding" to enc, "Line endings" to endings)
    }
    private fun trimIncomplete(b: ByteArray): Int { var n = b.size; var i = 0; while (i < 3 && n > 0 && (b[n - 1].toInt() and 0xC0) == 0x80) { n--; i++ }; if (n > 0 && (b[n - 1].toInt() and 0x80) != 0) n--; return if (b.size < 65536) b.size else n }
}

private fun apkInfo(ctx: Context, f: File): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val flags = PackageManager.GET_PERMISSIONS or (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    val pi = pm.getPackageArchiveInfo(f.path, flags) ?: return listOf("APK" to "Could not parse (corrupted or split APK)")
    pi.applicationInfo?.let { it.sourceDir = f.path; it.publicSourceDir = f.path }
    val installed = runCatching { pm.getPackageInfo(pi.packageName, 0) }.getOrNull()
    return listOfNotNull(
        "App name" to (pi.applicationInfo?.loadLabel(pm)?.toString() ?: "?"),
        "Package" to pi.packageName,
        "Version" to "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})",
        "Min / target SDK" to "${pi.applicationInfo?.minSdkVersion} / ${pi.applicationInfo?.targetSdkVersion}",
        "Installed" to (installed?.let { "Yes, version ${it.versionName}" } ?: "No"),
        "Permissions (${pi.requestedPermissions?.size ?: 0})" to (pi.requestedPermissions?.joinToString("\n") { it.removePrefix("android.permission.") } ?: "none"),
    )
}

private fun permissions(f: File): String = runCatching {
    val st = Os.stat(f.path)
    val m = st.st_mode
    val bits = "rwxrwxrwx".mapIndexed { i, c -> if (m and (1 shl (8 - i)) != 0) c else '-' }.joinToString("")
    "$bits  (uid ${st.st_uid}, gid ${st.st_gid})"
}.getOrElse { "r:${f.canRead()} w:${f.canWrite()} x:${f.canExecute()}" }

@Composable
fun InspectorScreen(nav: NavHostController, path: String) {
    val f = File(path)
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var hashes by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var hashing by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) {
        info = withContext(Dispatchers.IO) {
            val base = listOf(
                "Path" to f.absolutePath, "Size" to "${Formatters.size(f.length())} (${f.length()} bytes)",
                "MIME (by extension)" to (MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "unknown"),
                "MIME (system guess)" to (runCatching { java.net.URLConnection.guessContentTypeFromStream(f.inputStream().buffered()) }.getOrNull() ?: "unknown"),
                "Modified" to Formatters.date(f.lastModified()), "Permissions" to permissions(f),
            )
            base + (if (f.isFile) runCatching { EncodingDetector.detect(f) }.getOrDefault(emptyList()) else emptyList()) +
                (if (f.extension.equals("apk", true)) runCatching { apkInfo(ctx, f) }.getOrElse { listOf("APK" to (it.message ?: "error")) } else emptyList())
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) { Text("Inspector", style = MaterialTheme.typography.headlineSmall); Text(f.name, style = MaterialTheme.typography.bodySmall) }
                if (f.isFile) TextButton(onClick = { nav.navigate(Routes.text(path)) }) { Text("Open as text") }
            }
        }
        item {
            LyvtCard {
                SectionHeader("Details")
                SelectionContainer { Column { info.forEach { (k, v) -> Column(Modifier.padding(vertical = 4.dp)) {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyMedium) } } } }
            }
        }
        if (f.isFile) item {
            LyvtCard {
                SectionHeader("Hash calculator")
                if (hashes == null) TextButton(enabled = !hashing, onClick = {
                    hashing = true
                    scope.launch(Dispatchers.IO) {
                        hashes = listOf("MD5", "SHA-1", "SHA-256", "SHA-512").map { a -> a to runCatching { Hashing.digest(f.inputStream(), a) }.getOrElse { "error: ${it.message}" } }
                        hashing = false
                    }
                }) { Text(if (hashing) "Computing (streaming)…" else "Compute MD5 · SHA-1 · SHA-256 · SHA-512") }
                hashes?.let { hs ->
                    SelectionContainer { Column { hs.forEach { (a, h) -> Text(a, style = MaterialTheme.typography.labelMedium); Text(h, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } } }
                    OutlinedTextField(compare, { compare = it }, label = { Text("Paste a checksum to verify") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (compare.isNotBlank()) {
                        val match = hs.firstOrNull { it.second.equals(compare.trim(), true) }
                        Text(match?.let { "✓ Matches ${it.first}" } ?: "✗ No match", color = if (match != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
