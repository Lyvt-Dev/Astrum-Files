package com.lyvt.files.feature.viewer

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(nav: NavHostController, path: String) {
    val start = File(path)
    val images = remember(path) {
        start.parentFile?.listFiles()?.filter { it.isFile && FileCategory.of(it.extension) == FileCategory.IMAGE }
            ?.sortedWith { a, b -> com.lyvt.files.core.common.NaturalOrder.compare(a.name, b.name) } ?: listOf(start)
    }
    val pager = rememberPagerState(initialPage = images.indexOfFirst { it.path == start.path }.coerceAtLeast(0)) { images.size }
    var chrome by remember { mutableStateOf(true) }
    var rotation by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val current = images.getOrNull(pager.currentPage) ?: start

    LaunchedEffect(slideshow) { while (slideshow) { delay(3000); pager.animateScrollToPage((pager.currentPage + 1) % images.size) } }
    LaunchedEffect(pager.currentPage) { rotation = 0 }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            ZoomableImage(images[page], if (page == pager.currentPage) rotation else 0) { chrome = !chrome }
        }
        if (chrome) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("${pager.currentPage + 1} / ${images.size} · ${Formatters.size(current.length())}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { slideshow = !slideshow }) { Icon(if (slideshow) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Slideshow", tint = Color.White) }
                IconButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate", tint = Color.White) }
                IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "Details", tint = Color.White) }
                IconButton(onClick = { shareFile(ctx, current) }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Save rotated copy") }, enabled = rotation != 0, onClick = {
                            menu = false
                            scope.launch { snackbar.showSnackbar(withContext(Dispatchers.IO) { saveTransformed(current, rotation, null) }) }
                        })
                        DropdownMenuItem(text = { Text("Crop to square (copy)") }, onClick = {
                            menu = false
                            scope.launch { snackbar.showSnackbar(withContext(Dispatchers.IO) { saveTransformed(current, rotation, "square") }) }
                        })
                        DropdownMenuItem(text = { Text("Set as wallpaper") }, onClick = {
                            menu = false
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { current.inputStream().use { WallpaperManager.getInstance(ctx).setStream(it) } } }
                                snackbar.showSnackbar(if (r.isSuccess) "Wallpaper set" else "Could not set wallpaper: ${r.exceptionOrNull()?.message}")
                            }
                        })
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (info) {
        val rows = remember(current) { exifRows(current) }
        AlertDialog(onDismissRequest = { info = false }, title = { Text(current.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Path: ${current.absolutePath}", style = MaterialTheme.typography.bodySmall)
                Text("Size: ${Formatters.size(current.length())}", style = MaterialTheme.typography.bodySmall)
                Text("Modified: ${Formatters.date(current.lastModified())}", style = MaterialTheme.typography.bodySmall)
                rows.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } })
    }
}

@Composable
private fun ZoomableImage(file: File, rotation: Int, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()
        .pointerInput(file) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
        }
        .pointerInput(file) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 8f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
        }) {
        AsyncImage(model = file, contentDescription = file.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y; rotationZ = rotation.toFloat()
            })
    }
}

private fun exifRows(f: File): List<Pair<String, String>> = runCatching {
    val e = ExifInterface(f.path)
    listOf(ExifInterface.TAG_IMAGE_WIDTH to "Width", ExifInterface.TAG_IMAGE_LENGTH to "Height", ExifInterface.TAG_DATETIME_ORIGINAL to "Taken",
        ExifInterface.TAG_MAKE to "Make", ExifInterface.TAG_MODEL to "Model", ExifInterface.TAG_F_NUMBER to "F-number",
        ExifInterface.TAG_EXPOSURE_TIME to "Exposure", ExifInterface.TAG_ISO_SPEED_RATINGS to "ISO", ExifInterface.TAG_FOCAL_LENGTH to "Focal length",
        ExifInterface.TAG_ORIENTATION to "Orientation", ExifInterface.TAG_SOFTWARE to "Software")
        .mapNotNull { (tag, label) -> e.getAttribute(tag)?.let { label to it } } +
        listOfNotNull(FloatArray(2).takeIf { e.getLatLong(it) }?.let { "GPS" to "%.5f, %.5f".format(it[0], it[1]) })
}.getOrDefault(emptyList())

/** Writes an edited copy next to the original; originals are never overwritten. */
private fun saveTransformed(src: File, rotation: Int, crop: String?): String = runCatching {
    val bmp = BitmapFactory.decodeFile(src.path) ?: error("Cannot decode image")
    var out: Bitmap = bmp
    if (rotation != 0) out = Bitmap.createBitmap(out, 0, 0, out.width, out.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    if (crop == "square") { val s = minOf(out.width, out.height); out = Bitmap.createBitmap(out, (out.width - s) / 2, (out.height - s) / 2, s, s) }
    val target = FileOperations.uniqueTarget(src.parentFile!!, src.nameWithoutExtension + "_edited.jpg")
    target.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    "Saved ${target.name}"
}.getOrElse { "Edit failed: ${it.message}" }

fun shareFile(ctx: android.content.Context, f: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(type).putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(android.content.Intent.createChooser(i, f.name))
    }
}
