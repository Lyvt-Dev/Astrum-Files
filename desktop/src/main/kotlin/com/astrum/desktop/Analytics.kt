package com.astrum.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.scan.DuplicateFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Records one real snapshot per drive per launch (at most every 6 h) for the timeline. */
object Snapshots {
    data class Snap(val drive: String, val total: Long, val used: Long, val at: Long)
    val all: MutableList<Snap> = runCatching { JSONArray(Store.read("snapshots.json") ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o -> Snap(o.getString("d"), o.getLong("t"), o.getLong("u"), o.getLong("at")) } } } }.getOrDefault(emptyList()).toMutableList()
    fun record() {
        File.listRoots().filter { it.totalSpace > 0 }.forEach { r -> val last = all.lastOrNull { it.drive == r.path }
            if (last == null || System.currentTimeMillis() - last.at > 6 * 3_600_000L) all += Snap(r.path, r.totalSpace, r.totalSpace - r.usableSpace, System.currentTimeMillis()) }
        Store.write("snapshots.json", JSONArray(all.takeLast(2000).map { JSONObject().put("d", it.drive).put("t", it.total).put("u", it.used).put("at", it.at) }).toString())
    }
}

@Composable
fun AnalyticsScreen() {
    val scope = rememberCoroutineScope()
    val s = Scanner.result?.takeIf { it.root == Scanner.root.absolutePath }
    var waste by remember { mutableStateOf<Long?>(null) }; var wasteP by remember { mutableStateOf<Float?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Storage analytics", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Pill("Home", Scanner.root == AppState.home) { Scanner.root = AppState.home }; Spacer(Modifier.width(6.dp))
            File.listRoots().forEach { r -> Pill(r.path, Scanner.root == r) { Scanner.root = r }; Spacer(Modifier.width(6.dp)) }
            Pill("Choose…", false) { pickFolder()?.let { Scanner.root = it } }; Spacer(Modifier.width(10.dp))
            if (!Scanner.running) Button(onClick = { scope.launch { Scanner.get(true); waste = null } }) { Text(if (s == null) "Scan" else "Rescan") }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { File.listRoots().filter { it.totalSpace > 0 }.forEach { r ->
            AstrumCard(Modifier.width(260.dp)) {
                Text("Drive ${r.path}", style = MaterialTheme.typography.titleSmall)
                Text("${Formatters.size(r.totalSpace - r.usableSpace)} of ${Formatters.size(r.totalSpace)} used", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp)); UsageBar((r.totalSpace - r.usableSpace).toFloat() / r.totalSpace)
                Text("${Formatters.size(r.usableSpace)} free", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } } } }
        if (Scanner.running) item { ProgressCard("Scanning ${Scanner.root.path}", "${Scanner.files} files · ${Formatters.size(Scanner.bytes)} checked", Scanner.fraction, estimated = true) }
        if (s == null && !Scanner.running) item { EmptyState(Icons.Outlined.Analytics, "No analysis yet", "Choose a location and scan to see where your space goes.") }
        if (s != null) {
            item { AstrumCard {
                SectionHeader("By category · ${Formatters.size(s.totalBytes)} in ${s.fileCount} files")
                val total = s.totalBytes.coerceAtLeast(1); val cats = s.byCategory.entries.sortedByDescending { it.value.first }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Donut(cats.map { it.value.first.toFloat() / total to it.key.color() }, Modifier.size(170.dp), 22.dp) { Text(Formatters.size(s.totalBytes), style = MaterialTheme.typography.titleSmall) }
                    Spacer(Modifier.width(24.dp))
                    Column { cats.forEach { (c, v) -> Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        AppState.openInExplorer(s.root); AppState.pendingSearch = when (c.name) { "APK" -> "type:apk"; "OTHER" -> ""; "DOCUMENT" -> "type:doc"; else -> "type:" + c.name.lowercase() } }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(c.color())); Spacer(Modifier.width(8.dp))
                        Text("${c.name.lowercase().replaceFirstChar(Char::uppercase)} · ${Formatters.size(v.first)} · ${v.second} files", style = MaterialTheme.typography.bodySmall) } } }
                }
            } }
            item { AstrumCard { SectionHeader("Folders (click to open)"); Treemap(s.topFolders.take(14).map { Triple(it.name, it.size, it.path) }) { AppState.openInExplorer(it) } } }
            item { AstrumCard { SectionHeader("Top extensions")
                val top = s.byExtension.take(10); val max = top.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                top.forEach { (e, size, n) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(".$e", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(80.dp))
                    Box(Modifier.weight(1f).height(14.dp)) { Box(Modifier.fillMaxWidth(size.toFloat() / max).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.primary)) }
                    Text("  ${Formatters.size(size)} · $n", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(130.dp)) } } } }
            item { AstrumCard { SectionHeader("Duplicate waste")
                when { waste != null -> Text("${Formatters.size(waste!!)} could be reclaimed by removing duplicate copies.")
                    wasteP != null -> ProgressCard("Hashing candidates", "", wasteP)
                    else -> TextButton(onClick = { scope.launch { wasteP = 0f; waste = withContext(Dispatchers.IO) { DuplicateFinder.find(s.allFiles, 1024) { d, t -> wasteP = if (t > 0) d.toFloat() / t else 0f }.sumOf { it.wasted } }; wasteP = null } }) { Text("Calculate") } } } }
            item { val drive = File(s.root).toPath().root?.toString(); val hist = Snapshots.all.filter { it.drive == drive }
                if (hist.size >= 2) AstrumCard { SectionHeader("Used space on $drive over time (${hist.size} snapshots)"); Trend(hist.map { it.at to it.used })
                    Text("${Formatters.shortDate(hist.first().at)} → ${Formatters.shortDate(hist.last().at)}", style = MaterialTheme.typography.labelSmall) } }
            item { SectionHeader("Largest files") }
            items(s.largestFiles.take(30), key = { "lf" + it.path }) { f -> Row(Modifier.fillMaxWidth().clickable { AppState.reveal(File(f.path)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FileThumb(f, 30.dp); Spacer(Modifier.width(10.dp)); Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(File(f.path).parent.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(360.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Formatters.size(f.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp)) } }
            if (s.inaccessible > 0) item { Text("${s.inaccessible} folders could not be read (permissions) and are excluded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Treemap(items: List<Triple<String, Long, String>>, onClick: (String) -> Unit) {
    if (items.isEmpty()) { Text("No subfolders"); return }
    val palette = listOf(Color(0xFF7548F8), Color(0xFF4C8DFF), Color(0xFF2EB67D), Color(0xFFFF8A3D), Color(0xFF00B3C7), Color(0xFFE5487A), Color(0xFFE8B95A), Color(0xFF8A94A6))
    BoxWithConstraints(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp))) {
        val d = LocalDensity.current; val w = with(d) { maxWidth.toPx() }; val h = with(d) { maxHeight.toPx() }
        var x = 0f; var y = 0f; var rw = w; var rh = h; var rem = items.sumOf { it.second }.toFloat().coerceAtLeast(1f)
        items.forEachIndexed { i, (n, size, p) ->
            val fr = size / rem; val (cx, cy, cw, ch) = if (i == items.lastIndex) listOf(x, y, rw, rh) else if (rw >= rh) listOf(x, y, rw * fr, rh).also { x += rw * fr; rw -= rw * fr } else listOf(x, y, rw, rh * fr).also { y += rh * fr; rh -= rh * fr }
            rem -= size
            with(d) { Box(Modifier.offset(cx.toDp(), cy.toDp()).size(cw.toDp(), ch.toDp()).padding(1.5.dp).clip(RoundedCornerShape(10.dp)).background(palette[i % palette.size].copy(alpha = 0.85f))
                .clickable { onClick(p) }.padding(6.dp)) { if (cw > 110 && ch > 44) Column { Text(n, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1); Text(Formatters.size(size), color = Color.White.copy(0.85f), style = MaterialTheme.typography.labelSmall) } } }
        }
    }
}

@Composable
fun Trend(points: List<Pair<Long, Long>>) {
    val c = MaterialTheme.colorScheme.primary; val g = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp)) {
        val t0 = points.first().first; val t1 = points.last().first.coerceAtLeast(t0 + 1); val v0 = points.minOf { it.second } * 0.98f; val v1 = (points.maxOf { it.second } * 1.02f).coerceAtLeast(v0 + 1)
        for (i in 0..3) drawLine(g, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3))
        val p = Path(); points.forEachIndexed { i, (t, v) -> val px = (t - t0).toFloat() / (t1 - t0) * size.width; val py = size.height - (v - v0) / (v1 - v0) * size.height; if (i == 0) p.moveTo(px, py) else p.lineTo(px, py) }
        drawPath(p, c, style = Stroke(3.dp.toPx()))
    }
}
