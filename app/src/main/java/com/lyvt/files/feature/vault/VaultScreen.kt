package com.lyvt.files.feature.vault

import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.security.VaultEntry
import com.lyvt.files.core.security.VaultState
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.IconBadge
import com.lyvt.files.ui.LoadingState
import com.lyvt.files.ui.Pill
import com.lyvt.files.ui.TextInputDialog
import com.lyvt.files.ui.color
import com.lyvt.files.ui.icon
import com.lyvt.files.ui.openFile
import kotlinx.coroutines.launch
import javax.crypto.Cipher

private fun biometricAvailable(ctx: android.content.Context) =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

private fun promptBiometric(activity: FragmentActivity, cipher: Cipher, title: String, onSuccess: (Cipher) -> Unit, onError: (String) -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { result.cryptoObject?.cipher?.let(onSuccess) }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError(errString.toString()) }
    })
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("Use PIN")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build(), BiometricPrompt.CryptoObject(cipher))
}

@Composable
fun VaultScreen(nav: NavHostController, vm: VaultViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val recovery by vm.recoveryCode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                VaultState.NotSetUp -> SetupView(vm)
                VaultState.Locked -> LockedView(vm)
                is VaultState.Unlocked -> UnlockedView(nav, vm, s, snackbar)
            }
            busy?.let { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) { LoadingState(it) } }
        }
    }
    recovery?.let { code ->
        AlertDialog(onDismissRequest = {}, title = { Text("Save your recovery code") }, text = {
            Column {
                Text("This code is the only way to reset your PIN or restore a backup. Write it down and keep it somewhere safe. " +
                    "Lyvt cannot recover your files without it — there is no server and no back door.")
                Spacer(Modifier.height(12.dp))
                Text(code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp))
            }
        }, confirmButton = { TextButton(onClick = { vm.recoveryCode.value = null }) { Text("I have saved it") } })
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, { v -> onChange(v.filter(Char::isDigit).take(12)) }, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SetupView(vm: VaultViewModel) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var restore by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        IconBadge(Icons.Outlined.Lock, MaterialTheme.colorScheme.primary, 88.dp)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.vault_setup), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Files you add are encrypted with AES-256-GCM using a key protected by your PIN and the Android Keystore. " +
            "They are stored in app-private storage and are unreadable without unlocking.",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        PinField(pin, { pin = it }, "PIN (at least 6 digits)")
        Spacer(Modifier.height(10.dp))
        PinField(confirm, { confirm = it }, "Confirm PIN")
        Spacer(Modifier.height(20.dp))
        Button(enabled = pin.length >= 6 && pin == confirm, onClick = { vm.setUp(pin) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Create vault") }
        TextButton(onClick = { restore = true }) { Text("Restore from encrypted backup") }
    }
    if (restore) RestoreDialog(vm) { restore = false }
}

@Composable
private fun RestoreDialog(vm: VaultViewModel, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val error by vm.error.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Restore backup") }, text = {
        Column {
            OutlinedTextField(path, { path = it }, label = { Text("Backup file path (.lyvtbackup)") }, singleLine = true)
            OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.vault_recovery)) }, singleLine = true)
            PinField(pin, { pin = it }, "New PIN")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = pin.length >= 6 && code.isNotBlank() && path.isNotBlank(), onClick = { vm.restore(path.trim(), code, pin); onDismiss() }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun LockedView(vm: VaultViewModel) {
    var pin by remember { mutableStateOf("") }
    var recover by remember { mutableStateOf(false) }
    val error by vm.error.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    val bio = vm.repo.biometricEnrolled() && biometricAvailable(ctx)
    fun tryBio() {
        val c = vm.repo.biometricCipher(forEnroll = false) ?: run { vm.error.value = "Biometric key changed — use your PIN"; return }
        activity?.let { promptBiometric(it, c, "Unlock vault", vm::biometricUnlock) { m -> vm.error.value = m } }
    }
    LaunchedEffect(Unit) { if (bio) tryBio() }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IconBadge(Icons.Filled.Lock, MaterialTheme.colorScheme.primary, 80.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.vault_locked), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        PinField(pin, { pin = it }, stringResource(R.string.vault_pin))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(enabled = pin.length >= 4, onClick = { vm.unlock(pin); pin = "" }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(stringResource(R.string.vault_unlock)) }
        if (bio) OutlinedButton(onClick = ::tryBio, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.vault_biometric))
        }
        TextButton(onClick = { recover = true }) { Text("Forgot PIN? Use recovery code") }
    }
    if (recover) {
        var code by remember { mutableStateOf("") }
        var np by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { recover = false }, title = { Text("Reset PIN") }, text = {
            Column {
                OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.vault_recovery)) }, singleLine = true)
                Spacer(Modifier.height(8.dp)); PinField(np, { np = it }, "New PIN")
            }
        }, confirmButton = { TextButton(enabled = np.length >= 6 && code.isNotBlank(), onClick = { vm.recover(code, np); recover = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { recover = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnlockedView(nav: NavHostController, vm: VaultViewModel, s: VaultState.Unlocked, snackbar: SnackbarHostState) {
    var folder by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val visible = s.entries.filter { (folder.isEmpty() || it.folder == folder) && (query.isBlank() || it.name.contains(query, true)) }
    val sel = s.entries.filter { it.id in selected }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(14.dp, 0.dp, 14.dp, 100.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected.isNotEmpty()) {
                            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, null) }
                            Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        } else Text(stringResource(R.string.nav_vault), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.lock() }) { Icon(Icons.Filled.Lock, "Lock now") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                if (selected.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text("Export (decrypt) to Downloads") }, onClick = { vm.export(sel); selected = emptySet(); menu = false })
                                    DropdownMenuItem(text = { Text("Move to folder") }, onClick = { dialog = "move"; menu = false })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { dialog = "delete"; menu = false })
                                } else {
                                    DropdownMenuItem(text = { Text("New folder") }, onClick = { dialog = "folder"; menu = false })
                                    if (biometricAvailable(ctx)) DropdownMenuItem(text = { Text(if (vm.repo.biometricEnrolled()) "Disable biometric unlock" else "Enable biometric unlock") }, onClick = {
                                        menu = false
                                        if (vm.repo.biometricEnrolled()) vm.repo.disableBiometric()
                                        else vm.repo.biometricCipher(forEnroll = true)?.let { c -> (ctx as? FragmentActivity)?.let { promptBiometric(it, c, "Enable biometric unlock", vm::biometricEnroll) { } } }
                                    })
                                    DropdownMenuItem(text = { Text("Change PIN") }, onClick = { dialog = "pin"; menu = false })
                                    DropdownMenuItem(text = { Text("Encrypted backup") }, onClick = { vm.backup(); menu = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Search vault") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                    Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("All", folder.isEmpty()) { folder = "" }
                        s.folders.forEach { f -> Pill(f, folder == f) { folder = f } }
                    }
                }
            }
            if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Icons.Outlined.Lock, "Nothing here yet", "Tap “Add files” to encrypt photos, videos or documents into the vault.")
            }
            items(visible, key = { it.id }) { e ->
                VaultTile(e, e.id in selected, vm, onClick = {
                    if (selected.isNotEmpty()) selected = if (e.id in selected) selected - e.id else selected + e.id
                    else scope.launch {
                        runCatching { vm.openForViewing(e) }.onSuccess { f -> if (!nav.openFile(f.absolutePath)) snackbar.showSnackbar("No built-in viewer for this type — export it to open elsewhere") }
                            .onFailure { snackbar.showSnackbar("Could not decrypt: ${it.message}") }
                    }
                }, onLong = { selected = selected + e.id })
            }
        }
        ExtendedFloatingActionButton(onClick = { dialog = "import" }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.vault_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
    }
    when (dialog) {
        "folder" -> TextInputDialog("New vault folder", label = "Name", onConfirm = vm::createFolder, onDismiss = { dialog = null })
        "pin" -> TextInputDialog("New PIN", numeric = true, password = true, validate = { if (it.length < 6 || !it.all(Char::isDigit)) "At least 6 digits" else null },
            onConfirm = vm::changePin, onDismiss = { dialog = null })
        "move" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Move to") }, text = {
            Column { (listOf("") + s.folders).forEach { f -> TextButton(onClick = { vm.moveTo(sel, f); selected = emptySet(); dialog = null }) { Text(f.ifEmpty { "No folder" }) } } }
        }, confirmButton = {})
        "delete" -> com.lyvt.files.ui.ConfirmDialog("Delete ${sel.size} item(s) from vault?", "The encrypted copies will be removed. This cannot be undone.",
            stringResource(R.string.delete), destructive = true, onConfirm = { vm.delete(sel); selected = emptySet() }, onDismiss = { dialog = null })
        "import" -> ImportDialog(folder, vm) { dialog = null }
    }
}

@Composable
private fun ImportDialog(folder: String, vm: VaultViewModel, onDismiss: () -> Unit) {
    // Simple in-app picker: choose files from a folder path using the explorer's listing.
    var dir by remember { mutableStateOf(java.io.File(android.os.Environment.getExternalStorageDirectory(), "DCIM")) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var deleteOriginals by remember { mutableStateOf(false) }
    val children = remember(dir) { dir.listFiles()?.filterNot { it.name.startsWith(".") }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to vault") }, text = {
        Column {
            Text(dir.absolutePath.removePrefix(android.os.Environment.getExternalStorageDirectory().absolutePath).ifEmpty { "/" }, style = MaterialTheme.typography.labelMedium)
            Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                dir.parentFile?.takeIf { it.canRead() }?.let { p -> TextButton(onClick = { dir = p }) { Text("↑ ..") } }
                children.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.isDirectory) TextButton(onClick = { dir = f }) { Text("📁 ${f.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        else {
                            Checkbox(f.absolutePath in picked, { picked = if (it) picked + f.absolutePath else picked - f.absolutePath })
                            Text("${f.name} · ${Formatters.size(f.length())}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deleteOriginals, { deleteOriginals = it })
                Text("Delete originals after encryption", style = MaterialTheme.typography.bodySmall)
            }
            if (deleteOriginals) Text("Note: on flash storage, deleted data may remain physically recoverable until overwritten. Lyvt cannot guarantee secure erasure.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { vm.import(picked.toList(), folder, deleteOriginals); onDismiss() }) { Text("Encrypt ${picked.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultTile(e: VaultEntry, selected: Boolean, vm: VaultViewModel, onClick: () -> Unit, onLong: () -> Unit) {
    val thumb by produceState<android.graphics.Bitmap?>(null, e.id) { value = vm.thumbnail(e) }
    Column(Modifier.padding(5.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(e.category.color().copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Icon(e.category.icon(), null, tint = e.category.color(), modifier = Modifier.size(36.dp))
            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(e.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text(Formatters.size(e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
