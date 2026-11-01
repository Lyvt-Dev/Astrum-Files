package com.astrum.desktop

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.scan.DuplicateFinder
import com.lyvt.files.core.scan.ScanResult
import com.lyvt.files.core.scan.StorageScanner
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.imageio.ImageIO

/** AWT implementation of the phone's perceptual hash and sharpness heuristics. */
object ImageAnalysis {
    private fun gray(f: File, w: Int, h: Int): IntArray? = runCatching {
        if (f.length() > 40L * 1024 * 1024) return null
        val img = ImageIO.read(f) ?: return null
        val s = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
        s.createGraphics().apply { setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(img, 0, 0, w, h, null); dispose() }
        IntArray(w * h) { s.raster.getSample(it % w, it / w, 0) }
    }.getOrNull()
    fun dHash(f: File): Long? { val g = gray(f, 9, 8) ?: return null; var h = 0L; for (y in 0 until 8) for (x in 0 until 8) { h = h shl 1; if (g[y * 9 + x] > g[y * 9 + x + 1]) h = h or 1 }; return h }
    fun sharpness(f: File): Double? { val w = 256; val h = 192; val g = gray(f, w, h) ?: return null; var s = 0.0; var q = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) { val i = y * w + x; val l = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble(); s += l; q += l * l; n++ }
        val m = s / n; return q / n - m * m }
    fun cluster(items: List<Pair<File, Long>>, t: Int): List<List<File>> {
        val p = IntArray(items.size) { it }; fun find(x: Int): Int { var r = x; while (p[r] != r) r = p[r]; p[x] = r; return r }
        for (i in items.indices) for (j in i + 1 until items.size) if (java.lang.Long.bitCount(items[i].second xor items[j].second) <= t) p[find(i)] = find(j)
        return items.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { items[it].first } }
    }
}

/** One shared scan of the chosen root for Cleaner and Analytics. Total for the % is estimated from used disk space. */
object Scanner {
    var root by mutableStateOf(AppState.home)
    var result by mutableStateOf<ScanResult?>(null)
    var running by mutableStateOf(false)
    var files by mutableStateOf(0); var bytes by mutableStateOf(0L); var estimate by mutableStateOf(0L)
    val fraction: Float? get() = if (estimate > 0) (bytes.toFloat() / estimate).coerceIn(0f, 0.99f) else null
    private var job: Job? = null
    suspend fun get(force: Boolean = false): ScanResult? {
        result?.takeIf { !force && it.root == root.absolutePath && System.currentTimeMillis() - it.scannedAt < 10 * 60_000 }?.let { return it }
        running = true; files = 0; bytes = 0
        // Estimate: the folder's size is unknown up front; use the drive's used space when scanning a drive root, otherwise previous scan or 0.
        val drive = root.toPath().root?.toFile()
        estimate = if (drive != null && root.absolutePath == drive.absolutePath) drive.totalSpace - drive.usableSpace else result?.takeIf { it.root == root.absolutePath }?.totalBytes ?: 0
        val excl = AppState.scanExclusions
        return try { withContext(Dispatchers.IO) { StorageScanner.scan(root, excl) { f, b -> files = f; bytes = b } }.also { result = it } } finally { running = false }
    }
}

enum class CMode(val title: String, val desc: String) { DUP("Duplicate files", "Exact copies verified by SHA-256"), SIMILAR("Similar photos", "Near-identical shots (perceptual hash)"),
    BLURRY("Blurry photos", "Possibly out-of-focus photos"), LARGE("Large files", "Files above a size you choose"), OLD("Old files", "Not modified for a long time"),
    EMPTY("Empty folders", "Folders with nothing inside"), DOWNLOADS("Downloads & installers", "Old downloads, .exe/.msi installers, partial files"),
    ARCHIVES("Extracted archives", "Archives already extracted next to them") }
data class CGroup(val title: String, val files: List<FileItem>, val note: String? = null)

@Composable
fun CleanerScreen() {
    var mode by remember { mutableStateOf<CMode?>(null) }
    val groups = remember { mutableStateListOf<CGroup>() }
    val selected = remember { mutableStateListOf<String>() }
    var phase by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var largeMb by remember { mutableStateOf(500f) }; var oldMonths by remember { mutableStateOf(12f) }; var sim by remember { mutableStateOf(8f) }; var blur by remember { mutableStateOf(60f) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun run(m: CMode, force: Boolean = false) {
        job?.cancel(); mode = m; groups.clear(); selected.clear(); status = null; phase = null
        job = scope.launch {
            val scan = Scanner.get(force) ?: return@launch
            val files = scan.allFiles
            val g: List<CGroup> = withContext(Dispatchers.IO) { when (m) {
                CMode.DUP -> DuplicateFinder.find(files, 1024) { d, t -> phase = "Comparing files · $d of $t" to (if (t > 0) d.toFloat() / t else null) }.also { r ->
                    withContext(Dispatchers.Main) { selected.addAll(DuplicateFinder.defaultSelection(r)) } }
                    .map { CGroup("${it.files.size} copies · ${Formatters.size(it.size)} each", it.files.map { f -> FileItem.from(f) }, "SHA-256 ${it.hash.take(12)}…") }
                CMode.SIMILAR -> { val imgs = files.filter { FileCategory.of(it.extension) == FileCategory.IMAGE && it.length() > 20_000 }.take(5000)
                    val h = imgs.mapIndexedNotNull { i, f -> currentCoroutineContext().ensureActive(); if (i % 10 == 0) phase = "Analysing photos · $i of ${imgs.size}" to i.toFloat() / imgs.size; ImageAnalysis.dHash(f)?.let { f to it } }
                    ImageAnalysis.cluster(h, sim.toInt()).map { c -> CGroup("${c.size} similar photos", c.sortedByDescending { it.length() }.map { FileItem.from(it) }, "Visually similar — review before deleting") } }
                CMode.BLURRY -> { val imgs = files.filter { FileCategory.of(it.extension) == FileCategory.IMAGE && it.length() > 20_000 }.take(4000)
                    val b = imgs.mapIndexedNotNull { i, f -> currentCoroutineContext().ensureActive(); if (i % 10 == 0) phase = "Measuring sharpness · $i of ${imgs.size}" to i.toFloat() / imgs.size
                        ImageAnalysis.sharpness(f)?.takeIf { it < blur }?.let { f to it } }.sortedBy { it.second }
                    if (b.isEmpty()) emptyList() else listOf(CGroup("${b.size} possibly blurry photos", b.map { FileItem.from(it.first) }, "Heuristic (Laplacian variance) — artistic or low-detail shots may appear")) }
                CMode.LARGE -> files.filter { it.length() >= largeMb.toLong() * 1024 * 1024 }.sortedByDescending { it.length() }.map { FileItem.from(it) }.groupBy { it.category }
                    .map { (c, l) -> CGroup("${c.name.lowercase().replaceFirstChar(Char::uppercase)} · ${l.size}", l) }
                CMode.OLD -> { val cut = System.currentTimeMillis() - oldMonths.toLong() * 30 * 86_400_000
                    files.filter { it.lastModified() in 1 until cut }.sortedByDescending { it.length() }.take(3000).map { FileItem.from(it) }.takeIf { it.isNotEmpty() }
                        ?.let { listOf(CGroup("${it.size} files not modified in ${oldMonths.toInt()} months", it, "Based on modification date")) } ?: emptyList() }
                CMode.EMPTY -> scan.emptyFolders.map(::File).filter { it.list()?.isEmpty() == true }.map { FileItem.from(it) }.takeIf { it.isNotEmpty() }?.let { listOf(CGroup("${it.size} empty folders", it)) } ?: emptyList()
                CMode.DOWNLOADS -> { val dl = AppState.knownFolder("Downloads").absolutePath; val inDl = files.filter { it.absolutePath.startsWith(dl) }
                    listOfNotNull(inDl.filter { it.extension.lowercase() in setOf("exe", "msi", "apk") }.takeIf { it.isNotEmpty() }?.let { CGroup("Installers (.exe / .msi / .apk)", it.map { f -> FileItem.from(f) }, "Usually not needed after installing") },
                        inDl.filter { it.extension.lowercase() in setOf("tmp", "part", "crdownload", "partial") }.takeIf { it.isNotEmpty() }?.let { CGroup("Incomplete downloads", it.map { f -> FileItem.from(f) }) },
                        inDl.filter { System.currentTimeMillis() - it.lastModified() > 90L * 86_400_000 && it.extension.lowercase() !in setOf("exe", "msi", "apk") }.takeIf { it.isNotEmpty() }
                            ?.let { CGroup("Downloads older than 90 days", it.sortedByDescending { f -> f.length() }.map { f -> FileItem.from(f) }) }) }
                CMode.ARCHIVES -> files.filter { FileCategory.of(it.extension) == FileCategory.ARCHIVE }.filter { a -> File(a.parentFile, a.name.removeSuffix(".tar.gz").substringBeforeLast('.')).isDirectory }
                    .map { FileItem.from(it) }.takeIf { it.isNotEmpty() }?.let { listOf(CGroup("${it.size} archives with an extracted folder next to them", it, "Check the folder is complete before deleting")) } ?: emptyList()
            } }
            phase = null; groups.addAll(g)
            status = "Scanned ${scan.fileCount} files (${Formatters.size(scan.totalBytes)}) in ${scan.root}" + if (scan.inaccessible > 0) " · ${scan.inaccessible} folders not accessible" else ""
        }
    }
    val selBytes = groups.flatMap { it.files }.distinctBy { it.path }.filter { it.path in selected }.sumOf { it.size }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cleaner", style = MaterialTheme.typography.headlineMedium)
            Text("Nothing is deleted without your review. Deleted items go to the Recycle Bin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AstrumCard(padding = 12.dp) {
                Text("Scan location", style = MaterialTheme.typography.labelMedium); Text(Scanner.root.absolutePath, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Home", Scanner.root == AppState.home) { Scanner.root = AppState.home }
                    File.listRoots().forEach { r -> Pill(r.absolutePath, Scanner.root == r) { Scanner.root = r } }
                    Pill("Choose…", false) { pickFolder()?.let { Scanner.root = it } }
                }
            }
            listOf(CMode.DUP to Icons.Filled.ContentCopy, CMode.SIMILAR to Icons.Filled.PhotoLibrary, CMode.BLURRY to Icons.Filled.BlurOn, CMode.LARGE to Icons.Filled.Straighten,
                CMode.OLD to Icons.Filled.History, CMode.EMPTY to Icons.Filled.FolderOff, CMode.DOWNLOADS to Icons.Filled.Download, CMode.ARCHIVES to Icons.Filled.Inventory2).forEachIndexed { i, (m, ic) ->
                AstrumCard(padding = 12.dp, onClick = { run(m) }) { Row(verticalAlignment = Alignment.CenterVertically) { IconBadge(ic, Accents[i % Accents.size], 36.dp); Spacer(Modifier.width(10.dp))
                    Column { Text(m.title, style = MaterialTheme.typography.titleSmall, color = if (mode == m) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(m.desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            }
        }
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight().padding(18.dp)) {
            val m = mode
            if (m == null) { EmptyState(Icons.Outlined.CheckCircle, "Choose a cleaner", "Pick a tool on the left. The first scan of a location is reused by all tools for 10 minutes."); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (job?.isActive == true) TextButton(onClick = { job?.cancel(); phase = null; status = "Cancelled" }) { Text("Cancel") } else TextButton(onClick = { run(m, true) }) { Text("Rescan") }
            }
            when (m) {
                CMode.LARGE -> { Text("Minimum size: ${largeMb.toInt()} MB"); Slider(largeMb, { largeMb = it }, valueRange = 10f..5000f, onValueChangeFinished = { run(m) }) }
                CMode.OLD -> { Text("Not modified for ${oldMonths.toInt()} months"); Slider(oldMonths, { oldMonths = it }, valueRange = 1f..120f, onValueChangeFinished = { run(m) }) }
                CMode.SIMILAR -> { Text("Similarity tolerance ${sim.toInt()} bits (lower = stricter)"); Slider(sim, { sim = it }, valueRange = 0f..20f, onValueChangeFinished = { run(m) }) }
                CMode.BLURRY -> { Text("Sharpness threshold ${blur.toInt()}"); Slider(blur, { blur = it }, valueRange = 10f..400f, onValueChangeFinished = { run(m) }) }
                else -> Unit
            }
            if (Scanner.running) ProgressCard("Scanning ${Scanner.root.name.ifEmpty { Scanner.root.path }}", "${Scanner.files} files · ${Formatters.size(Scanner.bytes)} checked", Scanner.fraction, estimated = true)
            phase?.let { (t, f) -> ProgressCard(t.substringBefore(" ·"), t.substringAfter("· ", ""), f) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
            if (job?.isActive != true && groups.isEmpty() && status != null) EmptyState(Icons.Outlined.CheckCircle, "Nothing found", "This location looks tidy.")
            Box(Modifier.weight(1f)) {
                LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                    groups.forEachIndexed { gi, g ->
                        item("h$gi") { Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(g.title, style = MaterialTheme.typography.titleSmall); g.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                            val dupLike = m == CMode.DUP || m == CMode.SIMILAR
                            TextButton(onClick = { selected.removeAll(g.files.map { it.path }.toSet()); selected.addAll((if (dupLike) g.files.drop(1) else g.files).map { it.path }) }) { Text(if (dupLike) "Keep first" else "Select all") }
                        } }
                        items(g.files, key = { "g$gi" + it.path }) { f -> Row(Modifier.fillMaxWidth().clickable { if (f.path in selected) selected.remove(f.path) else selected.add(f.path) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(f.path in selected, { c -> if (c) selected.add(f.path) else selected.remove(f.path) }); FileThumb(f, 34.dp); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) { Text(f.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(File(f.path).parent.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                            Text("${Formatters.size(f.size)} · ${DateFormat.getDateInstance().format(Date(f.lastModified))}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { AppState.reveal(File(f.path)) }) { Text("Show") }
                        } }
                    }
                }
            }
            if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected · frees about ${Formatters.size(selBytes)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Button(onClick = { confirm = true }) { Text("Review & delete") }
            }
        }
    }
    if (confirm) {
        val full = if (mode == CMode.DUP) groups.filter { g -> g.files.all { it.path in selected } } else emptyList()
        if (full.isNotEmpty()) ConfirmDialog("Keep at least one copy", "${full.size} duplicate group(s) are fully selected. Unselect one copy in each.", "OK", onConfirm = {}, onDismiss = { confirm = false })
        else ConfirmDialog("Delete ${selected.size} item(s)?", "About ${Formatters.size(selBytes)} will be freed. Items go to the Recycle Bin where supported.", "Delete", true, onConfirm = {
            scope.launch {
                val paths = selected.toList()
                val ok = withContext(Dispatchers.IO) { paths.count { p -> val f = File(p)
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) runCatching { Desktop.getDesktop().moveToTrash(f) }.getOrDefault(false) else f.deleteRecursively() } }
                status = "Removed $ok of ${paths.size} item(s)"; AppState.log("clean", "$ok item(s) removed", ok == paths.size)
                val gone = paths.filterNot { File(it).exists() }.toSet()
                val updated = groups.map { g -> g.copy(files = g.files.filterNot { it.path in gone }) }.filter { it.files.size > if (mode == CMode.DUP || mode == CMode.SIMILAR) 1 else 0 }
                groups.clear(); groups.addAll(updated); selected.clear(); Scanner.result = null
            }
        }, onDismiss = { confirm = false })
    }
}
