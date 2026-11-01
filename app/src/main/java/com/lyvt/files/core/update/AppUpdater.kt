package com.lyvt.files.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lyvt.files.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Automatic updates from GitHub Releases. Checks at most once a day (if enabled); installing always needs the user's tap. */
@Singleton
class AppUpdater @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    /** Shown as a dialog when an automatic check finds something (cleared on dismiss). */
    val prompt = MutableStateFlow<UpdateInfo?>(null)

    var autoCheck: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) { prefs.edit().putBoolean("auto", v).apply() }

    fun checkIfDue() {
        if (!autoCheck || BuildConfig.DEBUG) return
        if (System.currentTimeMillis() - prefs.getLong("last", 0) < 24 * 3600_000L) return
        check(auto = true)
    }

    fun check(auto: Boolean = false) {
        if (state.value is UpdateState.Checking || state.value is UpdateState.Downloading) return
        state.value = UpdateState.Checking
        scope.launch {
            runCatching { UpdateCheck.latest("android-v", ".apk", BuildConfig.VERSION_NAME) }
                .onSuccess { info ->
                    prefs.edit().putLong("last", System.currentTimeMillis()).apply()
                    state.value = if (info == null) UpdateState.UpToDate else UpdateState.Available(info)
                    if (info != null && auto && prefs.getString("skipped", null) != info.version) prompt.value = info
                }
                .onFailure { state.value = if (auto) UpdateState.Idle else UpdateState.Failed(it.message ?: "No connection") }
        }
    }

    fun skip(info: UpdateInfo) { prefs.edit().putString("skipped", info.version).apply(); prompt.value = null }

    fun canInstall() = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
    fun installPermissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun downloadAndInstall(info: UpdateInfo) {
        prompt.value = null
        if (!canInstall()) { ctx.startActivity(installPermissionIntent()); state.value = UpdateState.Available(info); return }
        scope.launch {
            runCatching {
                val dir = File(ctx.cacheDir, "share/updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                UpdateCheck.download(info, File(dir, "AstrumFiles-${info.version}.apk")) { d, t -> state.value = UpdateState.Downloading(info, d, t) }
            }.onSuccess { apk ->
                state.value = UpdateState.Available(info)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { state.value = UpdateState.Failed(it.message ?: "Download failed") }
        }
    }
}
