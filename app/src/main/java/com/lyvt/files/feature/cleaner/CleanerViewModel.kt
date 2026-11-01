package com.lyvt.files.feature.cleaner

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyvt.files.core.scan.DuplicateFinder
import com.lyvt.files.core.scan.ImageAnalysis
import com.lyvt.files.core.scan.ScanCache
import com.lyvt.files.core.scan.clusterByHash
import com.lyvt.files.core.settings.SettingsRepository
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.core.storage.FileRepository
import com.lyvt.files.core.storage.OpResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

enum class CleanerMode { DUPLICATES, SIMILAR, BLURRY, LARGE, OLD, EMPTY, DOWNLOADS, SCREENSHOTS, ARCHIVES, APPS }

data class CleanGroup(val title: String, val items: List<FileItem>, val note: String? = null)
data class AppCandidate(val packageName: String, val label: String, val lastUsed: Long?, val installed: Long)

data class CleanerUi(
    val mode: CleanerMode? = null,
    val running: Boolean = false,
    val progress: String = "",
    /** Fraction of the current analysis phase (comparing / hashing photos); null while walking storage. */
    val phaseFraction: Float? = null,
    val phaseTitle: String = "",
    val groups: List<CleanGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
    val apps: List<AppCandidate> = emptyList(),
    val appsPermissionMissing: Boolean = false,
    val largeThresholdMb: Int = 100,
    val oldMonths: Int = 12,
    val unusedDays: Int = 30,
    val similarity: Int = 8,
    val blurThreshold: Int = 60,
    val lastScanSummary: String? = null,
) {
    val selectedBytes: Long get() = groups.flatMap { it.items }.distinctBy { it.path }.filter { it.path in selected }.sumOf { it.size }
}

@HiltViewModel
class CleanerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: ScanCache,
    private val repo: FileRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(CleanerUi())
    val ui = _ui.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    val scanProgress = cache.progress
    private var job: Job? = null

    fun open(mode: CleanerMode?) { job?.cancel(); _ui.update { it.copy(mode = mode, groups = emptyList(), selected = emptySet(), running = false, apps = emptyList()) }; if (mode != null) run() }
    fun cancel() { job?.cancel(); _ui.update { it.copy(running = false, progress = "Cancelled") } }
    fun setLarge(mb: Int) { _ui.update { it.copy(largeThresholdMb = mb) } }
    fun setOld(m: Int) { _ui.update { it.copy(oldMonths = m) } }
    fun setUnusedDays(d: Int) { _ui.update { it.copy(unusedDays = d) }; run() }
    /** Drops an app from the list once it is really gone (called when returning from the uninstall dialog). */
    fun refreshApps() {
        if (_ui.value.appsPermissionMissing) { run(); return } // user may have just granted Usage access
        val pm = context.packageManager
        _ui.update { u -> u.copy(apps = u.apps.filter { a -> runCatching { pm.getPackageInfo(a.packageName, 0) }.isSuccess }) }
    }
    fun setSimilarity(v: Int) { _ui.update { it.copy(similarity = v) } }
    fun setBlur(v: Int) { _ui.update { it.copy(blurThreshold = v) } }

    fun toggle(path: String) = _ui.update { it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path) }
    fun selectGroup(g: CleanGroup, keepFirst: Boolean) = _ui.update { u ->
        val paths = (if (keepFirst) g.items.drop(1) else g.items).map { it.path }
        u.copy(selected = u.selected - g.items.map { it.path }.toSet() + paths)
    }

    private suspend fun protectedPaths(): List<String> = settingsRepo.current().protectedDirs.map { File(Environment.getExternalStorageDirectory(), it).absolutePath }

    fun run(force: Boolean = false) {
        val mode = _ui.value.mode ?: return
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(running = true, progress = "", phaseFraction = null, phaseTitle = "", groups = emptyList(), selected = emptySet()) }
            try {
                if (mode == CleanerMode.APPS) { scanApps(); return@launch }
                val scan = cache.get(force)
                val prot = protectedPaths()
                val files = scan.allFiles.filterNot { f -> prot.any { f.absolutePath.startsWith(it) } }
                val groups: List<CleanGroup> = when (mode) {
                    CleanerMode.DUPLICATES -> {
                        val d = DuplicateFinder.find(files, minSize = 1024) { done, total -> _ui.update { it.copy(phaseTitle = "Comparing files", progress = "$done of $total candidates hashed", phaseFraction = if (total > 0) done.toFloat() / total else null) } }
                        d.map { g -> CleanGroup("${g.files.size} copies · ${com.lyvt.files.core.common.Formatters.size(g.size)} each", g.files.map { FileItem.from(it) },
                            "SHA-256 ${g.hash.take(12)}…") }.also { gs -> _ui.update { u -> u.copy(selected = DuplicateFinder.defaultSelection(d)) } }
                    }
                    CleanerMode.SIMILAR -> {
                        val imgs = files.filter { FileCategory.of(it.extension) == FileCategory.IMAGE && it.length() > 20_000 }.take(4000)
                        val hashed = imgs.mapIndexedNotNull { i, f ->
                            currentCoroutineContext().ensureActive()
                            if (i % 10 == 0) _ui.update { it.copy(phaseTitle = "Analysing photos", progress = "$i of ${imgs.size} photos", phaseFraction = i.toFloat() / imgs.size.coerceAtLeast(1)) }
                            ImageAnalysis.dHash(f)?.let { f to it }
                        }
                        clusterByHash(hashed, _ui.value.similarity).map { g ->
                            val items = g.sortedByDescending { it.length() }.map { FileItem.from(it) }
                            val exact = items.map { it.size }.distinct().size == 1
                            CleanGroup("${items.size} similar photos", items, if (exact) "May be exact duplicates — same size" else "Visually similar (perceptual hash)")
                        }
                    }
                    CleanerMode.BLURRY -> {
                        val imgs = files.filter { FileCategory.of(it.extension) == FileCategory.IMAGE && it.length() > 20_000 }.take(3000)
                        val blurry = imgs.mapIndexedNotNull { i, f ->
                            currentCoroutineContext().ensureActive()
                            if (i % 10 == 0) _ui.update { it.copy(phaseTitle = "Measuring sharpness", progress = "$i of ${imgs.size} photos", phaseFraction = i.toFloat() / imgs.size.coerceAtLeast(1)) }
                            ImageAnalysis.sharpness(f)?.takeIf { it < _ui.value.blurThreshold }?.let { f to it }
                        }.sortedBy { it.second }
                        if (blurry.isEmpty()) emptyList() else listOf(CleanGroup("${blurry.size} possibly blurry photos", blurry.map { FileItem.from(it.first) },
                            "Estimated with Laplacian variance — artistic shots or low-detail scenes may appear here"))
                    }
                    CleanerMode.LARGE -> {
                        val min = _ui.value.largeThresholdMb * 1024L * 1024
                        files.filter { it.length() >= min }.sortedByDescending { it.length() }.map { FileItem.from(it) }
                            .groupBy { it.category }.map { (c, l) -> CleanGroup("${c.name.lowercase().replaceFirstChar(Char::uppercase)} · ${l.size}", l) }
                    }
                    CleanerMode.OLD -> {
                        val cutoff = System.currentTimeMillis() - _ui.value.oldMonths * 30L * 86_400_000
                        val old = files.filter { it.lastModified() in 1 until cutoff && it.length() > 0 }.sortedByDescending { it.length() }.take(2000).map { FileItem.from(it) }
                        if (old.isEmpty()) emptyList() else listOf(CleanGroup("${old.size} files not modified in ${_ui.value.oldMonths} months", old,
                            "Based on modification date — Android does not reliably expose last-access time"))
                    }
                    CleanerMode.EMPTY -> {
                        val empties = scan.emptyFolders.map(::File).filter { it.exists() && (it.list()?.isEmpty() == true) }.map { FileItem.from(it) }
                        if (empties.isEmpty()) emptyList() else listOf(CleanGroup("${empties.size} empty folders", empties))
                    }
                    CleanerMode.DOWNLOADS -> {
                        val dl = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).absolutePath
                        val inDl = files.filter { it.absolutePath.startsWith(dl) }
                        val apks = inDl.filter { it.extension.equals("apk", true) }
                        val temp = inDl.filter { it.extension.lowercase() in setOf("tmp", "part", "crdownload", "partial", "download") || it.name.endsWith(".lyvtpart") }
                        val oldDl = inDl.filter { System.currentTimeMillis() - it.lastModified() > 90L * 86_400_000 } - apks.toSet() - temp.toSet()
                        listOfNotNull(
                            apks.takeIf { it.isNotEmpty() }?.let { CleanGroup("Installation packages (APK)", it.map { f -> FileItem.from(f) }, "Usually no longer needed after installing") },
                            temp.takeIf { it.isNotEmpty() }?.let { CleanGroup("Incomplete / temporary downloads", it.map { f -> FileItem.from(f) }) },
                            oldDl.takeIf { it.isNotEmpty() }?.let { CleanGroup("Downloads older than 90 days", it.sortedByDescending { f -> f.length() }.map { f -> FileItem.from(f) }) },
                        )
                    }
                    CleanerMode.SCREENSHOTS -> {
                        val shots = files.filter { f -> f.parentFile?.name.equals("Screenshots", true) || f.name.startsWith("Screenshot", true) }
                            .filter { FileCategory.of(it.extension) == FileCategory.IMAGE }.sortedByDescending { it.lastModified() }
                        val fmt = DateFormat.getDateInstance(DateFormat.LONG)
                        shots.groupBy { fmt.format(Date(it.lastModified())) }.map { (d, l) -> CleanGroup("$d · ${l.size}", l.map { FileItem.from(it) }) }
                    }
                    CleanerMode.ARCHIVES -> {
                        // An archive is "redundant" when a folder with the same base name sits next to it (already extracted)
                        // and that folder holds at least as many bytes as the archive.
                        val archives = files.filter { FileCategory.of(it.extension) == FileCategory.ARCHIVE }
                        val extracted = archives.filter { a ->
                            val base = a.name.removeSuffix(".tar.gz").removeSuffix(".tar.bz2").substringBeforeLast('.')
                            val dir = File(a.parentFile, base)
                            dir.isDirectory && dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } >= a.length() / 2
                        }
                        if (extracted.isEmpty()) emptyList() else listOf(CleanGroup("${extracted.size} archives already extracted next to them", extracted.map { FileItem.from(it) },
                            "A folder with the same name exists beside each archive. Check that its contents are complete before deleting."))
                    }
                    CleanerMode.APPS -> emptyList()
                }
                _ui.update { it.copy(groups = groups, running = false, progress = "", phaseFraction = null, phaseTitle = "",
                    lastScanSummary = "Scanned ${scan.fileCount} files" + if (scan.inaccessible > 0) " · ${scan.inaccessible} folders not accessible" else "") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(running = false, progress = "Scan failed: ${e.message}") }
            }
        }
    }

    private fun scanApps() {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(now - 365L * 86_400_000, now)
        if (stats.isEmpty()) { _ui.update { it.copy(running = false, appsPermissionMissing = true) }; return }
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
            .map { ai ->
                val last = stats[ai.packageName]?.lastTimeUsed?.takeIf { it > 0 }
                AppCandidate(ai.packageName, pm.getApplicationLabel(ai).toString(), last, runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0))
            }
            // Unused = not opened within the chosen span. Apps installed inside the span are excluded (they had no chance to be used).
            .filter { val cutoff = now - _ui.value.unusedDays * 86_400_000L; (it.lastUsed ?: 0) < cutoff && it.installed < cutoff }
            .sortedBy { it.lastUsed ?: 0 }
        _ui.update { it.copy(running = false, apps = apps, appsPermissionMissing = false) }
    }

    fun deleteSelected() = viewModelScope.launch {
        val u = _ui.value
        // Safety: refuse to remove every copy of a duplicate group.
        if (u.mode == CleanerMode.DUPLICATES) {
            val full = u.groups.filter { g -> g.items.all { it.path in u.selected } }
            if (full.isNotEmpty()) { _messages.emit("Keep at least one copy in each duplicate group (${full.size} group(s) fully selected)"); return@launch }
        }
        val paths = u.selected.toList()
        val r = repo.delete(paths, settingsRepo.current().useTrash)
        _messages.emit(when (r) {
            is OpResult.Success -> "Removed ${r.count} item(s) — recoverable from Trash"
            is OpResult.Partial -> "Removed ${r.done}, ${r.failed.size} failed"
            is OpResult.Failure -> r.reason
        })
        _ui.update { s -> s.copy(groups = s.groups.map { g -> g.copy(items = g.items.filterNot { it.path in paths }) }.filter { it.items.size > if (s.mode == CleanerMode.DUPLICATES || s.mode == CleanerMode.SIMILAR) 1 else 0 }, selected = emptySet()) }
        cache.invalidate()
    }
}
