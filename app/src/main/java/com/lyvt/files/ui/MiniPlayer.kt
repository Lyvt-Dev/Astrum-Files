package com.lyvt.files.ui

import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lyvt.files.core.media.PlaybackService
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
/** Mini-player shown above the navigation bar while background audio is loaded in the playback service. */
@Composable
fun MiniPlayer(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var mediaId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val future = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                title = player.currentMediaItem?.mediaMetadata?.title?.toString(); mediaId = player.currentMediaItem?.mediaId
                playing = player.isPlaying
                if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) title = null
            }
        }
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { c -> controller = c; c.addListener(listener); listener.onEvents(c, Player.Events(androidx.media3.common.FlagSet.Builder().build())) }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { controller?.removeListener(listener); MediaController.releaseFuture(future) }
    }
    LaunchedEffect(playing) { while (playing) { controller?.let { if (it.duration > 0) progress = it.currentPosition.toFloat() / it.duration }; delay(500) } }
    val c = controller ?: return
    val t = title ?: return
    val isAudio = mediaId?.substringAfterLast('.')?.lowercase() in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
    if (!isAudio) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { mediaId?.let(onOpen) }) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (c.isPlaying) c.pause() else c.play() }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
            IconButton(onClick = { c.seekToNextMediaItem() }, enabled = c.hasNextMediaItem()) { Icon(Icons.Filled.SkipNext, "Next") }
            IconButton(onClick = { c.stop(); c.clearMediaItems(); title = null }) { Icon(Icons.Filled.Close, "Stop") }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}
