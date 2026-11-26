package com.audiostickerwa.audio

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.audiostickerwa.ui.AnimatedStickerImage
import com.audiostickerwa.ui.StickerImage
import kotlinx.coroutines.delay
import java.io.File

/**
 * Preview of the selected picture. When [playing] is true it plays only the trimmed part
 * (video sound, or the separate audio file) and then calls [onFinished].
 * The VideoView and MediaPlayer are UI runtime objects: this composable owns and releases them.
 */
@Composable
fun ClipPreview(
    kind: VisualKind,
    visual: Uri,
    audio: Uri?,
    range: ClosedFloatingPointRange<Float>,
    playing: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var video by remember(visual) { mutableStateOf<VideoView?>(null) }
    val player = remember(audio) { audio?.let { u -> try { MediaPlayer.create(context, u) } catch (e: Exception) { null } } }
    DisposableEffect(player) { onDispose { player?.release() } }
    val finished by rememberUpdatedState(onFinished)

    // Show the first frame of the trimmed part when the start handle moves.
    LaunchedEffect(video, range.start, playing) {
        if (!playing) video?.let { it.pause(); it.seekTo(range.start.toInt()) }
    }

    // Play from start to end, then stop.
    LaunchedEffect(playing, video, player) {
        if (!playing) { player?.takeIf { it.isPlaying }?.pause(); return@LaunchedEffect }
        val start = range.start.toInt()
        val end = range.endInclusive.toInt()
        if (player != null) {
            player.seekTo(start); player.start()
            video?.let { it.seekTo(0); it.start() }
            while (player.isPlaying && player.currentPosition < end) delay(40)
            player.pause()
            video?.pause()
        } else if (video != null) {
            video!!.seekTo(start); video!!.start()
            while (video!!.isPlaying && video!!.currentPosition < end) delay(40)
            video!!.pause()
        }
        finished()
    }

    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            VisualKind.Video -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(visual)
                        setOnPreparedListener { mp ->
                            // With a separate audio file, the video is silent.
                            if (audio != null) mp.setVolume(0f, 0f)
                            mp.isLooping = audio != null
                            video = this
                        }
                    }
                },
            )
            VisualKind.Gif -> AnimatedStickerImage(uriFile(context, visual), "GIF", Modifier.fillMaxSize())
            VisualKind.Image -> StickerImage(uriFile(context, visual), "image", Modifier.fillMaxSize())
        }
    }
}

/** Copies a content Uri to a cache file once, for the image composables that read files. */
@Composable
private fun uriFile(context: android.content.Context, uri: Uri): File = remember(uri) {
    val f = File(context.cacheDir, "preview_" + uri.toString().hashCode())
    if (!f.exists()) try {
        context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
    } catch (_: Exception) {}
    f
}


