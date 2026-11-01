package com.lyvt.files.feature.library

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.lyvt.files.core.database.CollectionEntity
import com.lyvt.files.core.storage.LibraryRepository
import com.lyvt.files.ui.Accents
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.Pill
import com.lyvt.files.ui.Routes
import com.lyvt.files.ui.SectionHeader
import com.lyvt.files.ui.TextInputDialog
import com.lyvt.files.ui.openFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(val repo: LibraryRepository) : ViewModel() {
    val collections = repo.allCollections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tags = repo.allTags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notes = repo.allNotes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val openCollection = MutableStateFlow<CollectionEntity?>(null)
    val openTag = MutableStateFlow<String?>(null)
    val collectionItems = openCollection.flatMapLatest { c -> c?.let { repo.items(it.id) } ?: flowOf(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tagPaths = openTag.flatMapLatest { t -> t?.let { repo.pathsForTag(it) } ?: flowOf(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun create(name: String) = viewModelScope.launch { repo.createCollection(name, (0 until Accents.size).random()) }
    fun delete(c: CollectionEntity) = viewModelScope.launch { repo.deleteCollection(c.id); openCollection.value = null }
    fun remove(c: CollectionEntity, path: String) = viewModelScope.launch { repo.removeFromCollection(c.id, path) }
    private val backup get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "astrum-notes.json")
    suspend fun export() = withContext(Dispatchers.IO) { runCatching { backup.parentFile?.mkdirs(); backup.writeText(repo.exportJson()); "Saved Documents/${backup.name}" }.getOrElse { "Export failed: ${it.message}" } }
    suspend fun import() = withContext(Dispatchers.IO) { runCatching { "Imported ${repo.importJson(backup.readText())} entries" }.getOrElse { "Import failed: ${it.message}" } }
    suspend fun relink(path: String, size: Long) = repo.relink(path, size)
}

@Composable
fun LibraryScreen(nav: NavHostController, vm: LibraryViewModel = hiltViewModel()) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val openC by vm.openCollection.collectAsStateWithLifecycle()
    val openT by vm.openTag.collectAsStateWithLifecycle()
    val items by vm.collectionItems.collectAsStateWithLifecycle()
    val tagPaths by vm.tagPaths.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var create by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun open(p: String) { if (File(p).isDirectory) nav.navigate(Routes.files(p)) else if (!nav.openFile(p)) nav.navigate(Routes.files(File(p).parent)) }

    @Composable
    fun PathRow(p: String, size: Long, onRemove: (() -> Unit)?) {
        val exists = File(p).exists()
        Row(Modifier.fillMaxWidth().clickable(enabled = exists) { open(p) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(File(p).name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(if (exists) File(p).parent.orEmpty() else "Missing — moved outside the app or deleted", style = MaterialTheme.typography.labelSmall,
                    color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!exists) TextButton(onClick = { scope.launch { snackbar.showSnackbar(vm.relink(p, size)?.let { "Found at $it" } ?: "Not found on storage") } }) { Text("Find") }
            onRemove?.let { IconButton(onClick = it) { Icon(Icons.Filled.Close, "Remove") } }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (openC != null) vm.openCollection.value = null else if (openT != null) vm.openTag.value = null else nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Text(openC?.name ?: openT?.let { "#$it" } ?: "Collections & notes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (openC != null) IconButton(onClick = { vm.delete(openC!!) }) { Icon(Icons.Filled.Delete, "Delete collection") }
                    else if (openT == null && tab == 0) IconButton(onClick = { create = true }) { Icon(Icons.Filled.Add, "New collection") }
                }
            }
            when {
                openC != null -> {
                    if (items.isEmpty()) item { EmptyState(Icons.Outlined.CollectionsBookmark, "Empty collection", "Add files from a file's ⋮ menu → Properties → Collections.") }
                    items(items, key = { it.path }) { PathRow(it.path, it.size) { vm.remove(openC!!, it.path) } }
                }
                openT != null -> items(tagPaths, key = { it }) { PathRow(it, 0, null) }
                else -> {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Collections", tab == 0) { tab = 0 }; Pill("Tags", tab == 1) { tab = 1 }; Pill("Notes", tab == 2) { tab = 2 }
                        }
                    }
                    when (tab) {
                        0 -> if (collections.isEmpty()) item { EmptyState(Icons.Outlined.CollectionsBookmark, "No collections", "Group related files from anywhere on your device.") }
                        else items(collections, key = { it.id }) { c ->
                            LyvtCard(padding = 14.dp, onClick = { vm.openCollection.value = c }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(14.dp).background(Accents.getOrElse(c.color) { Accents[0] }, CircleShape))
                                    Spacer(Modifier.width(12.dp)); Text(c.name, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                        1 -> if (tags.isEmpty()) item { Text("No tags yet. Add tags in a file's Properties.", style = MaterialTheme.typography.bodyMedium) }
                        else items(tags, key = { it }) { t -> LyvtCard(padding = 14.dp, onClick = { vm.openTag.value = t }) { Text("#$t", style = MaterialTheme.typography.titleMedium) } }
                        else -> {
                            item {
                                Row { TextButton(onClick = { scope.launch { snackbar.showSnackbar(vm.export()) } }) { Text("Export notes & tags") }
                                    TextButton(onClick = { scope.launch { snackbar.showSnackbar(vm.import()) } }) { Text("Import") } }
                            }
                            if (notes.isEmpty()) item { Text("No notes yet.", style = MaterialTheme.typography.bodyMedium) }
                            items(notes, key = { it.path }) { n ->
                                LyvtCard(padding = 14.dp) { PathRow(n.path, n.size, null); Text(n.note, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (create) TextInputDialog("New collection", label = "Name", confirm = "Create", onConfirm = vm::create, onDismiss = { create = false })
}
