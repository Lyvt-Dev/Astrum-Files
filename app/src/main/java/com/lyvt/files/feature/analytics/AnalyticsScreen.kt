package com.lyvt.files.feature.analytics

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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.lyvt.files.R
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.scan.FolderStat
import com.lyvt.files.feature.home.categoryQuery
import com.lyvt.files.ui.DonutChart
import com.lyvt.files.ui.EmptyState
import com.lyvt.files.ui.FileRow
import com.lyvt.files.ui.LoadingState
import com.lyvt.files.ui.LyvtCard
import com.lyvt.files.ui.Routes
import com.lyvt.files.ui.SectionHeader
import com.lyvt.files.ui.color
import com.lyvt.files.ui.openFile

private val treemapPalette = listOf(Color(0xFF4C8DFF), Color(0xFF7A5CFF), Color(0xFF2EB67D), Color(0xFFFF8A3D), Color(0xFF00B3C7), Color(0xFFE5487A), Color(0xFFE9B10E), Color(0xFF8A94A6))

@Composable
fun AnalyticsScreen(nav: NavHostController, vm: AnalyticsViewModel = hiltViewModel()) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val waste by vm.duplicateWaste.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.analytics_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (!running) TextButton(onClick = { vm.scan(true) }) { Text(stringResource(if (scan == null) R.string.scan else R.string.rescan)) }
            }
        }
        volume?.let { v ->
            item {
                LyvtCard {
                    Text("Device storage (reported by Android)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Formatters.size(v.usedBytes)} used of ${Formatters.size(v.totalBytes)} · ${Formatters.size(v.freeBytes)} free", style = MaterialTheme.typography.titleMedium)
                    scan?.let { s ->
                        Spacer(Modifier.height(6.dp))
                        Text("Lyvt can inspect ${Formatters.size(s.totalBytes)} of it (${Formatters.percent(s.totalBytes, v.usedBytes)}%). " +
                            "The rest is the system, apps and their private data, which Android keeps inaccessible.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (running) item { LoadingState(stringResource(R.string.scanning), "$progress files indexed") }
        val s = scan
        if (s == null && !running) item {
            EmptyState(Icons.Outlined.Analytics, "No analysis yet", "Run a scan to see where your space goes.") { Button(onClick = { vm.scan(false) }) { Text(stringResource(R.string.scan)) } }
        }
        if (s != null) {
            item {
                LyvtCard {
                    SectionHeader("By category")
                    val total = s.totalBytes.coerceAtLeast(1)
                    val cats = s.byCategory.entries.sortedByDescending { it.value.first }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(cats.map { it.value.first.toFloat() / total to it.key.color() }, Modifier.size(140.dp), stroke = 20.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Formatters.size(s.totalBytes), style = MaterialTheme.typography.titleSmall)
                                Text("${s.fileCount} files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            cats.forEach { (c, v) ->
                                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { categoryQuery(c).takeIf { it.isNotEmpty() }?.let { nav.navigate(Routes.files(null, it)) } }
                                    .padding(vertical = 3.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(c.color()))
                                    Spacer(Modifier.width(8.dp))
                                    Text("${c.name.lowercase().replaceFirstChar(Char::uppercase)}  ${Formatters.size(v.first)} · ${v.second}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Folders (tap to open)")
                    Treemap(s.topFolders.take(12)) { nav.navigate(Routes.files(it.path)) }
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Top extensions")
                    val top = s.byExtension.take(8)
                    val max = top.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                    top.forEach { (ext, size, count) ->
                        Row(Modifier.fillMaxWidth().clickable { if (ext != "(none)") nav.navigate(Routes.files(null, "ext:$ext")) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(".$ext", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.weight(1f).height(14.dp)) {
                                Box(Modifier.fillMaxWidth(size.toFloat() / max).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.primary))
                            }
                            Text("  ${Formatters.size(size)} · $count", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item {
                LyvtCard {
                    SectionHeader("Duplicate waste")
                    when {
                        waste == null -> TextButton(onClick = vm::computeWaste) { Text("Calculate (hashes candidate files)") }
                        waste!! < 0 -> Text("Calculating…")
                        else -> Text("${Formatters.size(waste!!)} could be reclaimed by removing duplicate copies.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (history.size >= 2) item {
                LyvtCard {
                    SectionHeader("Used storage over time")
                    TrendChart(history.map { it.timestamp to it.usedBytes })
                    Text("${Formatters.shortDate(history.first().timestamp)} → ${Formatters.shortDate(history.last().timestamp)} · ${history.size} snapshots",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeader("Largest files") }
            items(s.largestFiles.take(25), key = { "lf" + it.path }) { f ->
                FileRow(f, false, false, compact = true, showThumbnails = true,
                    onClick = { if (!nav.openFile(f.path)) nav.navigate(Routes.files(f.file.parent)) }, onLongClick = { nav.navigate(Routes.files(f.file.parent)) })
            }
            if (s.inaccessible > 0) item {
                Text("${s.inaccessible} folders could not be read (e.g. Android/data) and are excluded.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Slice-and-dice treemap alternating orientation; areas are proportional to folder size. */
@Composable
private fun Treemap(folders: List<FolderStat>, onClick: (FolderStat) -> Unit) {
    if (folders.isEmpty()) { Text("No folders"); return }
    BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        data class R(val f: FolderStat, val x: Float, val y: Float, val w: Float, val h: Float, val color: Color)
        val rects = mutableListOf<R>()
        var x = 0f; var y = 0f; var rw = w; var rh = h
        var remaining = folders.sumOf { it.size }.toFloat().coerceAtLeast(1f)
        folders.forEachIndexed { i, f ->
            val frac = f.size / remaining
            val color = treemapPalette[i % treemapPalette.size]
            if (i == folders.lastIndex) rects += R(f, x, y, rw, rh, color)
            else if (rw >= rh) { val cw = rw * frac; rects += R(f, x, y, cw, rh, color); x += cw; rw -= cw }
            else { val ch = rh * frac; rects += R(f, x, y, rw, ch, color); y += ch; rh -= ch }
            remaining -= f.size
        }
        rects.forEach { r ->
            with(density) {
                Box(Modifier.offset(r.x.toDp(), r.y.toDp()).size(r.w.toDp(), r.h.toDp()).padding(1.5.dp).clip(RoundedCornerShape(10.dp))
                    .background(r.color.copy(alpha = 0.85f)).clickable { onClick(r.f) }.padding(6.dp)) {
                    if (r.w > 120 && r.h > 60) Column {
                        Text(r.f.name, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Formatters.size(r.f.size), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendChart(points: List<Pair<Long, Long>>) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp)) {
        val minT = points.first().first; val maxT = points.last().first.coerceAtLeast(minT + 1)
        val minV = points.minOf { it.second } * 0.98f; val maxV = (points.maxOf { it.second } * 1.02f).coerceAtLeast(minV + 1)
        for (i in 0..3) { val gy = size.height * i / 3; drawLine(grid, Offset(0f, gy), Offset(size.width, gy)) }
        val path = Path()
        points.forEachIndexed { i, (t, v) ->
            val px = (t - minT).toFloat() / (maxT - minT) * size.width
            val py = size.height - (v - minV) / (maxV - minV) * size.height
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}
