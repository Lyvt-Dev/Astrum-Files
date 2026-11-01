package com.lyvt.files.feature.explorer

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyvt.files.core.archive.ArchiveManager
import com.lyvt.files.core.settings.AppSettings
import com.lyvt.files.core.settings.SettingsRepository
import com.lyvt.files.core.storage.AppClipboard
import com.lyvt.files.core.storage.BatchRename
import com.lyvt.files.core.storage.ConflictPolicy
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.core.storage.FileRepository
import com.lyvt.files.core.storage.FileSearch
import com.lyvt.files.core.storage.ListResult
import com.lyvt.files.core.storage.OpResult
import com.lyvt.files.core.storage.SearchQuery
import com.lyvt.files.core.storage.SortOrder
import com.lyvt.files.core.storage.StorageVolumes
import com.lyvt.files.core.storage.TransferProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed interface DirState {
    data object Loading : DirState
    data class Loaded(val items: List<FileItem>) : DirState
    data class Error(val kind: String) : DirState // "denied" | "missing"
}

data class Tab(val path: String, val back: List<String> = emptyList(), val forward: List<String> = emptyList())

data class OperationState(val title: String, val progress: TransferProgress?, val indeterminate: Boolean = false)

data class ExplorerUi(
    val tabs: List<Tab>,
    val activeTab: Int = 0,
    val dir: DirState = DirState.Loading,
    val selection: Set<String> = emptySet(),
    val searchText: String? = null,
    val searchResults: List<FileItem> = emptyList(),
    val searching: Boolean = false,
    val operation: OperationState? = null,
) {
    val path: String get() = tabs[activeTab].path
    val visibleItems: List<FileItem> get() = if (searchText != null) searchResults else (dir as? DirState.Loaded)?.items.orEmpty()
}

@HiltViewModel
class ExplorerViewModel @Inject constructor(
    private val repo: FileRepository,
    private val settingsRepo: SettingsRepository,
    val clipboard: AppClipboard,
    private val volumes: StorageVolumes,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    val library: com.lyvt.files.core.storage.LibraryRepository,
    private val searches: com.lyvt.files.core.database.SearchDao,
    private val organizer: com.lyvt.files.core.organizer.OrganizerRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    val rootPath = volumes.primaryRoot()
    val storageRoots get() = volumes.volumes()

    private val startPath = savedState.get<String>("path")?.let(Uri::decode)?.takeIf { it.isNotBlank() } ?: rootPath
    private val _ui = MutableStateFlow(ExplorerUi(tabs = listOf(Tab(startPath))))
    val ui: StateFlow<ExplorerUi> = _ui.asStateFlow()

    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val favorites = repo.favoriteList.map { l -> l.map { it.path }.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val searchHistory = searches.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val collections = library.allCollections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun saveSearch(q: String) = viewModelScope.launch { searches.upsert(com.lyvt.files.core.database.SearchEntity(q, saved = true)); emit("Search saved") }
    fun forgetSearch(q: String) = viewModelScope.launch { searches.delete(q) }
    fun clearSearchHistory() = viewModelScope.launch { searches.clearHistory() }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var opJob: Job? = null

    init {
        viewModelScope.launch { settingsRepo.settings.map { it.showHidden to it.sort }.collect { reload() } }
        savedState.get<String>("q")?.let(Uri::decode)?.takeIf { it.isNotBlank() }?.let { search(it) }
    }

    fun reload() {
        loadJob?.cancel()
        val path = _ui.value.path
        loadJob = viewModelScope.launch {
            if ((_ui.value.dir as? DirState.Loaded) == null) _ui.update { it.copy(dir = DirState.Loading) }
            val s = settings.value
            val r = repo.list(path, s.showHidden, s.sort)
            _ui.update { u ->
                if (u.path != path) u else u.copy(dir = when (r) {
                    is ListResult.Ok -> DirState.Loaded(r.items)
                    ListResult.AccessDenied -> DirState.Error("denied")
                    ListResult.NotFound -> DirState.Error("missing")
                }, selection = u.selection.filter { sel -> (r as? ListResult.Ok)?.items?.any { it.path == sel } == true }.toSet())
            }
        }
    }

    private fun updateTab(transform: (Tab) -> Tab) = _ui.update { u ->
        u.copy(tabs = u.tabs.mapIndexed { i, t -> if (i == u.activeTab) transform(t) else t }, selection = emptySet(), dir = DirState.Loading, searchText = null)
    }

    fun open(path: String) { if (path == _ui.value.path) return; updateTab { Tab(path, it.back + it.path, emptyList()) }; reload() }
    fun canGoBack() = _ui.value.selection.isNotEmpty() || _ui.value.searchText != null || _ui.value.tabs[_ui.value.activeTab].back.isNotEmpty()
    fun back(): Boolean {
        val u = _ui.value
        when {
            u.selection.isNotEmpty() -> _ui.update { it.copy(selection = emptySet()) }
            u.searchText != null -> closeSearch()
            u.tabs[u.activeTab].back.isNotEmpty() -> { updateTab { Tab(it.back.last(), it.back.dropLast(1), listOf(it.path) + it.forward) }; reload() }
            else -> return false
        }
        return true
    }
    fun forward() { val t = _ui.value.tabs[_ui.value.activeTab]; if (t.forward.isNotEmpty()) { updateTab { Tab(it.forward.first(), it.back + it.path, it.forward.drop(1)) }; reload() } }
    fun up() { File(_ui.value.path).parentFile?.takeIf { it.canRead() }?.let { open(it.absolutePath) } }

    fun newTab() { _ui.update { it.copy(tabs = it.tabs + Tab(it.path), activeTab = it.tabs.size, selection = emptySet()) }; reload() }
    fun selectTab(i: Int) { _ui.update { it.copy(activeTab = i, selection = emptySet(), dir = DirState.Loading, searchText = null) }; reload() }
    fun closeTab(i: Int) {
        if (_ui.value.tabs.size <= 1) return
        _ui.update { val tabs = it.tabs.filterIndexed { idx, _ -> idx != i }; it.copy(tabs = tabs, activeTab = it.activeTab.coerceAtMost(tabs.lastIndex).let { a -> if (i < it.activeTab) a - 1 else a }.coerceAtLeast(0)) }
        reload()
    }

    // Selection
    fun toggle(path: String) = _ui.update { it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path) }
    fun selectAll() = _ui.update { u -> u.copy(selection = u.visibleItems.map { it.path }.toSet()) }
    fun invertSelection() = _ui.update { u -> u.copy(selection = u.visibleItems.map { it.path }.toSet() - u.selection) }
    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    // Settings shortcuts
    fun setSort(sort: SortOrder) = viewModelScope.launch { settingsRepo.update { it.copy(sort = sort) } }
    fun toggleGrid() = viewModelScope.launch { settingsRepo.update { it.copy(gridView = !it.gridView) } }
    fun toggleHidden() = viewModelScope.launch { settingsRepo.update { it.copy(showHidden = !it.showHidden) } }

    // Search
    fun search(text: String) {
        searchJob?.cancel()
        _ui.update { it.copy(searchText = text, searchResults = emptyList(), searching = text.isNotBlank()) }
        if (text.isBlank()) return
        if (text.trim().startsWith("tag:")) {
            val tag = text.trim().removePrefix("tag:").trim().lowercase()
            searchJob = viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(300)
                library.pathsForTag(tag).collect { paths ->
                    _ui.update { it.copy(searchResults = paths.map(::File).filter { f -> f.exists() }.map { f -> FileItem.from(f) }, searching = false) }
                }
            }
            return
        }
        val q = SearchQuery.parse(text, settings.value.showHidden)
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(350) // debounce typing
            val existing = searchHistory.value.firstOrNull { it.query == text.trim() }
            searches.upsert(com.lyvt.files.core.database.SearchEntity(text.trim(), saved = existing?.saved == true))
            FileSearch.search(File(_ui.value.path), q, settings.value.scanExclusions).collect { batch ->
                _ui.update { it.copy(searchResults = (it.searchResults + batch).take(5000)) }
            }
            _ui.update { it.copy(searching = false) }
        }
    }
    fun closeSearch() { searchJob?.cancel(); _ui.update { it.copy(searchText = null, searchResults = emptyList(), searching = false) } }

    private fun emit(m: String) { _messages.tryEmit(m) }
    private fun report(r: OpResult, verb: String) = emit(when (r) {
        is OpResult.Success -> "$verb ${r.count} item(s)"
        is OpResult.Partial -> "$verb ${r.done}, failed ${r.failed.size}: ${r.failed.first().let { "${it.first} — ${it.second}" }}"
        is OpResult.Failure -> r.reason
    })

    // Operations
    fun createFolder(name: String) = viewModelScope.launch { repo.createFolder(_ui.value.path, name).fold({ reload() }, { emit(it.message ?: "Failed") }) }
    fun createFile(name: String) = viewModelScope.launch { repo.createFile(_ui.value.path, name).fold({ reload() }, { emit(it.message ?: "Failed") }) }
    fun rename(path: String, name: String) = viewModelScope.launch { repo.rename(path, name).fold({ reload(); clearSelection() }, { emit(it.message ?: "Failed") }) }
    fun duplicate(path: String) = viewModelScope.launch { repo.duplicate(path).fold({ reload() }, { emit(it.message ?: "Failed") }) }

    fun delete(paths: List<String>) = viewModelScope.launch {
        val r = repo.delete(paths, settings.value.useTrash)
        report(r, if (settings.value.useTrash) "Moved to trash:" else "Deleted")
        clearSelection(); reload()
        if (_ui.value.searchText != null) _ui.update { u -> u.copy(searchResults = u.searchResults.filterNot { it.path in paths }) }
    }

    fun copyToClipboard(move: Boolean) { clipboard.set(_ui.value.selection.toList(), move); clearSelection(); emit(if (move) "Cut — open a folder and paste" else "Copied — open a folder and paste") }

    fun conflicts(): List<String> {
        val clip = clipboard.current.value ?: return emptyList()
        return clip.paths.map { File(it).name }.filter { File(_ui.value.path, it).exists() }
    }

    fun paste(policy: ConflictPolicy) {
        val clip = clipboard.current.value ?: return
        val dest = _ui.value.path
        opJob = viewModelScope.launch {
            _ui.update { it.copy(operation = OperationState(if (clip.move) "Moving" else "Copying", null, true)) }
            val r = repo.transfer(clip.paths, dest, clip.move, policy) { p -> _ui.update { it.copy(operation = OperationState(if (clip.move) "Moving" else "Copying", p)) } }
            _ui.update { it.copy(operation = null) }
            if (clip.move && r !is OpResult.Failure) clipboard.clear()
            report(r, if (clip.move) "Moved" else "Copied")
            reload()
        }
    }

    fun cancelOperation() { opJob?.cancel(); _ui.update { it.copy(operation = null) }; emit("Operation cancelled — completed items were kept, partial files removed"); reload() }

    fun toggleFavorite(item: FileItem) = viewModelScope.launch { repo.toggleFavorite(item, item.path in favorites.value) }
    fun pin(item: FileItem, color: Int) = viewModelScope.launch { repo.setPinned(item, true, color); emit("Pinned to home") }

    fun openExternal(item: FileItem) { viewModelScope.launch { repo.recordOpen(item.path) }; repo.openExternal(item.file)?.let(::emit) }
    fun recordOpen(path: String) = viewModelScope.launch { repo.recordOpen(path) }
    fun share(paths: List<String>) { repo.share(paths.map(::File))?.let(::emit) }

    fun batchRename(paths: List<String>, pattern: String, start: Int, pad: Int, find: String, replace: String, regex: Boolean) = viewModelScope.launch {
        val items = _ui.value.visibleItems.filter { it.path in paths }
        val plan = BatchRename.plan(items, pattern, start, pad, find, replace, regex)
        plan.firstOrNull { it.error != null }?.let { emit("${it.from.name}: ${it.error}"); return@launch }
        var ok = 0
        plan.forEach { p -> if (p.to != p.from.name && repo.rename(p.from.path, p.to).isSuccess) ok++ }
        emit("Renamed $ok item(s)"); clearSelection(); reload()
    }

    fun compress(paths: List<String>, name: String, password: String, level: Int) {
        opJob = viewModelScope.launch {
            _ui.update { it.copy(operation = OperationState("Compressing", null, true)) }
            val target = File(_ui.value.path, if (name.endsWith(".zip")) name else "$name.zip")
            val r = withContext(Dispatchers.IO) { runCatching { ArchiveManager.createZip(paths.map(::File), target, password.toCharArray().takeIf { it.isNotEmpty() }, level) } }
            _ui.update { it.copy(operation = null) }
            emit(r.fold({ "Created ${target.name}" }, { it.message ?: "Compression failed" })); clearSelection(); reload()
        }
    }

    suspend fun folderSize(path: String) = repo.folderSize(path)

    // ---- Sort into folders (one-off organizer run on the current folder) ----
    val sortPreview = MutableStateFlow<com.lyvt.files.core.organizer.OrganizerPlan?>(null)
    val sortUndo = MutableStateFlow<com.lyvt.files.core.database.OrganizerRunEntity?>(null)
    private var sortRule: com.lyvt.files.core.database.OrganizerRuleEntity? = null

    /** [destination] uses organizer tokens; [extensions] empty = all files. Only files directly in this folder are sorted. */
    fun previewSort(name: String, destination: String, extensions: String) = viewModelScope.launch {
        val r = com.lyvt.files.core.database.OrganizerRuleEntity(name = name, sourceDir = _ui.value.path, destination = destination, extensions = extensions)
        sortRule = r
        sortPreview.value = organizer.plan(r)
    }

    fun runSort() {
        val plan = sortPreview.value ?: return; val r = sortRule ?: return
        sortPreview.value = null
        opJob = viewModelScope.launch {
            _ui.update { it.copy(operation = OperationState("Sorting", null, true)) }
            val (res, run) = organizer.executeWithRun(r, plan)
            _ui.update { it.copy(operation = null) }
            reload()
            if (res.journal.isNotEmpty()) sortUndo.value = run
            emit("Sorted ${res.journal.size} file(s)" + if (res.failed.isNotEmpty()) ", ${res.failed.size} skipped: ${res.failed.first().second}" else "")
        }
    }

    fun undoSort() = viewModelScope.launch {
        val run = sortUndo.value ?: return@launch
        sortUndo.value = null
        val (ok, fail) = organizer.undo(run)
        reload(); emit("Restored $ok file(s)" + if (fail > 0) ", $fail could not be restored" else "")
    }

    fun mergePdfs(paths: List<String>) {
        opJob = viewModelScope.launch {
            _ui.update { it.copy(operation = OperationState("Merging PDFs", null, true)) }
            val sorted = paths.sortedWith { a, b -> com.lyvt.files.core.common.NaturalOrder.compare(File(a).name, File(b).name) }
            val out = com.lyvt.files.core.storage.FileOperations.uniqueTarget(File(_ui.value.path), "merged.pdf")
            val r = withContext(Dispatchers.IO) { runCatching { com.lyvt.files.core.pdf.PdfTools.init(appContext); com.lyvt.files.core.pdf.PdfTools.merge(sorted.map(::File), out) } }
            _ui.update { it.copy(operation = null) }
            emit(r.fold({ "Created ${out.name}" }, { it.message ?: "Merge failed" })); clearSelection(); reload()
        }
    }

    fun setNote(path: String, text: String) = viewModelScope.launch { library.setNote(path, text) }
    fun addTag(path: String, tag: String) = viewModelScope.launch { if (tag.isNotBlank()) library.addTag(path, tag) }
    fun removeTag(path: String, tag: String) = viewModelScope.launch { library.removeTag(path, tag) }
    fun addToCollection(id: Long, paths: List<String>) = viewModelScope.launch { library.addToCollection(id, paths); emit("Added to collection") }
    fun newCollection(name: String, paths: List<String>) = viewModelScope.launch { val id = library.createCollection(name); library.addToCollection(id, paths); emit("Created \"$name\"") }
    fun mime(item: FileItem) = repo.mimeType(item.file)
}
