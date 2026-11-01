package com.lyvt.files.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lyvt.files.core.common.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfViewerScreen(nav: NavHostController, path: String) {
    val ctx = LocalContext.current
    val file = File(path)
    val holder = remember(path) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
    }
    DisposableEffect(holder) { onDispose { holder.getOrNull()?.close() } }
    val renderer = holder.getOrNull()
    val mutex = remember { Mutex() }
    var scale by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val prefs = ctx.getSharedPreferences("pdf_bookmarks", Context.MODE_PRIVATE)
    var bookmarks by remember { mutableStateOf(prefs.getStringSet(path, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSortedSet()) }
    val page = list.firstVisibleItemIndex

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                renderer?.let { Text("Page ${page + 1} / ${it.pageCount}", style = MaterialTheme.typography.labelSmall) }
            }
            IconButton(onClick = {
                bookmarks = (if (page in bookmarks) bookmarks - page else bookmarks + page).toSortedSet()
                prefs.edit().putStringSet(path, bookmarks.map { it.toString() }.toSet()).apply()
            }) { Icon(if (page in bookmarks) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "Bookmark") }
            IconButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Go to page / bookmarks") }, onClick = { jump = true; menu = false })
                    DropdownMenuItem(text = { Text("Document info") }, onClick = { info = true; menu = false })
                    DropdownMenuItem(text = { Text("Share") }, onClick = { shareFile(ctx, file); menu = false })
                    DropdownMenuItem(text = { Text("Print") }, onClick = { print(ctx, file); menu = false })
                }
            }
        }
        if (renderer == null) {
            Text("Cannot open this PDF: ${holder.exceptionOrNull()?.message ?: "it may be corrupted or password-protected"}", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            return@Column
        }
        LazyColumn(state = list, contentPadding = PaddingValues(12.dp),
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 4f) } }) {
            items(renderer.pageCount) { index ->
                val bmp by produceState<Bitmap?>(null, index) {
                    value = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            runCatching {
                                renderer.openPage(index).use { p ->
                                    val w = 1400; val h = (w.toFloat() * p.height / p.width).toInt()
                                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                                        b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).aspectRatio(bmp?.let { it.width.toFloat() / it.height } ?: 0.707f).background(Color.White)) {
                    bmp?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation.toFloat() }) }
                }
            }
        }
    }
    if (info && renderer != null) AlertDialog(onDismissRequest = { info = false }, title = { Text("Document") }, text = {
        Text("Pages: ${renderer.pageCount}\nSize: ${Formatters.size(file.length())}\nModified: ${Formatters.date(file.lastModified())}\nPath: ${file.absolutePath}\n\n" +
            "Text search and selection are not available with the system PDF renderer on all Android versions.")
    }, confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } })
    if (jump && renderer != null) AlertDialog(onDismissRequest = { jump = false }, title = { Text("Go to") }, text = {
        LazyColumn {
            item { Text("Bookmarks", style = MaterialTheme.typography.titleSmall) }
            items(bookmarks.toList().size) { i -> val p = bookmarks.toList()[i]; TextButton(onClick = { scope.launch { list.scrollToItem(p) }; jump = false }) { Text("Page ${p + 1}") } }
            item { Text("All pages", style = MaterialTheme.typography.titleSmall) }
            items(renderer.pageCount) { p -> TextButton(onClick = { scope.launch { list.scrollToItem(p) }; jump = false }) { Text("Page ${p + 1}") } }
        }
    }, confirmButton = {})
}

private fun print(ctx: Context, file: File) {
    val pm = ctx.getSystemService(PrintManager::class.java)
    pm.print(file.name, object : PrintDocumentAdapter() {
        override fun onLayout(o: PrintAttributes?, n: PrintAttributes?, c: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (c?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }
        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, c: CancellationSignal?, cb: WriteResultCallback) {
            runCatching { file.inputStream().use { i -> java.io.FileOutputStream(dest.fileDescriptor).use { i.copyTo(it) } } }
                .onSuccess { cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }.onFailure { cb.onWriteFailed(it.message) }
        }
    }, null)
}
