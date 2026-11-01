package com.lyvt.files

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lyvt.files.core.security.VaultRepository
import com.lyvt.files.core.settings.SettingsRepository
import com.lyvt.files.core.storage.TrashManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LyvtApp : Application(), androidx.work.Configuration.Provider {
    @Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory
    override val workManagerConfiguration get() = androidx.work.Configuration.Builder().setWorkerFactory(workerFactory).build()

    @Inject lateinit var vault: VaultRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var trash: TrashManager

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lockJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        com.lyvt.files.core.scan.DuplicateFinder.cache = com.lyvt.files.core.scan.HashCache(java.io.File(filesDir, "hash-cache.tsv"))
        // Auto-lock the vault after the app has been in background for the configured timeout.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                lockJob = appScope.launch {
                    val seconds = settings.current().vaultAutoLockSeconds
                    delay(seconds * 1000L)
                    vault.lock()
                }
            }
            override fun onStart(owner: LifecycleOwner) { lockJob?.cancel() }
        })
        com.lyvt.files.core.work.Schedules.ensure(this)
        appScope.launch(Dispatchers.IO) {
            val s = settings.current()
            runCatching { trash.enforcePolicy(s.trashRetentionDays, s.trashLimitMb) }
        }
    }
}
