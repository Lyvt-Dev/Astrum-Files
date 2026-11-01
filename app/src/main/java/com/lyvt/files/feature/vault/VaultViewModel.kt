package com.lyvt.files.feature.vault

import android.graphics.Bitmap
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyvt.files.core.security.VaultEntry
import com.lyvt.files.core.security.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.crypto.Cipher
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(val repo: VaultRepository) : ViewModel() {
    val state = repo.state
    val busy = MutableStateFlow<String?>(null)
    val recoveryCode = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private val thumbs = HashMap<String, Bitmap?>()

    fun setUp(pin: String) = viewModelScope.launch {
        busy.value = "Creating encrypted vault…"
        runCatching { repo.setUp(pin.toCharArray()) }.onSuccess { recoveryCode.value = it }.onFailure { error.value = it.message }
        busy.value = null
    }

    fun unlock(pin: String) = viewModelScope.launch {
        busy.value = "Unlocking…"
        repo.unlockWithPin(pin.toCharArray()).onFailure { error.value = it.message }.onSuccess { error.value = null }
        busy.value = null
    }

    fun recover(code: String, newPin: String) = viewModelScope.launch {
        busy.value = "Verifying recovery code…"
        repo.recover(code, newPin.toCharArray()).onFailure { error.value = it.message }.onSuccess { _messages.emit("PIN reset") }
        busy.value = null
    }

    fun biometricUnlock(c: Cipher) { repo.completeBiometricUnlock(c).onFailure { error.value = "Biometric unlock failed" } }
    fun biometricEnroll(c: Cipher) { repo.completeBiometricEnroll(c); viewModelScope.launch { _messages.emit("Biometric unlock enabled") } }

    fun lock() = repo.lock()

    fun import(paths: List<String>, folder: String, deleteOriginals: Boolean) = viewModelScope.launch {
        busy.value = "Encrypting ${paths.size} file(s)…"
        runCatching { repo.import(paths.map(::File), folder, deleteOriginals) { busy.value = "Encrypting $it / ${paths.size}…" } }
            .onSuccess { _messages.emit("Added $it file(s) to the vault" + if (deleteOriginals) " and removed originals" else "") }
            .onFailure { _messages.emit("Import failed: ${it.message}") }
        busy.value = null
    }

    fun export(entries: List<VaultEntry>) = viewModelScope.launch {
        busy.value = "Decrypting…"
        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Lyvt Vault Export")
        runCatching { repo.export(entries, dest) }.onSuccess { _messages.emit("Exported $it file(s) to Download/Lyvt Vault Export") }.onFailure { _messages.emit("Export failed: ${it.message}") }
        busy.value = null
    }

    fun delete(entries: List<VaultEntry>) = viewModelScope.launch { repo.delete(entries); _messages.emit("Deleted ${entries.size} item(s) from vault") }
    fun createFolder(name: String) = viewModelScope.launch { repo.createFolder(name) }
    fun moveTo(entries: List<VaultEntry>, folder: String) = viewModelScope.launch { repo.moveTo(entries, folder) }
    fun setUpDecoy(pin: String) = viewModelScope.launch { runCatching { repo.setUpDecoy(pin.toCharArray()) }.onSuccess { _messages.emit("Decoy vault created. Unlocking with that PIN opens an empty, separate vault.") }.onFailure { _messages.emit(it.message ?: "Failed") } }
    fun removeDecoy() { repo.removeDecoy(); viewModelScope.launch { _messages.emit("Decoy vault removed") } }
    fun changePin(pin: String) = viewModelScope.launch { runCatching { repo.changePin(pin.toCharArray()) }.onSuccess { _messages.emit("PIN changed") } }

    fun backup() = viewModelScope.launch {
        busy.value = "Writing encrypted backup…"
        val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "lyvt-vault-${System.currentTimeMillis()}.lyvtbackup")
        runCatching { f.parentFile?.mkdirs(); repo.backup(f) }.onSuccess { _messages.emit("Backup saved to Documents/${f.name}. Restore requires your recovery code.") }
            .onFailure { _messages.emit("Backup failed: ${it.message}") }
        busy.value = null
    }

    fun restore(path: String, code: String, pin: String) = viewModelScope.launch {
        busy.value = "Restoring backup…"
        repo.restoreBackup(File(path), code, pin.toCharArray()).onSuccess { _messages.emit("Vault restored") }.onFailure { error.value = it.message }
        busy.value = null
    }

    suspend fun thumbnail(e: VaultEntry): Bitmap? = thumbs.getOrPut(e.id) { repo.thumbnail(e) }
    suspend fun openForViewing(e: VaultEntry): File = repo.decryptForViewing(e)
}
