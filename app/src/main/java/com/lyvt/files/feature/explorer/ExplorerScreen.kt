package com.lyvt.files.feature.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.FileGridCell
import com.lyvt.files.ui.FileRow
import com.lyvt.files.ui.LoadingState
import com.lyvt.files.ui.openFile
import java.io.File

@Composable
fun ExplorerScreen(nav: NavHostController, wide: Boolean) {
    if (wide) {
        // Split view: two independent panes sharing the app clipboard (copy in one, paste in the other).
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { ExplorerPane(nav, hiltViewModel(key = "left"), handleBack = true) }
            VerticalDivider()
            Box(Modifier.weight(1f)) { ExplorerPane(nav, hiltViewModel(key = "right"), handleBack = false) }
        }
    } else ExplorerPane(nav, hiltViewModel(), handleBack = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerPane(nav: NavHostController, vm: ExplorerViewModel, handleBack: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val clip by vm.clipboard.current.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var dialog by remember { mutableStateOf<ExplorerDialog?>(null) }
    var menuFor by remember { mutableStateOf<FileItem?>(null) }

    BackHandler(enabled = handleBack && vm.canGoBack()) { vm.back() }

    fun openItem(item: FileItem) {
        if (item.isDirectory) vm.open(item.path)
        else { vm.recordOpen(item.path); if (!nav.openFile(item.path)) vm.openExternal(item) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                if (ui.selection.isNotEmpty()) SelectionBar(ui.selection.size, vm, onMore = { dialog = ExplorerDialog.SelectionMenu })
                else if (ui.searchText != null) SearchBar(ui.searchText!!, ui.searching, ui.searchResults.size, vm, vm::search, vm::closeSearch)
                else BrowseBar(ui, settings.gridView, settings.showHidden, vm, onSort = { dialog = ExplorerDialog.Sort }, nav = nav)
                if (ui.tabs.size > 1) TabsRow(ui, vm)
                if (ui.searchText == null) Breadcrumbs(ui.path, vm.rootPath, vm::open)
            }
        },
        floatingActionButton = {
            if (ui.selection.isEmpty() && ui.searchText == null) Column(horizontalAlignment = Alignment.End) {
                if (clip != null) {
                    FloatingActionButton(onClick = {
                        if (vm.conflicts().isEmpty()) vm.paste(com.lyvt.files.core.storage.ConflictPolicy.KEEP_BOTH) else dialog = ExplorerDialog.Conflict(vm.conflicts())
                    }, containerColor = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(8.dp))
                            Text("${stringResource(R.string.paste_here)} (${clip!!.paths.size})")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                FloatingActionButton(onClick = { dialog = ExplorerDialog.Create }, shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Filled.Add, stringResource(R.string.new_folder)) }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ui.operation?.let { op -> OperationCard(op, vm::cancelOperation) }
            val items = ui.visibleItems
            when {
                ui.searchText != null && items.isEmpty() && !ui.searching ->
                    EmptyState(Icons.Outlined.SearchOff, "No results", "Try operators like ext:pdf, type:image, size>10mb, after:2024-01-01")
                ui.searchText == null && ui.dir is DirState.Loading -> LoadingState("Loading…")
                ui.searchText == null && ui.dir is DirState.Error -> {
                    val denied = (ui.dir as DirState.Error).kind == "denied"
                    EmptyState(Icons.Outlined.Block, stringResource(if (denied) R.string.access_denied else R.string.not_found),
                        if (denied) stringResource(R.string.access_denied_msg) else "It may have been moved, deleted or the SD card was removed.") {
                        TextButton(onClick = { vm.open(vm.rootPath) }) { Text(stringResource(R.string.internal_storage)) }
                    }
                }
                ui.searchText == null && items.isEmpty() ->
                    EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.folder_empty), stringResource(R.string.folder_empty_msg))
                settings.gridView -> LazyVerticalGrid(GridCells.Adaptive(112.dp), contentPadding = PaddingValues(8.dp, 4.dp, 8.dp, 120.dp)) {
                    items(items, key = { it.path }) { item ->
                        FileGridCell(item, item.path in ui.selection, settings.showThumbnails,
                            onClick = { if (ui.selection.isNotEmpty()) vm.toggle(item.path) else openItem(item) },
                            onLongClick = { vm.toggle(item.path) })
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(items, key = { it.path }) { item ->
                        Box {
                            FileRow(item, item.path in ui.selection, item.path in favorites, settings.compactList, settings.showThumbnails,
                                onClick = { if (ui.selection.isNotEmpty()) vm.toggle(item.path) else openItem(item) },
                                onLongClick = { vm.toggle(item.path) },
                                trailing = {
                                    if (ui.selection.isEmpty()) IconButton(onClick = { menuFor = item }) { Icon(Icons.Filled.MoreVert, null) }
                                })
                            if (ui.searchText != null) Text(File(item.path).parent.orEmpty().removePrefix(vm.rootPath).ifEmpty { "/" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.BottomStart).padding(start = 80.dp), maxLines = 1)
                            DropdownMenu(expanded = menuFor == item, onDismissRequest = { menuFor = null }) {
                                ItemMenu(item, item.path in favorites, vm, nav, onDialog = { dialog = it }, dismiss = { menuFor = null })
                            }
                        }
                    }
                }
            }
        }
    }
    ExplorerDialogs(dialog, ui, vm, settings, onDismiss = { dialog = null }, onDialog = { dialog = it })
}

@Composable
private fun ItemMenu(item: FileItem, fav: Boolean, vm: ExplorerViewModel, nav: NavHostController, onDialog: (ExplorerDialog) -> Unit, dismiss: () -> Unit) {
    fun act(f: () -> Unit) = { f(); dismiss() }
    if (!item.isDirectory) DropdownMenuItem(text = { Text(stringResource(R.string.open_with)) }, onClick = act { vm.openExternal(item) })
    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = act { onDialog(ExplorerDialog.Rename(item)) })
    DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = act { vm.clipboard.set(listOf(item.path), false) })
    DropdownMenuItem(text = { Text(stringResource(R.string.move)) }, onClick = act { vm.clipboard.set(listOf(item.path), true) })
    DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = act { vm.duplicate(item.path) })
    if (!item.isDirectory) DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = act { vm.share(listOf(item.path)) })
    DropdownMenuItem(text = { Text(stringResource(R.string.compress)) }, onClick = act { onDialog(ExplorerDialog.Compress(listOf(item.path), item.name.substringBeforeLast('.'))) })
    if (com.lyvt.files.core.archive.ArchiveFormat.of(item.name) != com.lyvt.files.core.archive.ArchiveFormat.UNKNOWN)
        DropdownMenuItem(text = { Text(stringResource(R.string.extract)) }, onClick = act { nav.navigate(com.lyvt.files.ui.Routes.archive(item.path)) })
    DropdownMenuItem(text = { Text(if (fav) "Remove favorite" else stringResource(R.string.favorite)) }, onClick = act { vm.toggleFavorite(item) })
    if (item.isDirectory) DropdownMenuItem(text = { Text("Pin to home") }, onClick = act { onDialog(ExplorerDialog.Pin(item)) })
    DropdownMenuItem(text = { Text(stringResource(R.string.properties)) }, onClick = act { onDialog(ExplorerDialog.Properties(item)) })
    if (!item.isDirectory) DropdownMenuItem(text = { Text("Inspect (developer)") }, onClick = act { nav.navigate(com.lyvt.files.ui.Routes.inspect(item.path)) })
    if (!item.isDirectory) DropdownMenuItem(text = { Text("Open as text") }, onClick = act { nav.navigate(com.lyvt.files.ui.Routes.text(item.path)) })
    HorizontalDivider()
    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, onClick = act { onDialog(ExplorerDialog.Delete(listOf(item.path))) })
}

@Composable
private fun BrowseBar(ui: ExplorerUi, grid: Boolean, showHidden: Boolean, vm: ExplorerViewModel, onSort: () -> Unit, nav: NavHostController) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ui.tabs[ui.activeTab].back.isNotEmpty()) IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        else Spacer(Modifier.width(12.dp))
        Text(File(ui.path).name.ifEmpty { "/" }.let { if (ui.path == vm.rootPath) stringResource(R.string.internal_storage) else it },
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = { vm.search("") }) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
        IconButton(onClick = { vm.toggleGrid() }) { Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, "Toggle layout") }
        IconButton(onClick = onSort) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New tab") }, onClick = { vm.newTab(); menu = false })
                DropdownMenuItem(text = { Text(stringResource(R.string.select_all)) }, onClick = { vm.selectAll(); menu = false })
                DropdownMenuItem(text = { Text(if (showHidden) "Hide hidden files" else stringResource(R.string.show_hidden)) }, onClick = { vm.toggleHidden(); menu = false })
                DropdownMenuItem(text = { Text("Go up") }, onClick = { vm.up(); menu = false })
                if (ui.tabs[ui.activeTab].forward.isNotEmpty()) DropdownMenuItem(text = { Text("Forward") }, onClick = { vm.forward(); menu = false })
                vm.storageRoots.forEach { v -> DropdownMenuItem(text = { Text(v.label) }, onClick = { vm.open(v.path); menu = false }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.trash)) }, onClick = { nav.navigate(com.lyvt.files.ui.Routes.TRASH); menu = false })
                DropdownMenuItem(text = { Text("Restore previous clipboard") }, onClick = { menu = false; vm.clipboard.history.value.getOrNull(1)?.let(vm.clipboard::restore) })
            }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, vm: ExplorerViewModel, onMore: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(20.dp))) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
            Text("$count", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.copyToClipboard(false) }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) }
            IconButton(onClick = { vm.copyToClipboard(true) }) { Icon(Icons.Filled.ContentCut, stringResource(R.string.move)) }
            IconButton(onClick = { vm.share(vm.ui.value.selection.toList()) }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
            IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, null) }
        }
    }
}

@Composable
private fun SearchBar(text: String, searching: Boolean, count: Int, vm: ExplorerViewModel, onSearch: (String) -> Unit, onClose: () -> Unit) {
    var value by remember { mutableStateOf(text) }
    val history by vm.searchHistory.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = value, onValueChange = { value = it; onSearch(it) }, singleLine = true,
            placeholder = { Text("Search…  ext:pdf  size>5mb  after:2024-01-01") },
            leadingIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { value = ""; onSearch("") }) { Icon(Icons.Filled.Close, null) } },
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
        )
        Text(if (searching) "Searching… $count found" else if (value.isNotBlank()) "$count result(s)" else "Searches this folder and all subfolders",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        if (value.isBlank() && history.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            history.forEach { h -> com.lyvt.files.ui.Pill((if (h.saved) "★ " else "") + h.query, h.saved) { value = h.query; onSearch(h.query) } }
            TextButton(onClick = vm::clearSearchHistory) { Text("Clear history") }
        }
        if (value.isNotBlank() && history.none { it.query == value.trim() && it.saved }) TextButton(onClick = { vm.saveSearch(value.trim()) }) { Text("★ Save this search") }
    }
}

@Composable
private fun TabsRow(ui: ExplorerUi, vm: ExplorerViewModel) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ui.tabs.forEachIndexed { i, t ->
            Surface(onClick = { vm.selectTab(i) }, shape = CircleShape,
                color = if (i == ui.activeTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (i == ui.activeTab) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(File(t.path).name.ifEmpty { "/" }, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    IconButton(onClick = { vm.closeTab(i) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Close tab", Modifier.size(14.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, root: String, onOpen: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    val parts = buildList {
        if (path.startsWith(root)) {
            add("Storage" to root)
            var acc = root
            path.removePrefix(root).split('/').filter { it.isNotEmpty() }.forEach { acc = "$acc/$it"; add(it to acc) }
        } else {
            var acc = ""
            path.split('/').filter { it.isNotEmpty() }.forEach { acc = "$acc/$it"; add(it to acc) }
        }
    }
    Row(Modifier.horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, (name, p) ->
            val last = i == parts.lastIndex
            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (last) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(enabled = !last) { onOpen(p) }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
            if (!last) Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OperationCard(op: OperationState, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(op.title + (op.progress?.let { " · ${it.itemsDone}/${it.itemsTotal}" } ?: ""), style = MaterialTheme.typography.titleSmall)
                    op.progress?.let { Text("${it.currentName} · ${Formatters.size(it.bytesDone)} / ${Formatters.size(it.bytesTotal)}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            Spacer(Modifier.height(8.dp))
            if (op.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { op.progress.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Suppress("unused") private val unusedIcons = listOf(Icons.Filled.CreateNewFolder, Icons.Filled.NoteAdd, Icons.Filled.Delete, Icons.Filled.DriveFileRenameOutline)
