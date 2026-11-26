package com.audiostickerwa.audio

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.audiostickerwa.sticker.Framing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Square preview that looks like the sticker will look (512×512): Fit (whole picture, transparent bars) or
 * Fill (a square cut). In Fill, the user drags the picture to choose the cut; [onPosition] gets x, y in 0..1.
 * When [playing] is true it plays only the trimmed part and then calls [onFinished].
 *
 * Video uses a TextureView (a SurfaceView cannot be clipped to the square). The MediaPlayers are UI
 * runtime objects: this composable owns and releases them.
 */
@Composable
fun ClipPreview(
    kind: VisualKind,
    visual: Media,
    audio: Media?,
    range: ClosedFloatingPointRange<Float>,
    framing: Framing,
    playing: Boolean,
    onFinished: () -> Unit,
    onPosition: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var videoPlayer by remember(visual.uri, audio?.uri) { mutableStateOf<MediaPlayer?>(null) }
    val soundPlayer = remember(audio?.uri) { audio?.let { a -> try { MediaPlayer.create(context, a.uri) } catch (e: Exception) { null } } }
    DisposableEffect(soundPlayer) { onDispose { soundPlayer?.release() } }
    val finished by rememberUpdatedState(onFinished)
    val latestFraming by rememberUpdatedState(framing)
    val latestOnPosition by rememberUpdatedState(onPosition)

    // Show the first frame of the trimmed part when the start handle moves.
    LaunchedEffect(videoPlayer, range.start, playing) {
        if (!playing) videoPlayer?.let { if (it.isPlaying) it.pause(); it.seekPrecise(range.start.toLong()) }
    }

    // Play from start to end, then stop.
    LaunchedEffect(playing, videoPlayer, soundPlayer) {
        if (!playing) { soundPlayer?.takeIf { it.isPlaying }?.pause(); return@LaunchedEffect }
        val start = range.start.toLong()
        val end = range.endInclusive.toInt()
        val v = videoPlayer
        if (soundPlayer != null) {
            soundPlayer.seekTo(start.toInt()); soundPlayer.start()
            v?.let { it.seekTo(0); it.start() }
            while (soundPlayer.isPlaying && soundPlayer.currentPosition < end) delay(40)
            soundPlayer.pause(); v?.pause()
        } else if (v != null) {
            v.seekPrecise(start); v.start()
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
        val ar = if (visual.width > 0 && visual.height > 0) visual.width.toFloat() / visual.height
        else if (kind == VisualKind.Video) 16f / 9f else 1f
        // Content size: Fit = inside the square; Fill = covers the square (one side larger).
        val (cw, ch) = if (framing.crop == (ar > 1f)) (side * ar) to side else side to (side / ar)
        val overX = cw - side
        val overY = ch - side
        val density = LocalDensity.current
        val content = Modifier
            .requiredSize(cw, ch)
            .offset(x = if (framing.crop) overX * (0.5f - framing.x) else 0.dp, y = if (framing.crop) overY * (0.5f - framing.y) else 0.dp)

        when (kind) {
            VisualKind.Video -> key(visual.uri, audio?.uri) {
                AndroidView(
                    modifier = content,
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    val mp = MediaPlayer()
                                    try {
                                        mp.setDataSource(ctx, visual.uri)
                                        mp.setSurface(Surface(st))
                                        if (audio != null) mp.setVolume(0f, 0f) // separate sound: the video is silent
                                        mp.isLooping = audio != null
                                        mp.setOnPreparedListener { p -> p.seekPrecise(range.start.toLong()); videoPlayer = p }
                                        mp.prepareAsync()
                                        tag = mp
                                    } catch (e: Exception) { mp.release() }
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    (tag as? MediaPlayer)?.release(); tag = null; videoPlayer = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    onRelease = { v -> (v.tag as? MediaPlayer)?.release(); v.tag = null },
                )
            }
            VisualKind.Gif -> AndroidView(
                modifier = content,
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY } },
                update = { iv ->
                    if (iv.tag != visual.uri) {
                        iv.tag = visual.uri
                        val d = try {
                            if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeDrawable(
                                android.graphics.ImageDecoder.createSource(iv.context.contentResolver, visual.uri),
                            ) else null
                        } catch (e: Exception) { null }
                        iv.setImageDrawable(d)
                        (d as? android.graphics.drawable.Animatable)?.start()
                    }
                },
            )
            VisualKind.Image -> {
                val px = with(density) { maxOf(cw, ch).roundToPx() }
                val bmp by produceState<ImageBitmap?>(null, visual.uri) {
                    value = withContext(Dispatchers.IO) {
                        com.audiostickerwa.sticker.AnimatedWebp.loadImage(context, visual.uri, maxOf(px, 512))?.asImageBitmap()
                    }
                }
                bmp?.let { Image(it, null, content, contentScale = ContentScale.FillBounds) }
            }
        }

        // Fill: drag to move the picture inside the square.
        if (framing.crop && (overX > 0.dp || overY > 0.dp)) {
            Box(
                Modifier.matchParentSize().pointerInput(overX, overY) {
                    val ox = overX.toPx(); val oy = overY.toPx()
                    detectDragGestures { change, drag ->
                        change.consume()
                        val f = latestFraming
                        // Dragging the picture right shows more of its left side (x goes down).
                        val nx = if (ox > 0f) f.x - drag.x / ox else f.x
                        val ny = if (oy > 0f) f.y - drag.y / oy else f.y
                        latestOnPosition(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
                    }
                },
            )
            Text(
                "Drag to move",
                Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Seeks to the exact frame where the phone supports it (Android 8+). */
private fun MediaPlayer.seekPrecise(ms: Long) {
    try {
        if (Build.VERSION.SDK_INT >= 26) seekTo(ms, MediaPlayer.SEEK_CLOSEST) else seekTo(ms.toInt())
    } catch (_: Exception) {}
}

/** Dark grey "transparent" background, like an image editor. */
private val Checker = Color(0xFF2A2D31)

