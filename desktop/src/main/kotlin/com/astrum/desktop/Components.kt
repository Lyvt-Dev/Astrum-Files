package com.astrum.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import javax.imageio.ImageIO

fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.IMAGE -> Icons.Filled.Image; FileCategory.VIDEO -> Icons.Filled.Movie; FileCategory.AUDIO -> Icons.Filled.Audiotrack
    FileCategory.DOCUMENT -> Icons.Filled.Description; FileCategory.ARCHIVE -> Icons.Filled.Inventory2; FileCategory.APK -> Icons.Filled.Android
    FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
fun AstrumCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding), content = content)
}

@Composable
fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); action?.invoke()
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

@Composable
fun Donut(segments: List<Pair<Float, Color>>, modifier: Modifier, stroke: Dp = 16.dp, center: @Composable () -> Unit = {}) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(segments) { anim.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx(); val d = size.minDimension - s; val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(s))
            var start = -90f
            segments.forEach { (f, c) -> val sw = 360f * f * anim.value; if (sw > 2f) drawArc(c, start + 1f, sw - 2f, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round)); start += sw }
        }
        center()
    }
}

@Composable
fun UsageBar(fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    val a by animateFloatAsState(fraction, tween(600), label = "usage")
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(a.coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun ProgressCard(title: String, detail: String, fraction: Float?, estimated: Boolean = false, onCancel: (() -> Unit)? = null) {
    val a by animateFloatAsState(fraction ?: 0f, tween(300), label = "p")
    AstrumCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (fraction != null) Text((if (estimated && fraction < 1f) "≈" else "") + "${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            onCancel?.let { TextButton(onClick = it) { Text("Cancel") } }
        }
        Spacer(Modifier.height(10.dp))
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) else LinearProgressIndicator(progress = { a }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, 72.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(14.dp)); it() }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun InputDialog(title: String, initial: String = "", label: String = "", confirm: String = "OK", password: Boolean = false,
                validate: (String) -> String? = { null }, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    val err = if (t.isEmpty()) null else validate(t)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(t, { t = it }, label = { Text(label) }, singleLine = true, isError = err != null, supportingText = err?.let { { Text(it) } },
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None, shape = RoundedCornerShape(12.dp))
    }, confirmButton = { TextButton(enabled = t.isNotEmpty() && err == null, onClick = { onConfirm(t); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Small in-memory LRU of decoded thumbnails; decoding happens off the UI thread with subsampling. */
object Thumbs {
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    })
    suspend fun load(f: File, max: Int = 160): ImageBitmap? = cache[f.path + max] ?: withContext(Dispatchers.IO) {
        runCatching {
            if (f.length() > 60L * 1024 * 1024) return@runCatching null
            val img = ImageIO.read(f) ?: return@runCatching null
            val k = max.toDouble() / maxOf(img.width, img.height)
            val out = if (k >= 1) img else java.awt.image.BufferedImage((img.width * k).toInt().coerceAtLeast(1), (img.height * k).toInt().coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB).also {
                val g = it.createGraphics(); g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, 0, 0, it.width, it.height, null); g.dispose()
            }
            out.toComposeImageBitmap()
        }.getOrNull()?.also { cache[f.path + max] = it }
    }
}

@Composable
fun FileThumb(item: FileItem, size: Dp, thumbnails: Boolean = true) {
    if (item.isDirectory) {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.55f))
        }
        return
    }
    val img by produceState<ImageBitmap?>(null, item.path) { if (thumbnails && item.category == FileCategory.IMAGE) value = Thumbs.load(File(item.path)) }
    val i = img
    if (i != null) Image(i, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)))
    else IconBadge(item.category.icon(), item.category.color(), size)
}

@Composable
fun Row2(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }

@Suppress("unused") private val keepWidth = Modifier.width(0.dp)
