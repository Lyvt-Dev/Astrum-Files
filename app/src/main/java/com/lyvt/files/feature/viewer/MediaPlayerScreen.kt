package com.lyvt.files.feature.viewer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Rational
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.navigation.NavHostController
import com.lyvt.files.core.common.NaturalOrder
import com.lyvt.files.core.media.PlaybackService
import com.lyvt.files.core.storage.FileCategory
import kotlinx.coroutines.delay
import java.io.File

private fun positions(ctx: Context) = ctx.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MediaPlayerScreen(nav: NavHostController, path: String) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val start = File(path)
    val isVideo = FileCategory.of(start.extension) == FileCategory.VIDEO
    val queue = remember(path) {
        val cat = FileCategory.of(start.extension)
        start.parentFile?.listFiles()?.filter { it.isFile && FileCategory.of(it.extension) == cat }?.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) } ?: listOf(start)
    }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf(start.name) }
    var speedDialog by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    var queueDialog by remember { mutableStateOf(false) }
    var sleepAt by remember { mutableStateOf(0L) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var shuffle by remember { mutableStateOf(false) }
    var gestureLabel by remember { mutableStateOf<String?>(null) }

    DisposableEffect(path) {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            val items = queue.map { f ->
                MediaItem.Builder().setMediaId(f.absolutePath).setUri(Uri.fromFile(f))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(f.nameWithoutExtension).build()).build()
            }
            val idx = queue.indexOfFirst { it.path == start.path }.coerceAtLeast(0)
            val resume = positions(ctx).getLong(start.absolutePath, 0L)
            c.setMediaItems(items, idx, resume)
            c.prepare(); c.play()
            c.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { title = mediaItem?.mediaMetadata?.title?.toString() ?: title }
            })
            controller = c
        }, ContextCompat.getMainExecutor(ctx))
        if (isVideo) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.let { c ->
                c.currentMediaItem?.mediaId?.let { id ->
                    val nearEnd = c.duration > 0 && c.currentPosition > c.duration - 5000
                    positions(ctx).edit().putLong(id, if (nearEnd) 0 else c.currentPosition).apply()
                }
                // Video stops when leaving the screen; audio keeps playing in the background service.
                if (isVideo) c.stop()
            }
            MediaController.releaseFuture(future)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) { delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0)); controller?.pause(); sleepAt = 0 }
    }
    LaunchedEffect(gestureLabel) { if (gestureLabel != null) { delay(900); gestureLabel = null } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { queueDialog = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = Color.White) }
                IconButton(onClick = { speedDialog = true }) { Icon(Icons.Filled.Speed, "Playback speed", tint = Color.White) }
                IconButton(onClick = { sleepDialog = true }) { Icon(Icons.Filled.Bedtime, "Sleep timer", tint = if (sleepAt > 0) MaterialTheme.colorScheme.primary else Color.White) }
                IconButton(onClick = {
                    controller?.let { c -> runCatching { TrackSelectionDialogBuilder(ctx, "Audio & subtitles", c, androidx.media3.common.C.TRACK_TYPE_AUDIO).build().show() } }
                }) { Icon(Icons.Filled.Audiotrack, "Tracks", tint = Color.White) }
                if (isVideo) {
                    IconButton(onClick = {
                        activity?.requestedOrientation = if (activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }) { Icon(Icons.Filled.ScreenRotation, "Rotate", tint = Color.White) }
                    IconButton(onClick = {
                        runCatching { activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
                    }) { Icon(Icons.Filled.PictureInPicture, "Picture in picture", tint = Color.White) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) {
                    // Left half: brightness, right half: volume.
                    var leftSide = false
                    detectVerticalDragGestures(onDragStart = { leftSide = it.x < size.width / 2 }) { _, dy ->
                        if (leftSide) activity?.window?.let { w ->
                            val lp = w.attributes
                            val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                            lp.screenBrightness = (cur - dy / size.height).coerceIn(0.02f, 1f); w.attributes = lp
                            gestureLabel = "Brightness ${(lp.screenBrightness * 100).toInt()}%"
                        } else {
                            val am = ctx.getSystemService(AudioManager::class.java)
                            if (kotlin.math.abs(dy) > 6) {
                                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dy < 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                                gestureLabel = "Volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
                            }
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                AndroidView(factory = { PlayerView(it).apply { useController = true; setShowSubtitleButton(true); controllerShowTimeoutMs = 3500 } },
                    update = { it.player = controller }, modifier = if (isVideo) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(1f))
                gestureLabel?.let { Text(it, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(12.dp)) }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (shuffle) MaterialTheme.colorScheme.primary else Color.White)
                }
                IconButton(onClick = {
                    repeat = when (repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                    controller?.repeatMode = repeat
                }) { Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat",
                    tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.White) }
            }
        }
    }
    if (speedDialog) AlertDialog(onDismissRequest = { speedDialog = false }, title = { Text("Playback speed") }, text = {
        Column { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> TextButton(onClick = { controller?.setPlaybackSpeed(s); speedDialog = false }) { Text("${s}×") } } }
    }, confirmButton = {})
    if (sleepDialog) AlertDialog(onDismissRequest = { sleepDialog = false }, title = { Text("Sleep timer") }, text = {
        Column {
            listOf(0, 15, 30, 45, 60, 90).forEach { m -> TextButton(onClick = { sleepAt = if (m == 0) 0 else System.currentTimeMillis() + m * 60_000L; sleepDialog = false }) { Text(if (m == 0) "Off" else "$m minutes") } }
        }
    }, confirmButton = {})
    if (queueDialog) AlertDialog(onDismissRequest = { queueDialog = false }, title = { Text("Queue (${queue.size})") }, text = {
        LazyColumn(Modifier.height(360.dp)) {
            itemsIndexed(queue) { i, f ->
                TextButton(onClick = { controller?.seekTo(i, 0); queueDialog = false }) {
                    Text("${i + 1}. ${f.nameWithoutExtension}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                        color = if (controller?.currentMediaItemIndex == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }, confirmButton = {})
    Spacer(Modifier.size(0.dp))
}
