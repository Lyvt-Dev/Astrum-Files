package com.lyvt.files.feature.explorer

import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.settings.AppSettings
import com.lyvt.files.core.storage.BatchRename
import com.lyvt.files.core.storage.ConflictPolicy
import com.lyvt.files.core.storage.FileItem
import com.lyvt.files.core.storage.FileOperations
import com.lyvt.files.core.storage.Hashing
import com.lyvt.files.core.storage.SortField
import com.lyvt.files.ui.Accents
import com.lyvt.files.ui.ConfirmDialog
import com.lyvt.files.ui.Pill
import com.lyvt.files.ui.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ExplorerDialog {
    data object Create : ExplorerDialog
    data object NewFolder : ExplorerDialog
    data object NewFile : ExplorerDialog
    data object Sort : ExplorerDialog
    data object SelectionMenu : ExplorerDialog
    data class Rename(val item: FileItem) : ExplorerDialog
    data class Delete(val paths: List<String>) : ExplorerDialog
    data class Properties(val item: FileItem) : ExplorerDialog
    data class Conflict(val names: List<String>) : ExplorerDialog
    data class Compress(val paths: List<String>, val name: String) : ExplorerDialog
    data class BatchRenameDialog(val paths: List<String>) : ExplorerDialog
    data class Pin(val item: FileItem) : ExplorerDialog
    data class Compare(val a: String, val b: String) : ExplorerDialog
    data object SortIntoFolders : ExplorerDialog
}

@Composable
fun ExplorerDialogs(
    dialog: ExplorerDialog?, ui: ExplorerUi, vm: ExplorerViewModel, settings: AppSettings,
    onDismiss: () -> Unit, onDialog: (ExplorerDialog) -> Unit,
) {
    when (dialog) {
        null -> Unit
        ExplorerDialog.Create -> AlertDialog(onDismissRequest = onDismiss, title = { Text("Create") }, text = {
            Column {
                TextButton(onClick = { onDialog(ExplorerDialog.NewFolder) }) { Text(stringResource(R.string.new_folder)) }
                TextButton(onClick = { onDialog(ExplorerDialog.NewFile) }) { Text(stringResource(R.string.new_file)) }
            }
        }, confirmButton = {})
        ExplorerDialog.NewFolder -> TextInputDialog(stringResource(R.string.new_folder), label = "Name", confirm = "Create",
            validate = { n -> FileOperations.validateName(n) ?: if (File(ui.path, n).exists()) "Already exists" else null },
            onConfirm = { vm.createFolder(it) }, onDismiss = onDismiss)
        ExplorerDialog.NewFile -> TextInputDialog(stringResource(R.string.new_file), initial = "untitled.txt", label = "Name", confirm = "Create",
            validate = { n -> FileOperations.validateName(n) ?: if (File(ui.path, n).exists()) "Already exists" else null },
            onConfirm = { vm.createFile(it) }, onDismiss = onDismiss)
        is ExplorerDialog.Rename -> TextInputDialog(stringResource(R.string.rename), initial = dialog.item.name, label = "New name (extension can be changed)",
            confirm = stringResource(R.string.rename),
            validate = { n -> FileOperations.validateName(n) ?: if (n != dialog.item.name && File(File(dialog.item.path).parent, n).exists()) "Already exists" else null },
            onConfirm = { vm.rename(dialog.item.path, it) }, onDismiss = onDismiss)
        is ExplorerDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.delete_confirm_title, dialog.paths.size),
            message = if (settings.useTrash) stringResource(R.string.delete_confirm_trash, settings.trashRetentionDays) else stringResource(R.string.delete_confirm_perm),
            confirm = stringResource(R.string.delete), destructive = true, onConfirm = { vm.delete(dialog.paths) }, onDismiss = onDismiss)
        ExplorerDialog.Sort -> SortDialog(settings, vm, onDismiss)
        is ExplorerDialog.Conflict -> {
            var policy by remember { mutableStateOf(ConflictPolicy.KEEP_BOTH) }
            AlertDialog(onDismissRequest = onDismiss, title = { Text("${dialog.names.size} item(s) already exist") }, text = {
                Column {
                    Text(dialog.names.take(5).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    listOf(ConflictPolicy.KEEP_BOTH to "Keep both (rename new)", ConflictPolicy.SKIP to "Skip existing", ConflictPolicy.REPLACE to "Replace existing")
                        .forEach { (p, l) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(policy == p, { policy = p }); Text(l) } }
                }
            }, confirmButton = { TextButton(onClick = { vm.paste(policy); onDismiss() }) { Text(stringResource(R.string.paste_here)) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
        }
        is ExplorerDialog.Properties -> PropertiesDialog(dialog.item, vm, onDismiss)
        is ExplorerDialog.Compress -> CompressDialog(dialog, vm, onDismiss)
        is ExplorerDialog.BatchRenameDialog -> BatchRenameDialog(dialog.paths, ui, vm, onDismiss)
        is ExplorerDialog.Pin -> AlertDialog(onDismissRequest = onDismiss, title = { Text("Pin folder") }, text = {
            Column { Text("Choose a color label"); Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Accents.forEachIndexed { i, _ -> Pill("${i + 1}", false) { vm.pin(dialog.item, i); onDismiss() } } } }
        }, confirmButton = {})
        is ExplorerDialog.Compare -> CompareDialog(dialog.a, dialog.b, onDismiss)
        ExplorerDialog.SortIntoFolders -> SortIntoFoldersDialog(vm, onDismiss)
        ExplorerDialog.SelectionMenu -> {
            val sel = ui.selection.toList()
            AlertDialog(onDismissRequest = onDismiss, title = { Text("${sel.size} selected") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.selectAll(); onDismiss() }) { Text(stringResource(R.string.select_all)) }
                    TextButton(onClick = { vm.invertSelection(); onDismiss() }) { Text(stringResource(R.string.invert_selection)) }
                    if (sel.size == 1) TextButton(onClick = { ui.visibleItems.firstOrNull { it.path == sel[0] }?.let { onDialog(ExplorerDialog.Rename(it)) } }) { Text(stringResource(R.string.rename)) }
                    TextButton(onClick = { onDialog(ExplorerDialog.BatchRenameDialog(sel)) }) { Text(stringResource(R.string.batch_rename)) }
                    TextButton(onClick = { onDialog(ExplorerDialog.Compress(sel, File(sel[0]).nameWithoutExtension)) }) { Text(stringResource(R.string.compress)) }
                    if (sel.size == 2) TextButton(onClick = { onDialog(ExplorerDialog.Compare(sel[0], sel[1])) }) { Text("Compare") }
                    if (sel.size >= 2 && sel.all { it.endsWith(".pdf", true) }) TextButton(onClick = { vm.mergePdfs(sel); onDismiss() }) { Text("Merge ${sel.size} PDFs") }
                    TextButton(onClick = { vm.newCollection("Selection ${java.text.SimpleDateFormat("d MMM HH:mm").format(java.util.Date())}", sel); onDismiss() }) { Text("Save as collection") }
                    if (sel.size == 1) TextButton(onClick = { ui.visibleItems.firstOrNull { it.path == sel[0] }?.let { onDialog(ExplorerDialog.Properties(it)) } }) { Text(stringResource(R.string.properties)) }
                    TextButton(onClick = { onDialog(ExplorerDialog.Delete(sel)) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            }, confirmButton = {})
        }
    }
}

@Composable
private fun SortDialog(settings: AppSettings, vm: ExplorerViewModel, onDismiss: () -> Unit) {
    val s = settings.sort
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.sort)) }, text = {
        Column {
            SortField.entries.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.field == f, { vm.setSort(s.copy(field = f)) })
                    Text(when (f) { SortField.NAME -> "Name (natural)"; SortField.DATE -> "Date modified"; SortField.SIZE -> "Size"; SortField.TYPE -> "Type" })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.ascending, { vm.setSort(s.copy(ascending = it)) }); Text("Ascending") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(s.foldersFirst, { vm.setSort(s.copy(foldersFirst = it)) }); Text("Folders first") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}

@Composable
private fun PropertiesDialog(item: FileItem, vm: ExplorerViewModel, onDismiss: () -> Unit) {
    var folderInfo by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var sha by remember { mutableStateOf<String?>(null) }
    var md5 by remember { mutableStateOf<String?>(null) }
    var hashing by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf("") }
    var meta by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.path) {
        if (item.isDirectory) folderInfo = vm.folderSize(item.path)
        meta = withContext(Dispatchers.IO) { readMetadata(item) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(item.name) }, text = {
        SelectionContainer {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Prop("Location", File(item.path).parent.orEmpty())
                Prop("Type", if (item.isDirectory) "Folder" else vm.mime(item))
                if (item.isDirectory) Prop("Size", folderInfo?.let { "${Formatters.size(it.first)} · ${it.second} files" } ?: "Calculating…")
                else Prop("Size", "${Formatters.size(item.size)} (${item.size} bytes)")
                Prop("Modified", Formatters.date(item.lastModified))
                Prop("Readable / writable", "${File(item.path).canRead()} / ${File(item.path).canWrite()}")
                LibrarySection(item, vm)
                meta.forEach { (k, v) -> Prop(k, v) }
                if (!item.isDirectory) {
                    Spacer(Modifier.height(8.dp))
                    if (sha == null) TextButton(enabled = !hashing, onClick = {
                        hashing = true
                        scope.launch(Dispatchers.IO) {
                            val f = File(item.path)
                            sha = runCatching { Hashing.sha256(f) }.getOrElse { "Error: ${it.message}" }
                            md5 = runCatching { Hashing.md5(f) }.getOrElse { "Error" }
                            hashing = false
                        }
                    }) { Text(if (hashing) "Computing checksums…" else "Compute SHA-256 / MD5") }
                    sha?.let { Prop("SHA-256", it, mono = true) }
                    md5?.let { Prop("MD5", it, mono = true) }
                    sha?.let { h ->
                        OutlinedTextField(compare, { compare = it }, label = { Text("Compare with checksum") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                        if (compare.isNotBlank()) {
                            val match = compare.trim().equals(h, true) || compare.trim().equals(md5, true)
                            Text(if (match) "✓ Checksum matches" else "✗ Checksum does not match",
                                color = if (match) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}

private fun readMetadata(item: FileItem): List<Pair<String, String>> = runCatching {
    when (item.category) {
        com.lyvt.files.core.storage.FileCategory.IMAGE -> {
            val e = ExifInterface(item.path)
            listOfNotNull(
                e.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)?.let { w -> "Dimensions" to "$w × ${e.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)}" },
                e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { "Taken" to it },
                e.getAttribute(ExifInterface.TAG_MAKE)?.let { "Camera" to "$it ${e.getAttribute(ExifInterface.TAG_MODEL).orEmpty()}" },
                e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "Aperture" to "f/$it" },
                e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { "Exposure" to "${it}s" },
                e.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO" to it },
                FloatArray(2).takeIf { e.getLatLong(it) }?.let { "GPS" to "%.5f, %.5f".format(it[0], it[1]) },
            )
        }
        com.lyvt.files.core.storage.FileCategory.VIDEO, com.lyvt.files.core.storage.FileCategory.AUDIO -> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(item.path)
                listOfNotNull(
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { "Duration" to "%d:%02d".format(it / 60000, it / 1000 % 60) },
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { "Resolution" to "$it × ${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)}" },
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { "Bitrate" to "${it / 1000} kbps" },
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { "Title" to it },
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { "Artist" to it },
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { "Album" to it },
                )
            } finally { r.release() }
        }
        else -> emptyList()
    }
}.getOrDefault(emptyList())

@Composable
private fun Prop(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun CompressDialog(d: ExplorerDialog.Compress, vm: ExplorerViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(d.name) }
    var password by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(5f) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create ZIP") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text("Archive name") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(password, { password = it }, label = { Text("Password (optional, AES-256)") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Spacer(Modifier.height(8.dp))
            Text("Compression level: ${level.toInt()}")
            Slider(level, { level = it }, valueRange = 0f..9f, steps = 8)
        }
    }, confirmButton = { TextButton(enabled = FileOperations.validateName(name) == null, onClick = { vm.compress(d.paths, name, password, level.toInt()); onDismiss() }) { Text(stringResource(R.string.compress)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun BatchRenameDialog(paths: List<String>, ui: ExplorerUi, vm: ExplorerViewModel, onDismiss: () -> Unit) {
    var pattern by remember { mutableStateOf("{name}_{n}") }
    var start by remember { mutableStateOf("1") }
    var pad by remember { mutableStateOf("2") }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    val items = ui.visibleItems.filter { it.path in paths }
    val plan = BatchRename.plan(items, pattern, start.toIntOrNull() ?: 1, pad.toIntOrNull() ?: 0, find, replace, regex)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.batch_rename)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(pattern, { pattern = it }, label = { Text("Pattern: {name} {n} {date} {ext}") }, singleLine = true)
            Row { OutlinedTextField(start, { start = it.filter(Char::isDigit) }, label = { Text("Start") }, modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(pad, { pad = it.filter(Char::isDigit) }, label = { Text("Digits") }, modifier = Modifier.weight(1f), singleLine = true) }
            OutlinedTextField(find, { find = it }, label = { Text("Find") }, singleLine = true)
            OutlinedTextField(replace, { replace = it }, label = { Text("Replace with") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(regex, { regex = it }); Spacer(Modifier.padding(4.dp)); Text("Regular expression") }
            Spacer(Modifier.height(8.dp)); Text("Preview", style = MaterialTheme.typography.titleSmall)
            plan.take(8).forEach { p -> Text("${p.from.name} → ${p.to}${p.error?.let { "  ⚠ $it" } ?: ""}", style = MaterialTheme.typography.bodySmall,
                color = if (p.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            if (plan.size > 8) Text("… and ${plan.size - 8} more", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = plan.none { it.error != null }, onClick = {
        vm.batchRename(paths, pattern, start.toIntOrNull() ?: 1, pad.toIntOrNull() ?: 0, find, replace, regex); onDismiss() }) { Text(stringResource(R.string.rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CompareDialog(a: String, b: String, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(a, b) {
        result = withContext(Dispatchers.IO) {
            val fa = File(a); val fb = File(b)
            if (fa.isDirectory && fb.isDirectory) {
                val la = fa.walkTopDown().filter { it.isFile }.associate { it.relativeTo(fa).path to it.length() }
                val lb = fb.walkTopDown().filter { it.isFile }.associate { it.relativeTo(fb).path to it.length() }
                val onlyA = la.keys - lb.keys; val onlyB = lb.keys - la.keys
                val diff = la.keys.intersect(lb.keys).filter { la[it] != lb[it] }
                "Only in ${fa.name}: ${onlyA.size}\n${onlyA.take(10).joinToString("\n") { "  $it" }}\n\nOnly in ${fb.name}: ${onlyB.size}\n${onlyB.take(10).joinToString("\n") { "  $it" }}\n\nDifferent size: ${diff.size}\n${diff.take(10).joinToString("\n") { "  $it" }}"
            } else if (fa.isFile && fb.isFile) {
                if (fa.length() != fb.length()) "Files differ (size ${Formatters.size(fa.length())} vs ${Formatters.size(fb.length())})"
                else if (Hashing.sha256(fa) == Hashing.sha256(fb)) "Files are identical (SHA-256 match)" else "Files differ in content"
            } else "Select two files or two folders to compare"
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Compare") }, text = {
        Text(result ?: "Comparing…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}

@Composable
private fun LibrarySection(item: FileItem, vm: ExplorerViewModel) {
    val note by vm.library.note(item.path).collectAsStateWithLifecycle(null)
    val tags by vm.library.tagsFor(item.path).collectAsStateWithLifecycle(emptyList())
    val collections by vm.collections.collectAsStateWithLifecycle()
    var noteText by remember(note) { mutableStateOf(note?.note ?: "") }
    var tagText by remember { mutableStateOf("") }
    var newCol by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Text("Note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(noteText, { noteText = it }, placeholder = { Text("Add a personal note") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
    if (noteText != (note?.note ?: "")) TextButton(onClick = { vm.setNote(item.path, noteText) }) { Text("Save note") }
    Text("Tags", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { t -> Pill("#$t ✕", true) { vm.removeTag(item.path, t) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(tagText, { tagText = it.replace(" ", "-") }, placeholder = { Text("new tag") }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
        TextButton(enabled = tagText.isNotBlank(), onClick = { vm.addTag(item.path, tagText); tagText = "" }) { Text("Add") }
    }
    Text("Collections", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        collections.forEach { c -> Pill("+ ${c.name}", false) { vm.addToCollection(c.id, listOf(item.path)) } }
        Pill("New…", false) { newCol = true }
    }
    if (newCol) TextInputDialog("New collection", label = "Name", confirm = "Create", onConfirm = { vm.newCollection(it, listOf(item.path)) }, onDismiss = { newCol = false })
}

private data class SortPreset(val label: String, val folder: String, val extensions: String)
private val sortPresets = listOf(
    SortPreset("Archives", "Archives", "zip,7z,rar,tar,gz,tgz,bz2,xz"),
    SortPreset("Images", "Images", "jpg,jpeg,png,gif,webp,heic,heif,bmp,avif"),
    SortPreset("Videos", "Videos", "mp4,mkv,webm,3gp,mov,avi,m4v"),
    SortPreset("Audio", "Music", "mp3,m4a,aac,flac,ogg,opus,wav"),
    SortPreset("Documents", "Documents", "pdf,doc,docx,xls,xlsx,ppt,pptx,odt,ods,txt,rtf,csv,epub"),
    SortPreset("APKs", "APKs", "apk,apks,xapk"),
)

@Composable
private fun SortIntoFoldersDialog(vm: ExplorerViewModel, onDismiss: () -> Unit) {
    val preview by vm.sortPreview.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf("type") }
    var preset by remember { mutableStateOf(sortPresets[0]) }
    var folder by remember { mutableStateOf(sortPresets[0].folder) }
    var exts by remember { mutableStateOf(sortPresets[0].extensions) }
    val p = preview
    if (p != null) {
        val uiState by vm.ui.collectAsStateWithLifecycle()
        val root = uiState.path
        val groups = p.moves.groupBy { it.to.parentFile!!.absolutePath.removePrefix(root).trimStart('/') }
        AlertDialog(onDismissRequest = { vm.sortPreview.value = null; onDismiss() }, title = { Text("Preview") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (p.moves.isEmpty()) Text("Nothing to sort here — no matching files directly in this folder.")
                else {
                    Text("${p.moves.size} file(s) · ${Formatters.size(p.totalBytes)}. Existing files are never overwritten; you can undo afterwards.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    groups.toSortedMap().forEach { (dir, moves) ->
                        Text("📁 $dir  (${moves.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                        moves.take(4).forEach { Text("   ${it.from.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        if (moves.size > 4) Text("   … ${moves.size - 4} more", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = p.moves.isNotEmpty(), onClick = { vm.runSort(); onDismiss() }) { Text("Sort ${p.moves.size} files") } },
            dismissButton = { TextButton(onClick = { vm.sortPreview.value = null }) { Text("Back") } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sort into folders") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Moves files in this folder into subfolders.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            listOf("type" to "Everything by type (Images, Videos, Archives…)", "pick" to "Only certain files into one folder",
                "ext" to "Everything by extension (ZIP, PDF, …)", "month" to "Everything by month (2024-05)").forEach { (k, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == k, { mode = k }); Text(l, style = MaterialTheme.typography.bodyMedium) }
            }
            if (mode == "pick") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sortPresets.forEach { sp -> Pill(sp.label, preset == sp) { preset = sp; folder = sp.folder; exts = sp.extensions } }
                }
                OutlinedTextField(folder, { folder = it }, label = { Text("Folder name") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    isError = FileOperations.validateName(folder) != null)
                OutlinedTextField(exts, { exts = it }, label = { Text("Extensions") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            }
        }
    }, confirmButton = {
        TextButton(enabled = mode != "pick" || (FileOperations.validateName(folder) == null && exts.isNotBlank()), onClick = {
            when (mode) {
                "type" -> vm.previewSort("Sort by type", "{category}", "")
                "ext" -> vm.previewSort("Sort by extension", "{ext}", "")
                "month" -> vm.previewSort("Sort by month", "{yyyy}-{MM}", "")
                else -> vm.previewSort("Sort ${folder.trim()}", folder.trim(), exts)
            }
        }) { Text("Preview") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
