package com.lyvt.files.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import androidx.compose.ui.platform.LocalContext
import com.lyvt.files.core.common.Formatters
import com.lyvt.files.core.storage.FileCategory
import com.lyvt.files.core.storage.FileItem
import java.io.File

fun FileCategory.color(): Color = when (this) {
    FileCategory.IMAGE -> CategoryColors.image; FileCategory.VIDEO -> CategoryColors.video; FileCategory.AUDIO -> CategoryColors.audio
    FileCategory.DOCUMENT -> CategoryColors.document; FileCategory.ARCHIVE -> CategoryColors.archive; FileCategory.APK -> CategoryColors.apk
    FileCategory.OTHER -> CategoryColors.other
}

fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.IMAGE -> Icons.Filled.Image; FileCategory.VIDEO -> Icons.Filled.Movie; FileCategory.AUDIO -> Icons.Filled.Audiotrack
    FileCategory.DOCUMENT -> Icons.Filled.Description; FileCategory.ARCHIVE -> Icons.Filled.Inventory2; FileCategory.APK -> Icons.Filled.Android
    FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Soft card: tonal surface, hairline border, generous radius. */
@Composable
fun LyvtCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val m = modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .let { if (onClick != null) it.combinedClickable(onClick = onClick) else it }
        .padding(padding)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) { Column(m, content = content) }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 44.dp, contentDescription: String? = null) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Animated donut with rounded caps; segments are fractions that sum to <= 1. */
@Composable
fun DonutChart(
    segments: List<Pair<Float, Color>>, modifier: Modifier = Modifier, stroke: Dp = 18.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest, center: @Composable () -> Unit = {},
) {
    val reduce = LocalLyvt.current.reduceMotion
    val anim = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(segments) { if (!reduce) anim.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val d = size.minDimension - s
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(s))
            var start = -90f
            val gap = if (segments.count { it.first > 0.004f } > 1) 3f else 0f
            segments.forEach { (frac, color) ->
                val sweep = 360f * frac * anim.value
                if (sweep > gap + 0.5f) drawArc(color, start + gap / 2, sweep - gap, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
                start += sweep
            }
        }
        center()
    }
}

@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, height: Dp = 8.dp) {
    val reduce = LocalLyvt.current.reduceMotion
    val anim = remember { Animatable(if (reduce) fraction else 0f) }
    LaunchedEffect(fraction) { if (reduce) anim.snapTo(fraction) else anim.animateTo(fraction, tween(700)) }
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(anim.value.coerceIn(0f, 1f)).height(height).clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.75f), color))))
    }
}

@Composable
fun FileThumb(item: FileItem, size: Dp, showThumbnails: Boolean, modifier: Modifier = Modifier) {
    val cat = item.category
    if (item.isDirectory) {
        Box(modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.55f))
        }
    } else if (showThumbnails && (cat == FileCategory.IMAGE || cat == FileCategory.VIDEO)) {
        val ctx = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(File(item.path)).size(256).videoFrameMillis(1000).crossfade(true).build(),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(cat.color().copy(alpha = 0.12f)),
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { IconBadge(cat.icon(), cat.color(), size) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    item: FileItem, selected: Boolean, favorite: Boolean, compact: Boolean, showThumbnails: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, trailing: (@Composable () -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 10.dp)
            .semantics { contentDescription = item.name + if (selected) ", selected" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileThumb(item, if (compact) 36.dp else 44.dp, showThumbnails)
            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp).align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface, CircleShape))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                if (favorite) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Star, null, tint = Color(0xFFF5B400), modifier = Modifier.size(14.dp)) }
            }
            val sub = if (item.isDirectory) "${if (item.childCount >= 0) "${item.childCount} items · " else ""}${Formatters.shortDate(item.lastModified)}"
            else "${Formatters.size(item.size)} · ${Formatters.shortDate(item.lastModified)}"
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(item: FileItem, selected: Boolean, showThumbnails: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.padding(6.dp).clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            FileThumb(item, 72.dp, showThumbnails)
            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(if (item.isDirectory) "${item.childCount.coerceAtLeast(0)} items" else Formatters.size(item.size),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primaryContainer, Color.Transparent))),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}

@Composable
fun LoadingState(label: String, progress: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(strokeCap = StrokeCap.Round)
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        progress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) {
            Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.lyvt.files.R.string.cancel)) } },
    )
}

@Composable
fun TextInputDialog(
    title: String, initial: String = "", label: String = "", confirm: String = "OK", password: Boolean = false,
    numeric: Boolean = false, validate: (String) -> String? = { null }, onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val error = if (text.isEmpty()) null else validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
                isError = error != null, supportingText = error?.let { { Text(it) } },
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = when { numeric -> KeyboardType.NumberPassword; password -> KeyboardType.Password; else -> KeyboardType.Text }),
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = { TextButton(enabled = text.isNotEmpty() && error == null, onClick = { onConfirm(text); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.lyvt.files.R.string.cancel)) } },
    )
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
}

/**
 * Scan progress with a determinate bar when a total is known (or estimated), otherwise an indeterminate bar.
 * [estimated] marks percentages derived from an estimate so the UI says "about".
 */
@Composable
fun ScanProgressCard(title: String, detail: String, fraction: Float?, estimated: Boolean = false, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    val animated by androidx.compose.animation.core.animateFloatAsState(fraction ?: 0f, androidx.compose.animation.core.tween(300), label = "scan")
    LyvtCard(modifier.padding(horizontal = 0.dp), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (fraction != null) Text((if (estimated && fraction < 1f) "≈" else "") + "${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            onCancel?.let { TextButton(onClick = it) { Text(androidx.compose.ui.res.stringResource(com.lyvt.files.R.string.cancel)) } }
        }
        Spacer(Modifier.height(10.dp))
        if (fraction == null) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), strokeCap = StrokeCap.Round)
        else androidx.compose.material3.LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), strokeCap = StrokeCap.Round)
    }
}
