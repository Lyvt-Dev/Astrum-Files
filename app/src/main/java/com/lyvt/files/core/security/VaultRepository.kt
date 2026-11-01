package com.lyvt.files.core.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Size
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileOperations
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class VaultEntry(
    val id: String,
    val name: String,
    val folder: String,
    val size: Long,
    val addedAt: Long,
    val hasThumb: Boolean,
) {
    val category: FileCategory get() = FileCategory.of(name.substringAfterLast('.', ""))
}

sealed interface VaultState {
    data object NotSetUp : VaultState
    data object Locked : VaultState
    data class Unlocked(val entries: List<VaultEntry>, val folders: List<String>) : VaultState
}

/**
 * Encrypted vault.
 *  - A random 256-bit master key (MK) encrypts every file, thumbnail and the metadata index (AES-256-GCM).
 *  - MK is stored only in wrapped form:
 *      pin.wrap      = GCM(PBKDF2(PIN, salt), MK), then additionally sealed by a non-exportable Android Keystore
 *                      key (device binding: copying the vault folder to another device is useless).
 *      recovery.wrap = GCM(PBKDF2(recoveryCode, salt2), MK) — the user-held recovery code (shown once).
 *      bio.wrap      = MK sealed by a Keystore key that requires biometric authentication per use.
 *  - Losing both PIN and recovery code makes the data permanently unrecoverable — by design.
 *  - Failed PIN attempts trigger exponential back-off.
 */
@Singleton
class VaultRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val root = File(context.filesDir, "vault").apply { mkdirs() }
    private val blobs = File(root, "blobs").apply { mkdirs() }
    private val tmp = File(context.cacheDir, "vault-view")
    private val meta = File(root, "meta.json") // non-secret parameters (salts, iteration count)
    private val prefs = context.getSharedPreferences("vault_guard", Context.MODE_PRIVATE)

    private var masterKey: SecretKey? = null
    private val _state = MutableStateFlow<VaultState>(if (isSetUp()) VaultState.Locked else VaultState.NotSetUp)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    fun isSetUp() = File(root, "pin.wrap").exists()
    fun biometricEnrolled() = File(root, "bio.wrap").exists()

    // ---------- Keystore ----------
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    private fun deviceKey(): SecretKey {
        (keyStore.getKey(DEVICE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(DEVICE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun biometricKey(create: Boolean): SecretKey? {
        (keyStore.getKey(BIO_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val b = KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        gen.init(b.build())
        return gen.generateKey()
    }

    private fun ksSeal(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(data)
    }

    private fun ksOpen(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    // ---------- Setup / unlock ----------
    /** Creates the vault and returns the one-time recovery code the user must store safely. */
    suspend fun setUp(pin: CharArray): String = withContext(Dispatchers.Default) {
        require(pin.size >= 4) { "PIN must have at least 4 digits" }
        val mk = VaultCipher.newKey()
        val pinSalt = VaultCipher.randomBytes(16)
        val recSalt = VaultCipher.randomBytes(16)
        val recovery = generateRecoveryCode()
        val pinWrap = VaultCipher.seal(VaultCipher.deriveKey(pin, pinSalt), mk.encoded)
        File(root, "pin.wrap").writeBytes(ksSeal(deviceKey(), pinWrap))
        File(root, "recovery.wrap").writeBytes(VaultCipher.seal(VaultCipher.deriveKey(normalizeCode(recovery), recSalt), mk.encoded))
        meta.writeText(JSONObject().put("v", 1).put("pinSalt", b64(pinSalt)).put("recSalt", b64(recSalt))
            .put("iter", VaultCipher.PBKDF2_ITERATIONS).toString())
        masterKey = mk
        writeIndex(emptyList(), listOf())
        publish()
        recovery
    }

    fun lockoutRemainingMs(): Long = (prefs.getLong("lock_until", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    suspend fun unlockWithPin(pin: CharArray): Result<Unit> = withContext(Dispatchers.Default) {
        if (lockoutRemainingMs() > 0) return@withContext Result.failure(IOException("Too many attempts. Try again in ${lockoutRemainingMs() / 1000 + 1}s"))
        runCatching {
            val m = JSONObject(meta.readText())
            val wrapped = ksOpen(deviceKey(), File(root, "pin.wrap").readBytes())
            val raw = VaultCipher.open(VaultCipher.deriveKey(pin, unb64(m.getString("pinSalt")), m.getInt("iter")), wrapped)
            masterKey = SecretKeySpec(raw, "AES")
            prefs.edit().putInt("fails", 0).apply()
            publish()
        }.onFailure {
            val fails = prefs.getInt("fails", 0) + 1
            val wait = if (fails >= 5) (30_000L shl (fails - 5).coerceAtMost(6)) else 0L
            prefs.edit().putInt("fails", fails).putLong("lock_until", System.currentTimeMillis() + wait).apply()
        }.recoverCatching { throw IOException("Incorrect PIN") }
    }

    suspend fun recover(code: String, newPin: CharArray): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val m = JSONObject(meta.readText())
            val raw = VaultCipher.open(VaultCipher.deriveKey(normalizeCode(code), unb64(m.getString("recSalt")), m.getInt("iter")),
                File(root, "recovery.wrap").readBytes())
            masterKey = SecretKeySpec(raw, "AES")
            changePinInternal(newPin)
            publish()
        }.recoverCatching { throw IOException("Recovery code is not valid") }
    }

    suspend fun changePin(newPin: CharArray) = withContext(Dispatchers.Default) { changePinInternal(newPin) }

    private fun changePinInternal(newPin: CharArray) {
        val mk = masterKey ?: throw IOException("Vault is locked")
        require(newPin.size >= 4)
        val m = JSONObject(meta.readText())
        val salt = VaultCipher.randomBytes(16)
        File(root, "pin.wrap").writeBytes(ksSeal(deviceKey(), VaultCipher.seal(VaultCipher.deriveKey(newPin, salt), mk.encoded)))
        meta.writeText(m.put("pinSalt", b64(salt)).toString())
    }

    /** Cipher for BiometricPrompt.CryptoObject: encrypt mode when enrolling, decrypt mode when unlocking. */
    fun biometricCipher(forEnroll: Boolean): Cipher? = try {
        val key = biometricKey(create = forEnroll) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (forEnroll) c.init(Cipher.ENCRYPT_MODE, key) else {
            val blob = File(root, "bio.wrap").takeIf { it.exists() }?.readBytes() ?: return null
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        }
        c
    } catch (e: KeyPermanentlyInvalidatedException) {
        disableBiometric(); null
    }

    fun completeBiometricEnroll(cipher: Cipher) {
        val mk = masterKey ?: return
        File(root, "bio.wrap").writeBytes(cipher.iv + cipher.doFinal(mk.encoded))
    }

    fun completeBiometricUnlock(cipher: Cipher): Result<Unit> = runCatching {
        val blob = File(root, "bio.wrap").readBytes()
        masterKey = SecretKeySpec(cipher.doFinal(blob, 12, blob.size - 12), "AES")
        publish()
    }

    fun disableBiometric() {
        File(root, "bio.wrap").delete()
        runCatching { keyStore.deleteEntry(BIO_ALIAS) }
    }

    fun lock() {
        masterKey = null
        tmp.deleteRecursively()
        if (isSetUp()) _state.value = VaultState.Locked
    }

    fun isUnlocked() = masterKey != null

    // ---------- Index ----------
    private fun requireKey() = masterKey ?: throw IOException("Vault is locked")

    private fun readIndex(): Pair<List<VaultEntry>, List<String>> {
        val f = File(root, "index.bin")
        if (!f.exists()) return emptyList<VaultEntry>() to emptyList()
        val o = JSONObject(String(VaultCipher.open(requireKey(), f.readBytes(), "index".toByteArray())))
        val arr = o.getJSONArray("entries")
        val entries = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            VaultEntry(e.getString("id"), e.getString("name"), e.optString("folder"), e.getLong("size"), e.getLong("added"), e.optBoolean("thumb"))
        }
        val fa = o.optJSONArray("folders") ?: JSONArray()
        return entries to (0 until fa.length()).map { fa.getString(it) }
    }

    private fun writeIndex(entries: List<VaultEntry>, folders: List<String>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("folder", it.folder).put("size", it.size).put("added", it.addedAt).put("thumb", it.hasThumb)) }
        val bytes = VaultCipher.seal(requireKey(), JSONObject().put("entries", arr).put("folders", JSONArray(folders)).toString().toByteArray(), "index".toByteArray())
        val t = File(root, "index.bin.tmp"); t.writeBytes(bytes)
        if (!t.renameTo(File(root, "index.bin"))) throw IOException("Could not save vault index")
    }

    private fun publish() {
        val (e, f) = readIndex()
        _state.value = VaultState.Unlocked(e.sortedByDescending { it.addedAt }, f.sorted())
    }

    // ---------- Content ----------
    suspend fun import(sources: List<File>, folder: String, deleteOriginals: Boolean, onProgress: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val key = requireKey()
        val (entries, folders) = readIndex()
        val added = mutableListOf<VaultEntry>()
        val imported = mutableListOf<File>()
        sources.filter { it.isFile }.forEachIndexed { i, src ->
            val id = UUID.randomUUID().toString()
            val out = File(blobs, "$id.bin")
            src.inputStream().use { inp -> out.outputStream().use { VaultCipher.encryptStream(key, inp, it) } }
            val thumb = makeThumb(src)?.let { bytes -> File(blobs, "$id.thumb").writeBytes(VaultCipher.seal(key, bytes, id.toByteArray())); true } ?: false
            added += VaultEntry(id, src.name, folder, src.length(), System.currentTimeMillis(), thumb)
            imported += src
            onProgress(i + 1)
        }
        writeIndex(entries + added, folders)
        if (deleteOriginals) imported.forEach { it.delete() }
        publish()
        added.size
    }

    private fun makeThumb(src: File): ByteArray? = runCatching {
        val bmp: Bitmap? = when (FileCategory.of(src.extension)) {
            FileCategory.IMAGE -> ThumbnailUtils.createImageThumbnail(src, Size(320, 320), null)
            FileCategory.VIDEO -> ThumbnailUtils.createVideoThumbnail(src, Size(320, 320), null)
            else -> null
        }
        bmp?.let { ByteArrayOutputStream().also { o -> it.compress(Bitmap.CompressFormat.JPEG, 80, o) }.toByteArray() }
    }.getOrNull()

    suspend fun thumbnail(entry: VaultEntry): Bitmap? = withContext(Dispatchers.IO) {
        if (!entry.hasThumb) return@withContext null
        runCatching {
            val b = VaultCipher.open(requireKey(), File(blobs, "${entry.id}.thumb").readBytes(), entry.id.toByteArray())
            BitmapFactory.decodeByteArray(b, 0, b.size)
        }.getOrNull()
    }

    /** Decrypts into app-private cache (never public storage) for viewing; wiped on lock. */
    suspend fun decryptForViewing(entry: VaultEntry): File = withContext(Dispatchers.IO) {
        tmp.mkdirs()
        val out = File(tmp, "${entry.id}.${entry.name.substringAfterLast('.', "bin")}")
        if (!out.exists()) {
            val part = File(tmp, out.name + ".part")
            File(blobs, "${entry.id}.bin").inputStream().use { i -> part.outputStream().use { VaultCipher.decryptStream(requireKey(), i, it) } }
            part.renameTo(out)
        }
        out
    }

    suspend fun readSmall(entry: VaultEntry, max: Long = 40L * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        if (entry.size > max) throw IOException("File too large for in-memory preview")
        val bos = ByteArrayOutputStream()
        File(blobs, "${entry.id}.bin").inputStream().use { VaultCipher.decryptStream(requireKey(), it, bos) }
        bos.toByteArray()
    }

    suspend fun export(entries: List<VaultEntry>, destDir: File): Int = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        var n = 0
        entries.forEach { e ->
            val target = FileOperations.uniqueTarget(destDir, e.name)
            File(blobs, "${e.id}.bin").inputStream().use { i -> target.outputStream().use { VaultCipher.decryptStream(requireKey(), i, it) } }
            n++
        }
        n
    }

    suspend fun delete(entries: List<VaultEntry>) = withContext(Dispatchers.IO) {
        val (all, folders) = readIndex()
        val ids = entries.map { it.id }.toSet()
        ids.forEach { File(blobs, "$it.bin").delete(); File(blobs, "$it.thumb").delete() }
        writeIndex(all.filterNot { it.id in ids }, folders)
        publish()
    }

    suspend fun createFolder(name: String) = withContext(Dispatchers.IO) {
        val (all, folders) = readIndex()
        if (name.isNotBlank() && name !in folders) writeIndex(all, folders + name.trim())
        publish()
    }

    suspend fun moveTo(entries: List<VaultEntry>, folder: String) = withContext(Dispatchers.IO) {
        val (all, folders) = readIndex()
        val ids = entries.map { it.id }.toSet()
        writeIndex(all.map { if (it.id in ids) it.copy(folder = folder) else it }, folders)
        publish()
    }

    /**
     * Encrypted backup: a single file containing the recovery-wrapped key, salts and all encrypted blobs.
     * It can only be restored with the recovery code — not with the device-bound PIN wrap.
     */
    suspend fun backup(dest: File) = withContext(Dispatchers.IO) {
        requireKey()
        java.util.zip.ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            fun add(name: String, f: File) { if (f.exists()) { zip.putNextEntry(java.util.zip.ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() } }
            add("meta.json", meta); add("recovery.wrap", File(root, "recovery.wrap")); add("index.bin", File(root, "index.bin"))
            blobs.listFiles()?.forEach { add("blobs/${it.name}", it) }
        }
    }

    suspend fun restoreBackup(src: File, recoveryCode: String, newPin: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File(context.filesDir, "vault-restore").apply { deleteRecursively(); mkdirs() }
            java.util.zip.ZipInputStream(src.inputStream().buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val allowed = e.name in setOf("meta.json", "recovery.wrap", "index.bin") || Regex("blobs/[0-9a-f-]{36}\\.(bin|thumb)").matches(e.name)
                    if (allowed) File(staging, e.name).apply { parentFile?.mkdirs() }.outputStream().use { zip.copyTo(it) }
                    e = zip.nextEntry
                }
            }
            val m = JSONObject(File(staging, "meta.json").readText())
            val raw = VaultCipher.open(VaultCipher.deriveKey(normalizeCode(recoveryCode), unb64(m.getString("recSalt")), m.getInt("iter")),
                File(staging, "recovery.wrap").readBytes())
            masterKey = SecretKeySpec(raw, "AES")
            readIndexFrom(File(staging, "index.bin")) // verify before replacing
            root.deleteRecursively(); staging.renameTo(root); blobs.mkdirs()
            changePinInternal(newPin)
            disableBiometric()
            publish()
        }.recoverCatching { throw IOException("Restore failed: ${it.message}") }
    }

    private fun readIndexFrom(f: File) { VaultCipher.open(requireKey(), f.readBytes(), "index".toByteArray()) }

    private fun generateRecoveryCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val b = VaultCipher.randomBytes(24)
        return b.map { alphabet[(it.toInt() and 0xFF) % alphabet.length] }.chunked(4).joinToString("-") { it.joinToString("") }
    }

    private fun normalizeCode(c: String) = c.uppercase().filter { it.isLetterOrDigit() }.toCharArray()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val DEVICE_ALIAS = "lyvt_vault_device"
        private const val BIO_ALIAS = "lyvt_vault_bio"
    }
}
