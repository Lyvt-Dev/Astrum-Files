package com.astrum.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.security.VaultAuthException
import com.lyvt.files.core.security.VaultCipher
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

data class VEntry(val id: String, val name: String, val folder: String, val size: Long, val added: Long, val thumb: Boolean) {
    val category get() = FileCategory.of(name.substringAfterLast('.', ""))
}

/**
 * Desktop vault: same AES-256-GCM streaming format as the phone (VaultCipher). Windows has no Android Keystore, so the master
 * key is wrapped with PBKDF2-HMAC-SHA256(password, 310k) and with the recovery code. Use a strong password — the vault
 * folder can be copied and attacked offline, unlike the phone's device-bound key.
 */
object Vault {
    private fun space(decoy: Boolean) = File(Store.dir, if (decoy) "vault-decoy" else "vault")
    private var root = space(false)
    private val blobs get() = File(root, "blobs").apply { mkdirs() }
    private val tmp = File(Store.dir, "vault-view")
    private var key: SecretKey? = null
    var unlocked by mutableStateOf(false)
    var isDecoy by mutableStateOf(false)
    val entries = mutableStateListOf<VEntry>()
    val folders = mutableStateListOf<String>()
    var pendingImport by mutableStateOf<List<File>?>(null)
    var lastActivity = System.currentTimeMillis()
    private var fails = 0; private var lockUntil = 0L

    fun isSetUp() = File(space(false), "pass.wrap").exists()
    fun decoyExists() = File(space(true), "pass.wrap").exists()
    private val b64e = Base64.getEncoder(); private val b64d = Base64.getDecoder()

    private fun recoveryCode(): String { val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; return VaultCipher.randomBytes(24).map { a[(it.toInt() and 0xFF) % 32] }.chunked(4).joinToString("-") { it.joinToString("") } }
    private fun norm(c: String) = c.uppercase().filter { it.isLetterOrDigit() }.toCharArray()

    /** Returns the recovery code (show once). */
    fun create(password: CharArray, decoy: Boolean = false): String {
        val r = space(decoy).apply { deleteRecursively(); mkdirs() }
        val mk = VaultCipher.newKey(); val s1 = VaultCipher.randomBytes(16); val s2 = VaultCipher.randomBytes(16); val code = recoveryCode()
        File(r, "pass.wrap").writeBytes(VaultCipher.seal(VaultCipher.deriveKey(password, s1), mk.encoded))
        File(r, "recovery.wrap").writeBytes(VaultCipher.seal(VaultCipher.deriveKey(norm(code), s2), mk.encoded))
        File(r, "meta.json").writeText(JSONObject().put("v", 1).put("s1", b64e.encodeToString(s1)).put("s2", b64e.encodeToString(s2)).put("iter", VaultCipher.PBKDF2_ITERATIONS).toString())
        if (!decoy) { root = r; key = mk; isDecoy = false; writeIndex(emptyList(), emptyList()); publish() } else {
            // A fresh, empty index — the decoy must never contain anything from the real vault.
            val keep = key; val keepRoot = root; root = r; key = mk; writeIndex(emptyList(), emptyList()); key = keep; root = keepRoot
        }
        return code
    }

    private fun unwrap(r: File, secret: CharArray, recovery: Boolean): ByteArray {
        val m = JSONObject(File(r, "meta.json").readText())
        return VaultCipher.open(VaultCipher.deriveKey(secret, b64d.decode(m.getString(if (recovery) "s2" else "s1")), m.getInt("iter")), File(r, if (recovery) "recovery.wrap" else "pass.wrap").readBytes())
    }

    fun unlock(password: CharArray): Result<Unit> {
        val wait = lockUntil - System.currentTimeMillis()
        if (wait > 0) return Result.failure(IOException("Too many attempts — wait ${wait / 1000 + 1} s"))
        return runCatching {
            val (raw, decoy) = runCatching { unwrap(space(false), password, false) to false }.getOrNull()
                ?: if (decoyExists()) runCatching { unwrap(space(true), password, false) to true }.getOrNull() ?: throw VaultAuthException("bad") else throw VaultAuthException("bad")
            root = space(decoy); isDecoy = decoy; key = SecretKeySpec(raw, "AES"); fails = 0; publish()
        }.recoverCatching { fails++; if (fails >= 5) lockUntil = System.currentTimeMillis() + (30_000L shl (fails - 5).coerceAtMost(6)); throw IOException("Wrong password") }
    }

    fun resetWithRecovery(code: String, newPassword: CharArray): Result<Unit> = runCatching {
        val r = space(false); val raw = unwrap(r, norm(code), true)
        val m = JSONObject(File(r, "meta.json").readText()); val s1 = VaultCipher.randomBytes(16)
        File(r, "pass.wrap").writeBytes(VaultCipher.seal(VaultCipher.deriveKey(newPassword, s1), raw))
        File(r, "meta.json").writeText(m.put("s1", b64e.encodeToString(s1)).toString())
        root = r; key = SecretKeySpec(raw, "AES"); isDecoy = false; publish()
    }.recoverCatching { throw IOException("Recovery code is not valid") }

    fun lock() { key = null; unlocked = false; entries.clear(); folders.clear(); tmp.deleteRecursively(); root = space(false); isDecoy = false }

    private fun k() = key ?: throw IOException("Vault is locked")
    private fun readIndex(): Pair<List<VEntry>, List<String>> {
        val f = File(root, "index.bin"); if (!f.exists()) return emptyList<VEntry>() to emptyList()
        val o = JSONObject(String(VaultCipher.open(k(), f.readBytes(), "index".toByteArray())))
        val a = o.getJSONArray("entries")
        return (0 until a.length()).map { a.getJSONObject(it).let { e -> VEntry(e.getString("id"), e.getString("name"), e.optString("folder"), e.getLong("size"), e.getLong("added"), e.optBoolean("thumb")) } } to
            (o.optJSONArray("folders") ?: JSONArray()).let { fa -> (0 until fa.length()).map { fa.getString(it) } }
    }
    private fun writeIndex(e: List<VEntry> = entries.toList(), f: List<String> = folders.toList()) {
        val a = JSONArray(); e.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("folder", it.folder).put("size", it.size).put("added", it.added).put("thumb", it.thumb)) }
        val t = File(root, "index.tmp"); t.writeBytes(VaultCipher.seal(k(), JSONObject().put("entries", a).put("folders", JSONArray(f)).toString().toByteArray(), "index".toByteArray()))
        java.nio.file.Files.move(t.toPath(), File(root, "index.bin").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    private fun publish() { val (e, f) = readIndex(); entries.clear(); entries.addAll(e.sortedByDescending { it.added }); folders.clear(); folders.addAll(f.sorted()); unlocked = true; lastActivity = System.currentTimeMillis() }

    fun import(files: List<File>, folder: String, deleteOriginals: Boolean, onProgress: (Int) -> Unit) {
        val added = mutableListOf<VEntry>()
        files.filter { it.isFile }.forEachIndexed { i, src ->
            val id = UUID.randomUUID().toString()
            src.inputStream().use { inp -> File(blobs, "$id.bin").outputStream().use { VaultCipher.encryptStream(k(), inp, it) } }
            val thumb = runCatching { if (FileCategory.of(src.extension) != FileCategory.IMAGE) null else ImageIO.read(src)?.let { img ->
                val s = 240.0 / maxOf(img.width, img.height); val t = java.awt.image.BufferedImage((img.width * s).toInt().coerceAtLeast(1), (img.height * s).toInt().coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_RGB)
                t.createGraphics().apply { drawImage(img, 0, 0, t.width, t.height, null); dispose() }
                ByteArrayOutputStream().also { ImageIO.write(t, "jpg", it) }.toByteArray() } }.getOrNull()
            thumb?.let { File(blobs, "$id.thumb").writeBytes(VaultCipher.seal(k(), it, id.toByteArray())) }
            added += VEntry(id, src.name, folder, src.length(), System.currentTimeMillis(), thumb != null)
            onProgress(i + 1)
        }
        writeIndex(entries + added, folders); publish()
        if (deleteOriginals) files.forEach { it.delete() }
    }
    fun thumb(e: VEntry): ImageBitmap? = runCatching { if (!e.thumb) null else VaultCipher.open(k(), File(blobs, "${e.id}.thumb").readBytes(), e.id.toByteArray()).let { ImageIO.read(it.inputStream()).toComposeImageBitmap() } }.getOrNull()
    fun decryptForView(e: VEntry): File { tmp.mkdirs(); val out = File(tmp, "${e.id}_${e.name}"); if (!out.exists()) File(blobs, "${e.id}.bin").inputStream().use { i -> out.outputStream().use { VaultCipher.decryptStream(k(), i, it) } }; out.deleteOnExit(); return out }
    fun export(list: List<VEntry>, dir: File): Int { dir.mkdirs(); list.forEach { e -> val t = FileOperations.uniqueTarget(dir, e.name); File(blobs, "${e.id}.bin").inputStream().use { i -> t.outputStream().use { VaultCipher.decryptStream(k(), i, it) } } }; return list.size }
    fun delete(list: List<VEntry>) { val ids = list.map { it.id }.toSet(); ids.forEach { File(blobs, "$it.bin").delete(); File(blobs, "$it.thumb").delete() }; writeIndex(entries.filterNot { it.id in ids }, folders); publish() }
    fun newFolder(n: String) { if (n.isNotBlank() && n !in folders) { writeIndex(entries, folders + n.trim()); publish() } }
    fun moveTo(list: List<VEntry>, folder: String) { val ids = list.map { it.id }.toSet(); writeIndex(entries.map { if (it.id in ids) it.copy(folder = folder) else it }, folders); publish() }
    fun backup(dest: File) = java.util.zip.ZipOutputStream(dest.outputStream().buffered()).use { z -> root.walkTopDown().filter { it.isFile && it.name != "index.tmp" }.forEach { f ->
        z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(root).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultScreen() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    if (!Vault.unlocked) { LockedOrSetup { code = it }; code?.let { RecoveryDialog(it) { code = null } }; return }
    var folder by remember { mutableStateOf("") }; var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var dialog by remember { mutableStateOf<String?>(null) }
    val pending = Vault.pendingImport
    LaunchedEffect(pending) { if (pending != null) dialog = "import" }
    val visible = Vault.entries.filter { (folder.isEmpty() || it.folder == folder) && (query.isBlank() || it.name.contains(query, true)) }
    val sel = Vault.entries.filter { it.id in selected }
    fun import(files: List<File>, del: Boolean) = scope.launch {
        busy = "Encrypting" to 0f
        msg = withContext(Dispatchers.IO) { runCatching { Vault.import(files, folder, del) { busy = "Encrypting $it of ${files.size}" to it.toFloat() / files.size }; "Added ${files.size} file(s)" }.getOrElse { "Import failed: ${it.message}" } }
        busy = null; Vault.pendingImport = null
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Private Vault", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { pickFiles()?.let { Vault.pendingImport = it } }) { Text("Add files…") }
            if (selected.isNotEmpty()) {
                TextButton(onClick = { pickFolder()?.let { d -> scope.launch { msg = withContext(Dispatchers.IO) { runCatching { "Exported ${Vault.export(sel, d)} file(s)" }.getOrElse { it.message } }; selected.clear() } } }) { Text("Export…") }
                TextButton(onClick = { dialog = "move" }) { Text("Move") }
                TextButton(onClick = { dialog = "delete" }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(onClick = { dialog = "folder" }) { Text("New folder") }
                TextButton(onClick = { pickFolder()?.let { d -> scope.launch { msg = withContext(Dispatchers.IO) { runCatching { val f = File(d, "astrum-vault-${System.currentTimeMillis()}.zip"); Vault.backup(f); "Encrypted backup: ${f.name}" }.getOrElse { it.message } } } } }) { Text("Backup…") }
                if (!Vault.isDecoy) TextButton(onClick = { dialog = "decoy" }) { Text(if (Vault.decoyExists()) "Replace decoy" else "Decoy vault") }
            }
            Button(onClick = Vault::lock, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Filled.Lock, null); Spacer(Modifier.width(6.dp)); Text("Lock") }
        }
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search vault") }, singleLine = true, modifier = Modifier.width(260.dp))
            Pill("All", folder.isEmpty()) { folder = "" }; Vault.folders.forEach { f -> Pill(f, folder == f) { folder = f } }
        }
        busy?.let { (t, f) -> ProgressCard(t, "AES-256-GCM, streamed in 64 KiB chunks", f) }
        if (visible.isEmpty()) EmptyState(Icons.Outlined.Lock, "Nothing here yet", "Add files, or right-click files in Files → “Encrypt into Vault”.")
        LazyVerticalGrid(GridCells.Adaptive(140.dp), contentPadding = PaddingValues(4.dp)) {
            items(visible, key = { it.id }) { e ->
                val t by produceState<ImageBitmap?>(null, e.id) { value = withContext(Dispatchers.IO) { Vault.thumb(e) } }
                Column(Modifier.padding(5.dp).clip(RoundedCornerShape(16.dp)).background(if (e.id in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                    .combinedClickable(onClick = { if (e.id in selected) selected.remove(e.id) else selected.add(e.id) },
                        onDoubleClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { Vault.decryptForView(e) } }.onSuccess { f -> if (Viewers.supports(f)) AppState.openFile = f else AppState.systemOpen(f) }.onFailure { msg = it.message } } })
                    .padding(8.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(e.category.color().copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        t?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } ?: Icon(e.category.icon(), null, tint = e.category.color(), modifier = Modifier.size(36.dp))
                        if (e.id in selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                    Text(e.name, style = MaterialTheme.typography.labelMedium, maxLines = 1); Text(Formatters.size(e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Double-click opens a decrypted copy in a private temporary folder that is wiped when the vault locks.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when (dialog) {
        "import" -> { var del by remember { mutableStateOf(false) }
            AlertDialog(onDismissRequest = { dialog = null; Vault.pendingImport = null }, title = { Text("Encrypt ${pending?.size ?: 0} file(s)?") }, text = { Column {
                Text("Files are encrypted into the vault${if (folder.isNotEmpty()) " folder “$folder”" else ""}.")
                Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Checkbox(del, { del = it }); Text("Delete originals afterwards") }
                if (del) Text("SSDs may keep deleted data physically; secure erasure cannot be guaranteed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            } }, confirmButton = { TextButton(onClick = { pending?.let { import(it, del) }; dialog = null }) { Text("Encrypt") } },
                dismissButton = { TextButton(onClick = { dialog = null; Vault.pendingImport = null }) { Text("Cancel") } }) }
        "folder" -> InputDialog("New vault folder", label = "Name", onConfirm = { Vault.newFolder(it) }, onDismiss = { dialog = null })
        "move" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Move to") }, text = { Column { (listOf("") + Vault.folders).forEach { f ->
            TextButton(onClick = { Vault.moveTo(sel, f); selected.clear(); dialog = null }) { Text(f.ifEmpty { "No folder" }) } } } }, confirmButton = {})
        "delete" -> ConfirmDialog("Delete ${sel.size} item(s) from the vault?", "The encrypted copies are removed. This cannot be undone.", "Delete", true, { Vault.delete(sel); selected.clear() }, { dialog = null })
        "decoy" -> InputDialog("Decoy vault password", password = true, label = "Different password", validate = { if (it.length < 8) "At least 8 characters" else null },
            onConfirm = { p -> msg = runCatching { Vault.create(p.toCharArray(), decoy = true); "Decoy vault ready — that password opens a separate, empty vault." }.getOrElse { it.message } }, onDismiss = { dialog = null })
    }
}

@Composable
private fun LockedOrSetup(onCode: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }; var pw2 by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }
    var recover by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val setup = !Vault.isSetUp()
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AstrumCard(Modifier.width(440.dp), padding = 28.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(Icons.Outlined.Lock, MaterialTheme.colorScheme.primary, 72.dp); Spacer(Modifier.height(14.dp))
                Text(if (setup) "Create your Private Vault" else "Vault is locked", style = MaterialTheme.typography.headlineSmall)
                if (setup) Text("Files are encrypted with AES-256-GCM. Choose a strong password — on a PC the encrypted folder could be copied and attacked offline.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (setup) OutlinedTextField(pw2, { pw2 = it }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(14.dp))
                Button(enabled = !busy && if (setup) pw.length >= 8 && pw == pw2 else pw.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), onClick = {
                    busy = true
                    scope.launch {
                        if (setup) onCode(withContext(Dispatchers.Default) { Vault.create(pw.toCharArray()) })
                        else withContext(Dispatchers.Default) { Vault.unlock(pw.toCharArray()) }.onFailure { err = it.message }
                        pw = ""; pw2 = ""; busy = false
                    }
                }) { Text(if (busy) "Deriving key…" else if (setup) "Create vault (min. 8 characters)" else "Unlock") }
                if (!setup) TextButton(onClick = { recover = true }) { Text("Forgot password? Use recovery code") }
            }
        }
    }
    if (recover) { var c by remember { mutableStateOf("") }; var np by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { recover = false }, title = { Text("Reset password") }, text = { Column {
            OutlinedTextField(c, { c = it }, label = { Text("Recovery code") }, singleLine = true)
            OutlinedTextField(np, { np = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) } },
            confirmButton = { TextButton(enabled = np.length >= 8, onClick = { Vault.resetWithRecovery(c, np.toCharArray()).onFailure { err = it.message }; recover = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { recover = false }) { Text("Cancel") } }) }
}

@Composable
private fun RecoveryDialog(code: String, onDone: () -> Unit) = AlertDialog(onDismissRequest = {}, title = { Text("Save your recovery code") }, text = { Column {
    Text("This is the only way to reset your password. Without password and code the files cannot be recovered — there is no back door.")
    Spacer(Modifier.height(10.dp)); androidx.compose.foundation.text.selection.SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) } } },
    confirmButton = { TextButton(onClick = onDone) { Text("I have saved it") } })
