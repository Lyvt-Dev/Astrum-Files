package com.astrum.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.archive.ArchiveFormat
import com.lyvt.files.core.archive.ArchiveManager
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.organizer.OrganizerEngine
import com.lyvt.files.core.organizer.OrganizerPlan
import com.lyvt.files.core.organizer.OrganizerRule
import com.lyvt.files.core.storage.BatchRename
import com.lyvt.files.core.storage.ConflictPolicy
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.core.storage.FileOperations
import com.lyvt.files.core.storage.FastFs
import com.lyvt.files.core.storage.FileSearch
import com.lyvt.files.core.storage.Hashing
import com.lyvt.files.core.storage.OpResult
import com.lyvt.files.core.storage.SearchQuery
import com.lyvt.files.core.storage.SortField
import com.lyvt.files.core.storage.TransferProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

class ExplorerState(private val scope: CoroutineScope, start: String) {
    var path by mutableStateOf(start)
    val items = mutableStateListOf<FileItem>()
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    val selection = mutableStateListOf<String>()
    private val back = ArrayDeque<String>(); private val fwd = ArrayDeque<String>()
    var search by mutableStateOf<String?>(null)
    var searching by mutableStateOf(false)
    var searchedFolders by mutableStateOf(0)
    var op by mutableStateOf<Pair<String, TransferProgress?>?>(null)
    var message by mutableStateOf<String?>(null)
    private var job: Job? = null
    private var opJob: Job? = null

    fun reload() {
        job?.cancel(); search = null
        job = scope.launch {
            loading = true
            val r = withContext(Dispatchers.IO) {
                val d = File(path)
                when {
                    !d.exists() -> null to "This folder no longer exists (drive removed or folder deleted)."
                    else -> FastFs.list(d)?.let { list -> list.asSequence().filter { AppState.showHidden || !it.hidden }
                        .filterNot { it.file.name.endsWith(FileOperations.PART_SUFFIX) }
                        .map { it.toItem() }.sortedWith(AppState.sort.comparator()).toList() to null }
                        ?: (null to "Access denied. Windows does not allow reading this folder.")
                }
            }
            items.clear(); r.first?.let { items.addAll(it) }; error = r.second
            selection.retainAll(items.map { it.path }.toSet())
            loading = false
        }
    }
    fun open(p: String) { if (p == path) return; back.addLast(path); fwd.clear(); path = p; selection.clear(); reload() }
    fun goBack() { back.removeLastOrNull()?.let { fwd.addFirst(path); path = it; selection.clear(); reload() } }
    fun goForward() { fwd.removeFirstOrNull()?.let { back.addLast(path); path = it; selection.clear(); reload() } }
    fun up() { File(path).parentFile?.let { open(it.absolutePath) } }
    val canBack get() = back.isNotEmpty()
    val canForward get() = fwd.isNotEmpty()

    fun runSearch(text: String) {
        job?.cancel(); search = text; items.clear()
        if (text.isBlank()) return
        val q = SearchQuery.parse(text, AppState.showHidden)
        job = scope.launch {
            searching = true; searchedFolders = 0
            FileSearch.search(File(path), q, AppState.scanExclusions, onFolder = { searchedFolders = it }).collect { batch -> withContext(Dispatchers.Main) { if (items.size < 5000) items.addAll(batch) } }
            searching = false
        }
    }

    private fun report(r: OpResult, verb: String) { message = when (r) {
        is OpResult.Success -> "$verb ${r.count} item(s)"
        is OpResult.Partial -> "$verb ${r.done}; ${r.failed.size} failed: ${r.failed.first().let { "${it.first} — ${it.second}" }}"
        is OpResult.Failure -> r.reason
    }; AppState.log(verb.lowercase(), message!!, r !is OpResult.Failure) }

    fun copy(move: Boolean) { AppState.clipboard = selection.toList() to move; message = "${selection.size} item(s) ${if (move) "cut" else "copied"}" }
    fun conflicts() = AppState.clipboard?.first?.map { File(it).name }?.filter { File(path, it).exists() }.orEmpty()
    fun paste(policy: ConflictPolicy) {
        val (paths, move) = AppState.clipboard ?: return
        opJob = scope.launch {
            op = (if (move) "Moving" else "Copying") to null
            val r = withContext(Dispatchers.IO) {
                val files = paths.map(::File)
                if (move) FileOperations.move(files, File(path), policy) { p -> op = "Moving" to p } else FileOperations.copy(files, File(path), policy) { p -> op = "Copying" to p }
            }
            op = null; if (move && r !is OpResult.Failure) AppState.clipboard = null
            report(r, if (move) "Moved" else "Copied"); reload()
        }
    }
    fun cancelOp() { opJob?.cancel(); op = null; message = "Cancelled — finished items kept, partial file removed"; reload() }

    fun delete(paths: List<String>, permanent: Boolean) = scope.launch {
        val r = withContext(Dispatchers.IO) {
            var ok = 0; val failed = mutableListOf<Pair<String, String>>()
            val canTrash = !permanent && AppState.useRecycleBin && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
            paths.map(::File).forEach { f ->
                val done = if (canTrash) runCatching { Desktop.getDesktop().moveToTrash(f) }.getOrDefault(false) else f.deleteRecursively()
                if (done) ok++ else failed += f.name to (if (canTrash) "Could not move to Recycle Bin (file in use?)" else "Could not delete (file in use?)")
            }
            if (failed.isEmpty()) OpResult.Success(ok) else OpResult.Partial(ok, failed)
        }
        selection.clear(); report(r, if (!permanent && AppState.useRecycleBin) "Moved to Recycle Bin:" else "Deleted"); reload()
    }

    fun mkdir(name: String) = scope.launch { runCatching { withContext(Dispatchers.IO) { FileOperations.createFolder(File(path), name) } }.onFailure { message = it.message }; reload() }
    fun mkfile(name: String) = scope.launch { runCatching { withContext(Dispatchers.IO) { FileOperations.createFile(File(path), name) } }.onFailure { message = it.message }; reload() }
    fun rename(p: String, n: String) = scope.launch { runCatching { withContext(Dispatchers.IO) { FileOperations.rename(File(p), n) } }.onFailure { message = it.message }
        .onSuccess { AppState.log("rename", "${File(p).name} → $n", true) }; reload() }
    fun duplicate(p: String) = scope.launch { runCatching { withContext(Dispatchers.IO) { FileOperations.duplicate(File(p)) } }.onFailure { message = it.message }; reload() }

    fun compress(paths: List<String>, name: String, password: String, level: Int) {
        opJob = scope.launch {
            op = "Compressing" to null
            val target = File(path, if (name.endsWith(".zip")) name else "$name.zip")
            val r = withContext(Dispatchers.IO) { runCatching { ArchiveManager.createZip(paths.map(::File), target, password.toCharArray().takeIf { it.isNotEmpty() }, level) } }
            op = null; message = r.fold({ "Created ${target.name}" }, { it.message }); reload()
        }
    }

    fun extract(archive: File, password: String?) {
        opJob = scope.launch {
            op = "Extracting" to null
            val dest = FileOperations.uniqueTarget(archive.parentFile, archive.name.substringBefore('.'))
            var bytes = 0L
            val r = withContext(Dispatchers.IO) { runCatching { ArchiveManager.extract(archive, dest, password?.toCharArray()) { n -> bytes += n; op = "Extracting" to TransferProgress(bytes, archive.length() * 3, archive.name, 0, 1) } } }
            op = null; message = r.fold({ "Extracted $it file(s) to ${dest.name}" }, { it.message }); reload()
        }
    }

    fun batchRename(paths: List<String>, pattern: String, start: Int, pad: Int, find: String, replace: String, regex: Boolean) = scope.launch {
        val plan = BatchRename.plan(items.filter { it.path in paths }, pattern, start, pad, find, replace, regex)
        plan.firstOrNull { it.error != null }?.let { message = "${it.from.name}: ${it.error}"; return@launch }
        var ok = 0
        withContext(Dispatchers.IO) { plan.forEach { p -> if (p.to != p.from.name && runCatching { FileOperations.rename(File(p.from.path), p.to) }.isSuccess) ok++ } }
        message = "Renamed $ok item(s)"; selection.clear(); reload()
    }

    // Sort into folders (organizer engine)
    var sortPlan by mutableStateOf<OrganizerPlan?>(null)
    var lastSortJournal by mutableStateOf<List<Pair<String, String>>?>(null)
    fun previewSort(dest: String, exts: Set<String>) = scope.launch { sortPlan = withContext(Dispatchers.IO) { OrganizerEngine.plan(OrganizerRule("Sort", path, dest, exts)) } }
    fun runSort() { val p = sortPlan ?: return; sortPlan = null; scope.launch {
        val r = withContext(Dispatchers.IO) { OrganizerEngine.execute(p) }
        lastSortJournal = r.journal; message = "Sorted ${r.journal.size} file(s)"; Organizer.recordRun("Sort ${File(path).name}", r.journal, r.failed.size); reload()
    } }
    fun undoSort() { val j = lastSortJournal ?: return; lastSortJournal = null; scope.launch { val (ok, _) = withContext(Dispatchers.IO) { OrganizerEngine.undo(j) }; message = "Restored $ok file(s)"; reload() } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(state: ExplorerState) {
    val s = state
    var dialog by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<FileItem?>(null) }
    var searchText by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (s.items.isEmpty() && s.search == null) s.reload(); runCatching { focus.requestFocus() } }
    LaunchedEffect(AppState.pendingExplorerPath) { AppState.pendingExplorerPath?.let { s.open(it); AppState.pendingExplorerPath = null
        AppState.pendingSearch?.takeIf { q -> q.isNotBlank() }?.let { q -> searchText = q; s.runSearch(q) }; AppState.pendingSearch = null } }
    LaunchedEffect(s.message) { if (s.message != null) { kotlinx.coroutines.delay(4000); s.message = null } }

    fun openItem(i: FileItem) { if (i.isDirectory) s.open(i.path) else AppState.open(File(i.path)) }
    fun sel() = s.selection.toList()

    Row(Modifier.fillMaxSize()) {
        // Sidebar: quick access + drives
        Column(Modifier.width(250.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).verticalScroll(rememberScrollState()).padding(10.dp)) {
            Text("Quick access", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            listOf("Home" to AppState.home, "Desktop" to AppState.knownFolder("Desktop"), "Documents" to AppState.knownFolder("Documents"),
                "Downloads" to AppState.knownFolder("Downloads"), "Pictures" to AppState.knownFolder("Pictures"), "Music" to AppState.knownFolder("Music"),
                "Videos" to AppState.knownFolder("Videos")).filter { it.second.exists() }.forEach { (n, f) ->
                SideItem(n, if (n == "Home") Icons.Filled.Home else if (n == "Downloads") Icons.Filled.Download else Icons.Outlined.FolderOpen, s.path == f.absolutePath) { s.open(f.absolutePath) }
            }
            if (AppState.favorites.isNotEmpty()) {
                Text("Favorites", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                AppState.favorites.forEach { p -> SideItem(File(p).name.ifEmpty { p }, Icons.Filled.Star, s.path == p) { if (File(p).isDirectory) s.open(p) else AppState.open(File(p)) } }
            }
            Text("Drives", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            File.listRoots().forEach { r ->
                SideItem(r.absolutePath, Icons.Filled.Storage, s.path == r.absolutePath) { s.open(r.absolutePath) }
                if (r.totalSpace > 0) Column(Modifier.padding(start = 40.dp, end = 8.dp, bottom = 6.dp)) {
                    UsageBar((r.totalSpace - r.usableSpace).toFloat() / r.totalSpace)
                    Text("${Formatters.size(r.usableSpace)} free of ${Formatters.size(r.totalSpace)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || dialog != null) return@onPreviewKeyEvent false
            when {
                e.isCtrlPressed && e.key == Key.C && s.selection.isNotEmpty() -> { s.copy(false); true }
                e.isCtrlPressed && e.key == Key.X && s.selection.isNotEmpty() -> { s.copy(true); true }
                e.isCtrlPressed && e.key == Key.V && AppState.clipboard != null -> { if (s.conflicts().isEmpty()) s.paste(ConflictPolicy.KEEP_BOTH) else dialog = "conflict"; true }
                e.isCtrlPressed && e.key == Key.A -> { s.selection.clear(); s.selection.addAll(s.items.map { it.path }); true }
                e.key == Key.Delete && s.selection.isNotEmpty() -> { dialog = "delete"; true }
                e.key == Key.F2 && s.selection.size == 1 -> { target = s.items.firstOrNull { it.path == s.selection[0] }; dialog = "rename"; true }
                e.key == Key.F5 -> { s.reload(); true }
                e.key == Key.Backspace && s.search == null -> { s.up(); true }
                e.key == Key.Enter && s.selection.size == 1 -> { s.items.firstOrNull { it.path == s.selection[0] }?.let(::openItem); true }
                else -> false
            }
        }) {
            // Toolbar
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = s::goBack, enabled = s.canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton(onClick = s::goForward, enabled = s.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                IconButton(onClick = s::up) { Icon(Icons.Filled.ArrowUpward, "Up") }
                IconButton(onClick = s::reload) { Icon(Icons.Filled.Refresh, "Refresh (F5)") }
                Breadcrumbs(s.path, Modifier.weight(1f)) { s.open(it) }
                OutlinedTextField(searchText, { searchText = it; s.runSearch(it) }, singleLine = true, placeholder = { Text("Search in this folder…", maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, trailingIcon = { if (searchText.isNotEmpty()) IconButton(onClick = { searchText = ""; s.reload() }) { Icon(Icons.Filled.Close, null) } },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.width(340.dp), textStyle = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButton(Icons.Filled.CreateNewFolder, "New folder") { dialog = "mkdir" }
                ToolButton(Icons.Filled.ContentCopy, "Copy", s.selection.isNotEmpty()) { s.copy(false) }
                ToolButton(Icons.Filled.ContentCut, "Cut", s.selection.isNotEmpty()) { s.copy(true) }
                ToolButton(Icons.Filled.ContentPaste, "Paste" + (AppState.clipboard?.let { " (${it.first.size})" } ?: ""), AppState.clipboard != null) {
                    if (s.conflicts().isEmpty()) s.paste(ConflictPolicy.KEEP_BOTH) else dialog = "conflict" }
                ToolButton(Icons.Filled.Delete, "Delete", s.selection.isNotEmpty()) { dialog = "delete" }
                Spacer(Modifier.weight(1f))
                var more by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { more = true }) { Text("More ▾") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("New file") }, onClick = { dialog = "mkfile"; more = false })
                        DropdownMenuItem(text = { Text("Sort into folders…") }, onClick = { dialog = "sortfolders"; more = false })
                        DropdownMenuItem(text = { Text("Select all  (Ctrl+A)") }, onClick = { s.selection.clear(); s.selection.addAll(s.items.map { it.path }); more = false })
                        DropdownMenuItem(text = { Text("Invert selection") }, onClick = { val all = s.items.map { it.path }.toSet() - s.selection.toSet(); s.selection.clear(); s.selection.addAll(all); more = false })
                        if (s.selection.size > 1) DropdownMenuItem(text = { Text("Batch rename…") }, onClick = { dialog = "batch"; more = false })
                        if (s.selection.isNotEmpty()) DropdownMenuItem(text = { Text("Compress to ZIP…") }, onClick = { dialog = "zip"; more = false })
                        if (s.selection.isNotEmpty()) DropdownMenuItem(text = { Text("Send to phone (Transfer)") }, onClick = { Transfer.share(sel().map(::File)); AppState.screen = Screen.TRANSFER; more = false })
                        if (s.selection.size >= 2 && sel().all { it.endsWith(".pdf", true) }) DropdownMenuItem(text = { Text("Merge PDFs") }, onClick = {
                            more = false; s.message = PdfTools.mergeInto(sel().map(::File), File(s.path)); s.reload() })
                        if (s.selection.size == 2) DropdownMenuItem(text = { Text("Compare") }, onClick = { dialog = "compare"; more = false })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(if (AppState.showHidden) "Hide hidden files" else "Show hidden files") }, onClick = { AppState.showHidden = !AppState.showHidden; AppState.save(); s.reload(); more = false })
                        DropdownMenuItem(text = { Text("Open in Windows Explorer") }, onClick = { runCatching { Desktop.getDesktop().open(File(s.path)) }; more = false })
                    }
                }
                IconButton(onClick = { dialog = "sort" }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                IconButton(onClick = { AppState.gridView = !AppState.gridView; AppState.save() }) { Icon(if (AppState.gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, "Layout") }
            }
            s.op?.let { (label, p) ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    ProgressCard(label + (p?.let { " · ${it.itemsDone}/${it.itemsTotal}" } ?: ""), p?.let { "${it.currentName} · ${Formatters.size(it.bytesDone)} of ${Formatters.size(it.bytesTotal)}" } ?: "Working…",
                        p?.fraction, onCancel = s::cancelOp)
                }
            }
            if (s.search != null) Text(if (s.searching) "Searching… ${s.searchedFolders} folders checked · ${s.items.size} found" else "${s.items.size} result(s)",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            if (s.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
            Box(Modifier.weight(1f)) {
                when {
                    s.error != null -> EmptyState(Icons.Outlined.Block, "Can't open this folder", s.error!!) { TextButton(onClick = { s.open(AppState.home.absolutePath) }) { Text("Go home") } }
                    s.items.isEmpty() && !s.loading && !s.searching -> EmptyState(Icons.Outlined.FolderOpen, if (s.search != null) "No results" else "This folder is empty",
                        if (s.search != null) "Try ext:zip, type:image, size>100mb, after:2024-01-01" else "Create a folder or paste files here.")
                    AppState.gridView -> LazyVerticalGrid(GridCells.Adaptive(130.dp), contentPadding = PaddingValues(12.dp)) {
                        items(s.items, key = { it.path }) { i ->
                            ContextMenuArea(items = { contextItems(i, s) { d -> target = i; dialog = d } }) {
                                Column(Modifier.padding(6.dp).clip(RoundedCornerShape(16.dp)).background(if (i.path in s.selection) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                                    .combinedClickable(onClick = { s.selection.clear(); s.selection.add(i.path) }, onDoubleClick = { openItem(i) }).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    FileThumb(i, 72.dp, AppState.thumbnails); Spacer(Modifier.height(6.dp))
                                    Text(i.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    else -> Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
                            HeaderCell("Name", SortField.NAME, Modifier.weight(1f), s); HeaderCell("Modified", SortField.DATE, Modifier.width(150.dp), s)
                            HeaderCell("Type", SortField.TYPE, Modifier.width(80.dp), s); HeaderCell("Size", SortField.SIZE, Modifier.width(90.dp), s)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                        LazyColumn(contentPadding = PaddingValues(8.dp)) {
                            items(s.items, key = { it.path }) { i ->
                                ContextMenuArea(items = { contextItems(i, s) { d -> target = i; dialog = d } }) {
                                    val selected = i.path in s.selection
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else androidx.compose.ui.graphics.Color.Transparent)
                                        .combinedClickable(onClick = {
                                            if (s.selection.isNotEmpty() && i.path in s.selection && s.selection.size == 1) Unit
                                            s.selection.clear(); s.selection.add(i.path)
                                        }, onDoubleClick = { openItem(i) }, onLongClick = { if (selected) s.selection.remove(i.path) else s.selection.add(i.path) })
                                        .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(selected, { c -> if (c) s.selection.add(i.path) else s.selection.remove(i.path) }, modifier = Modifier.size(28.dp))
                                        Spacer(Modifier.width(6.dp)); FileThumb(i, 30.dp, AppState.thumbnails); Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(i.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                            if (s.search != null) Text(File(i.path).parent.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                        }
                                        if (i.path in AppState.favorites) Icon(Icons.Filled.Star, null, tint = Gold, modifier = Modifier.size(14.dp))
                                        Text(Formatters.date(i.lastModified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(150.dp))
                                        Text(if (i.isDirectory) "Folder" else i.extension.uppercase().ifEmpty { "File" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
                                        Text(if (i.isDirectory) "" else Formatters.size(i.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Status bar
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.items.size} items" + if (s.selection.isNotEmpty()) " · ${s.selection.size} selected · ${Formatters.size(s.items.filter { it.path in s.selection }.sumOf { it.size })}" else "",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                s.message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                if (s.lastSortJournal != null) TextButton(onClick = s::undoSort) { Text("Undo sort") }
            }
        }
    }
    ExplorerDialogs(dialog, target, s) { dialog = null }
}

private fun contextItems(i: FileItem, s: ExplorerState, onDialog: (String) -> Unit): List<ContextMenuItem> {
    if (i.path !in s.selection) { s.selection.clear(); s.selection.add(i.path) }
    return buildList {
        add(ContextMenuItem("Open") { if (i.isDirectory) s.open(i.path) else AppState.open(File(i.path)) })
        if (!i.isDirectory) add(ContextMenuItem("Open with default app") { AppState.systemOpen(File(i.path)) })
        add(ContextMenuItem("Show in Windows Explorer") { AppState.reveal(File(i.path)) })
        add(ContextMenuItem("Copy") { s.copy(false) }); add(ContextMenuItem("Cut") { s.copy(true) })
        add(ContextMenuItem("Rename  (F2)") { onDialog("rename") }); add(ContextMenuItem("Duplicate") { s.duplicate(i.path) })
        add(ContextMenuItem("Compress to ZIP") { onDialog("zip") })
        if (ArchiveFormat.of(i.name) !in setOf(ArchiveFormat.UNKNOWN, ArchiveFormat.RAR)) add(ContextMenuItem("Extract here") { onDialog("extract") })
        add(ContextMenuItem(if (i.path in AppState.favorites) "Remove from favorites" else "Add to favorites") { AppState.toggleFavorite(i.path) })
        add(ContextMenuItem("Send to phone") { Transfer.share(s.selection.map(::File)); AppState.screen = Screen.TRANSFER })
        if (!i.isDirectory) add(ContextMenuItem("Encrypt into Vault") { Vault.pendingImport = s.selection.map(::File); AppState.screen = Screen.VAULT })
        add(ContextMenuItem("Properties") { onDialog("props") })
        add(ContextMenuItem("Delete") { onDialog("delete") })
    }
}

@Composable private fun SideItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label) }
}

@Composable private fun HeaderCell(label: String, field: SortField, modifier: Modifier, s: ExplorerState) {
    val active = AppState.sort.field == field
    Text(label + if (active) (if (AppState.sort.ascending) " ▲" else " ▼") else "", style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clickable { AppState.sort = if (active) AppState.sort.copy(ascending = !AppState.sort.ascending) else AppState.sort.copy(field = field, ascending = true); AppState.save(); s.reload() })
}

@Composable private fun Breadcrumbs(path: String, modifier: Modifier, onOpen: (String) -> Unit) {
    val parts = buildList { var f: File? = File(path); while (f != null) { add(0, f); f = f.parentFile } }
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { idx, f ->
            val last = idx == parts.lastIndex
            Text(f.name.ifEmpty { f.path }, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (last) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(enabled = !last) { onOpen(f.absolutePath) }.padding(horizontal = 9.dp, vertical = 5.dp))
            if (!last) Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExplorerDialogs(d: String?, target: FileItem?, s: ExplorerState, dismiss: () -> Unit) {
    val sel = s.selection.toList()
    when (d) {
        "mkdir" -> InputDialog("New folder", label = "Name", confirm = "Create", validate = { FileOperations.validateName(it) ?: if (File(s.path, it).exists()) "Already exists" else null },
            onConfirm = s::mkdir, onDismiss = dismiss)
        "mkfile" -> InputDialog("New file", "New Text Document.txt", "Name", "Create", validate = { FileOperations.validateName(it) ?: if (File(s.path, it).exists()) "Already exists" else null },
            onConfirm = s::mkfile, onDismiss = dismiss)
        "rename" -> target?.let { t -> InputDialog("Rename", t.name, "New name", "Rename", validate = { FileOperations.validateName(it) ?: if (it != t.name && File(File(t.path).parent, it).exists()) "Already exists" else null },
            onConfirm = { s.rename(t.path, it) }, onDismiss = dismiss) }
        "delete" -> {
            var permanent by remember { mutableStateOf(false) }
            AlertDialog(onDismissRequest = dismiss, title = { Text("Delete ${sel.size} item(s)?") }, text = {
                Column {
                    Text(if (AppState.useRecycleBin && !permanent) "Items go to the Windows Recycle Bin and can be restored there." else "Items are deleted permanently. This cannot be undone.")
                    if (AppState.useRecycleBin) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(permanent, { permanent = it }); Text("Delete permanently (Shift+Del)") }
                }
            }, confirmButton = { TextButton(onClick = { s.delete(sel, permanent); dismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
        }
        "conflict" -> {
            var policy by remember { mutableStateOf(ConflictPolicy.KEEP_BOTH) }
            AlertDialog(onDismissRequest = dismiss, title = { Text("${s.conflicts().size} item(s) already exist") }, text = {
                Column { listOf(ConflictPolicy.KEEP_BOTH to "Keep both (rename new)", ConflictPolicy.SKIP to "Skip", ConflictPolicy.REPLACE to "Replace")
                    .forEach { (p, l) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(policy == p, { policy = p }); Text(l) } } }
            }, confirmButton = { TextButton(onClick = { s.paste(policy); dismiss() }) { Text("Paste") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
        }
        "sort" -> AlertDialog(onDismissRequest = dismiss, title = { Text("Sort") }, text = {
            Column {
                SortField.entries.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(AppState.sort.field == f, { AppState.sort = AppState.sort.copy(field = f); AppState.save(); s.reload() })
                    Text(when (f) { SortField.NAME -> "Name (natural)"; SortField.DATE -> "Date modified"; SortField.SIZE -> "Size"; SortField.TYPE -> "Type" }) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(AppState.sort.ascending, { AppState.sort = AppState.sort.copy(ascending = it); AppState.save(); s.reload() }); Text("Ascending") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(AppState.sort.foldersFirst, { AppState.sort = AppState.sort.copy(foldersFirst = it); AppState.save(); s.reload() }); Text("Folders first") }
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text("OK") } })
        "zip" -> {
            var name by remember { mutableStateOf(File(sel.firstOrNull() ?: "archive").nameWithoutExtension) }
            var pw by remember { mutableStateOf("") }; var level by remember { mutableStateOf(5f) }
            AlertDialog(onDismissRequest = dismiss, title = { Text("Compress to ZIP") }, text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Archive name") }, singleLine = true)
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password (optional, AES-256)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Text("Compression level ${level.toInt()}"); Slider(level, { level = it }, valueRange = 0f..9f, steps = 8)
                }
            }, confirmButton = { TextButton(onClick = { s.compress(sel, name, pw, level.toInt()); dismiss() }) { Text("Compress") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
        }
        "extract" -> target?.let { t ->
            val encrypted = remember(t.path) { runCatching { ArchiveManager.list(File(t.path)).any { it.encrypted } }.getOrDefault(false) }
            if (encrypted) InputDialog("Archive password", password = true, label = "Password", onConfirm = { s.extract(File(t.path), it) }, onDismiss = dismiss)
            else { LaunchedEffect(t.path) { s.extract(File(t.path), null); dismiss() } }
        }
        "props" -> target?.let { PropertiesDialog(it, dismiss) }
        "batch" -> BatchDialog(sel, s, dismiss)
        "compare" -> CompareDialog(sel[0], sel[1], dismiss)
        "sortfolders" -> SortFoldersDialog(s, dismiss)
    }
}

@Composable
private fun PropertiesDialog(i: FileItem, dismiss: () -> Unit) {
    var size by remember { mutableStateOf<String?>(null) }
    var hashes by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var cmp by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val f = File(i.path)
    LaunchedEffect(i.path) {
        if (i.isDirectory) size = withContext(Dispatchers.IO) { var b = 0L; var n = 0; f.walkTopDown().onFail { _, _ -> }.forEach { if (it.isFile) { b += it.length(); n++; if (n % 500 == 0) size = "Calculating… $n files" } }; "${Formatters.size(b)} · $n files" }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(i.name) }, text = {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.width(460.dp).verticalScroll(rememberScrollState())) {
                @Composable fun P(k: String, v: String, mono: Boolean = false) = Column(Modifier.padding(vertical = 4.dp)) {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null) }
                P("Location", f.parent.orEmpty())
                P("Type", if (i.isDirectory) "Folder" else (runCatching { java.nio.file.Files.probeContentType(f.toPath()) }.getOrNull() ?: i.extension.uppercase() + " file"))
                P("Size", if (i.isDirectory) size ?: "Calculating…" else "${Formatters.size(i.size)} (${i.size} bytes)")
                P("Modified", Formatters.date(i.lastModified))
                runCatching { java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java) }.getOrNull()?.let { a ->
                    P("Created", Formatters.date(a.creationTime().toMillis()))
                }
                P("Attributes", listOfNotNull(if (f.isHidden) "Hidden" else null, if (!f.canWrite()) "Read-only" else null).joinToString().ifEmpty { "Normal" })
                if (!i.isDirectory) {
                    if (hashes == null) TextButton(onClick = { scope.launch(Dispatchers.IO) { hashes = listOf("MD5", "SHA-1", "SHA-256", "SHA-512").map { a -> a to runCatching { Hashing.digest(f.inputStream(), a) }.getOrElse { "error" } } } }) {
                        Text("Calculate checksums (MD5, SHA-1, SHA-256, SHA-512)") }
                    hashes?.forEach { (a, h) -> P(a, h, true) }
                    hashes?.let { hs -> OutlinedTextField(cmp, { cmp = it }, label = { Text("Paste checksum to verify") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (cmp.isNotBlank()) { val m = hs.firstOrNull { it.second.equals(cmp.trim(), true) }
                            Text(m?.let { "✓ Matches ${it.first}" } ?: "✗ No match", color = if (m != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

@Composable
private fun BatchDialog(paths: List<String>, s: ExplorerState, dismiss: () -> Unit) {
    var pattern by remember { mutableStateOf("{name}_{n}") }; var start by remember { mutableStateOf("1") }; var pad by remember { mutableStateOf("2") }
    var find by remember { mutableStateOf("") }; var repl by remember { mutableStateOf("") }; var regex by remember { mutableStateOf(false) }
    val plan = BatchRename.plan(s.items.filter { it.path in paths }, pattern, start.toIntOrNull() ?: 1, pad.toIntOrNull() ?: 0, find, repl, regex)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Batch rename ${paths.size} items") }, text = {
        Column(Modifier.width(480.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(pattern, { pattern = it }, label = { Text("Pattern: {name} {n} {date} {ext}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row { OutlinedTextField(start, { start = it.filter(Char::isDigit) }, label = { Text("Start") }, singleLine = true, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp))
                OutlinedTextField(pad, { pad = it.filter(Char::isDigit) }, label = { Text("Digits") }, singleLine = true, modifier = Modifier.weight(1f)) }
            Row { OutlinedTextField(find, { find = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f)); Spacer(Modifier.width(8.dp))
                OutlinedTextField(repl, { repl = it }, label = { Text("Replace") }, singleLine = true, modifier = Modifier.weight(1f)) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(regex, { regex = it }); Spacer(Modifier.width(8.dp)); Text("Regular expression") }
            Text("Preview", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            plan.take(10).forEach { Text("${it.from.name}  →  ${it.to}${it.error?.let { e -> "  ⚠ $e" } ?: ""}", style = MaterialTheme.typography.bodySmall,
                color = if (it.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
        }
    }, confirmButton = { TextButton(enabled = plan.none { it.error != null }, onClick = { s.batchRename(paths, pattern, start.toIntOrNull() ?: 1, pad.toIntOrNull() ?: 0, find, repl, regex); dismiss() }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun CompareDialog(a: String, b: String, dismiss: () -> Unit) {
    var r by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(a, b) { r = withContext(Dispatchers.IO) {
        val fa = File(a); val fb = File(b)
        if (fa.isDirectory && fb.isDirectory) {
            val la = fa.walkTopDown().filter { it.isFile }.associate { it.relativeTo(fa).path to it.length() }; val lb = fb.walkTopDown().filter { it.isFile }.associate { it.relativeTo(fb).path to it.length() }
            val oa = la.keys - lb.keys; val ob = lb.keys - la.keys; val diff = la.keys.intersect(lb.keys).filter { la[it] != lb[it] }
            "Only in ${fa.name}: ${oa.size}\n${oa.take(15).joinToString("\n") { "  $it" }}\n\nOnly in ${fb.name}: ${ob.size}\n${ob.take(15).joinToString("\n") { "  $it" }}\n\nDifferent size: ${diff.size}\n${diff.take(15).joinToString("\n") { "  $it" }}"
        } else if (fa.isFile && fb.isFile) { if (fa.length() != fb.length()) "Files differ (size)" else if (Hashing.sha256(fa) == Hashing.sha256(fb)) "Files are identical (SHA-256)" else "Files differ in content" }
        else "Select two files or two folders" } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Compare") }, text = { Text(r ?: "Comparing…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(480.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

@Composable
private fun SortFoldersDialog(s: ExplorerState, dismiss: () -> Unit) {
    val plan = s.sortPlan
    var mode by remember { mutableStateOf("type") }; var folder by remember { mutableStateOf("Archives") }; var exts by remember { mutableStateOf("zip,7z,rar,tar,gz,tgz,bz2,xz") }
    if (plan != null) {
        AlertDialog(onDismissRequest = { s.sortPlan = null; dismiss() }, title = { Text("Preview") }, text = {
            Column(Modifier.width(480.dp).verticalScroll(rememberScrollState())) {
                if (plan.moves.isEmpty()) Text("Nothing to sort here.")
                else { Text("${plan.moves.size} file(s), ${Formatters.size(plan.totalBytes)}. Nothing is overwritten; Undo appears in the status bar.", style = MaterialTheme.typography.bodySmall)
                    plan.moves.groupBy { it.to.parentFile.name }.toSortedMap().forEach { (dir, m) -> Text("📁 $dir (${m.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                        m.take(4).forEach { Text("   ${it.from.name}", style = MaterialTheme.typography.bodySmall) }; if (m.size > 4) Text("   … ${m.size - 4} more", style = MaterialTheme.typography.bodySmall) } }
            }
        }, confirmButton = { TextButton(enabled = plan.moves.isNotEmpty(), onClick = { s.runSort(); dismiss() }) { Text("Sort ${plan.moves.size} files") } },
            dismissButton = { TextButton(onClick = { s.sortPlan = null }) { Text("Back") } })
        return
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Sort into folders") }, text = {
        Column(Modifier.width(460.dp)) {
            listOf("type" to "Everything by type (Images, Videos, Archives…)", "pick" to "Only certain files into one folder", "ext" to "By extension (ZIP, PDF…)", "month" to "By month (2024-05)").forEach { (k, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == k, { mode = k }); Text(l) } }
            if (mode == "pick") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Archives" to "zip,7z,rar,tar,gz,tgz,bz2,xz", "Images" to "jpg,jpeg,png,gif,webp,heic,bmp", "Videos" to "mp4,mkv,mov,avi,webm",
                        "Documents" to "pdf,doc,docx,xls,xlsx,ppt,pptx,txt,odt", "Installers" to "exe,msi,apk").forEach { (n, e) -> Pill(n, folder == n) { folder = n; exts = e } } }
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder name") }, singleLine = true)
                OutlinedTextField(exts, { exts = it }, label = { Text("Extensions") }, singleLine = true)
            }
        }
    }, confirmButton = { TextButton(onClick = {
        when (mode) { "type" -> s.previewSort("{category}", emptySet()); "ext" -> s.previewSort("{ext}", emptySet()); "month" -> s.previewSort("{yyyy}-{MM}", emptySet())
            else -> s.previewSort(folder.trim(), exts.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()) } }) { Text("Preview") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
