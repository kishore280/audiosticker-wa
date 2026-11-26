package com.audiostickerwa.audio

import android.media.MediaPlayer
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Square preview that looks like the sticker will look: 512×512, with Fit (whole picture, transparent bars)
 * or Fill (centre cut to a square). When [playing] is true it plays only the trimmed part (video sound, or
 * the separate audio file) and then calls [onFinished].
 * The VideoView and MediaPlayer are UI runtime objects: this composable owns and releases them.
 */
@Composable
fun ClipPreview(
    kind: VisualKind,
    visual: Media,
    audio: Media?,
    range: ClosedFloatingPointRange<Float>,
    crop: Boolean,
    playing: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var video by remember(visual.uri, audio?.uri) { mutableStateOf<VideoView?>(null) }
    val player = remember(audio?.uri) { audio?.let { a -> try { MediaPlayer.create(context, a.uri) } catch (e: Exception) { null } } }
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
        val v = video
        if (player != null) {
            player.seekTo(start); player.start()
            v?.let { it.seekTo(0); it.start() }
            while (player.isPlaying && player.currentPosition < end) delay(40)
            player.pause(); v?.pause()
        } else if (v != null) {
            v.seekTo(start); v.start()
            while (v.isPlaying && v.currentPosition < end) delay(40)
            v.pause()
        }
        finished()
    }

    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))
            .background(Checker).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val side = maxWidth
        when (kind) {
            VisualKind.Video -> {
                // VideoView keeps the aspect ratio inside its size: give it the exact size the sticker shows.
                val ar = if (visual.width > 0 && visual.height > 0) visual.width.toFloat() / visual.height else 16f / 9f
                val (w, h) = if (crop == (ar > 1f)) (side * ar) to side else side to (side / ar)
                // AndroidView runs its factory once: a new video or sound choice needs a new VideoView.
                key(visual.uri, audio?.uri) {
                    AndroidView(
                        modifier = Modifier.requiredSize(w, h),
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(visual.uri)
                                setOnPreparedListener { mp ->
                                    if (audio != null) mp.setVolume(0f, 0f) // separate sound: the video is silent
                                    mp.isLooping = audio != null
                                    video = this
                                }
                            }
                        },
                        onRelease = { it.stopPlayback() },
                    )
                }
            }
            VisualKind.Gif -> AndroidView(
                modifier = Modifier.requiredSize(side, side),
                factory = { ctx -> ImageView(ctx) },
                update = { iv ->
                    iv.scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                    if (iv.tag != visual.uri) {
                        iv.tag = visual.uri
                        val d = try {
                            if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeDrawable(
                                android.graphics.ImageDecoder.createSource(iv.context.contentResolver, visual.uri),
                            ) else null
                        } catch (e: Exception) { null }
                        iv.setImageDrawable(d)
                        (d as? android.graphics.drawable.Animatable)?.start()
                    }
                },
            )
            VisualKind.Image -> {
                val px = with(LocalDensity.current) { side.roundToPx() }
                val bmp by produceState<ImageBitmap?>(null, visual.uri) {
                    value = withContext(Dispatchers.IO) { decodeSampled(context, visual, px) }
                }
                bmp?.let {
                    Image(it, null, Modifier.requiredSize(side, side), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
                }
            }
        }
    }
}

/** Dark grey "transparent" background, like an image editor. */
private val Checker = Color(0xFF2A2D31)

private fun decodeSampled(context: android.content.Context, m: Media, target: Int): ImageBitmap? = try {
    var sample = 1
    while (m.width / (sample * 2) >= target && m.height / (sample * 2) >= target) sample *= 2
    val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(m.uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }?.asImageBitmap()
} catch (e: Exception) { null }
