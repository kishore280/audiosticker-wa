package com.audiostickerwa.audio

import android.content.Context
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class VisualKind { Video, Gif, Image }

/** A picked file. [width]/[height] are the shown size (rotation applied); 0 when unknown. */
data class Media(val uri: Uri, val mime: String, val durationMs: Long, val name: String, val width: Int = 0, val height: Int = 0)

/** What the audio maker screen renders. [visualKind] is null when no picture is selected. */
data class AudioMakerUi(
    /** Selected tab. */
    val tab: VisualKind = VisualKind.Video,
    /** Selected picture (null = none yet). */
    val visual: Media? = null,
    val audio: Media? = null,
    val needsAudio: Boolean = false,
    val timedMs: Long = 0,
    val range: ClosedFloatingPointRange<Float> = 0f..0f,
    val crop: Boolean = false,
    val canCreate: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val result: SavedSound? = null,
)


/**
 * Plain UI state holder for the audio sticker maker. It owns the user's choices and the last result.
 *
 * It lives in the App composition next to [StickerEngine], and [scope] is that composition's scope:
 * a create or send continues when the user leaves the audio screen, and stops only when the app UI goes.
 */
@Stable
class AudioMakerState(
    private val context: Context,
    private val library: SoundLibrary,
    private val scope: CoroutineScope,
) {
    var visual by mutableStateOf<Media?>(null); private set
    var visualKind by mutableStateOf(VisualKind.Video); private set
    var tab by mutableStateOf(VisualKind.Video); private set
    /** Short text while busy (create/send steps). */
    var progress by mutableStateOf<String?>(null); private set
    var audio by mutableStateOf<Media?>(null); private set
    /** Trim range in ms on the timed source (separate audio if set, else the video). */
    var range by mutableStateOf(0f..0f); private set
    var crop by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<SavedSound?>(null); private set

    /** Immutable snapshot for the stateless content composable. */
    fun ui() = AudioMakerUi(
        tab = tab, visual = visual, audio = audio,
        needsAudio = needsAudio, timedMs = timedDurationMs, range = range, crop = crop,
        canCreate = canCreate, busy = busy, error = error, result = result,
    )

    /** The source that the trim slider controls. */
    val timedDurationMs: Long get() = audio?.durationMs ?: if (visualKind == VisualKind.Video) visual?.durationMs ?: 0 else 0

    val needsAudio: Boolean get() = visual != null && visualKind != VisualKind.Video && audio == null

    val canCreate: Boolean get() = visual != null && !needsAudio && !busy && range.endInclusive - range.start >= 300f

    /** Changing the tab clears a picture of the other kind (and the result). */
    fun selectTab(kind: VisualKind) {
        if (kind == tab) return
        tab = kind
        if (visual != null && visualKind != kind) { visual = null; result = null; resetRange() }
    }

    /** A new trim or crop makes the old sticker out of date. */
    fun changeRange(r: ClosedFloatingPointRange<Float>) { if (r != range) { range = r; clearResult() } }
    fun changeCrop(c: Boolean) { if (c != crop) { crop = c; clearResult() } }
    private fun clearResult() { if (!busy) result = null }

    /** Starts again with an empty form (the made sticker stays in the library). */
    fun reset() {
        if (busy) return
        visual = null; audio = null; result = null; error = null; crop = false
        resetRange()
    }

    fun setVisual(uri: Uri) { scope.launch { loadVisual(uri) } }
    fun setAudio(uri: Uri?) { scope.launch { loadAudio(uri) } }
    fun create() { if (!busy) scope.launch { doCreate() } }

    private suspend fun loadVisual(uri: Uri) {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("video/") -> VisualKind.Video
            mime == "image/gif" -> VisualKind.Gif
            else -> VisualKind.Image
        }
        val (dur, size) = withContext(Dispatchers.IO) {
            when (kind) {
                VisualKind.Video -> mediaDuration(uri) to videoSize(uri)
                VisualKind.Gif -> gifDuration(uri) to imageSize(uri)
                VisualKind.Image -> 0L to imageSize(uri)
            }
        }
        visual = Media(uri, mime, dur, displayName(uri), size.first, size.second)
        visualKind = kind
        tab = kind
        result = null
        resetRange()
    }

    private suspend fun loadAudio(uri: Uri?) {
        audio = uri?.let {
            val mime = context.contentResolver.getType(it) ?: "audio/mpeg"
            Media(it, mime, withContext(Dispatchers.IO) { mediaDuration(it) }, displayName(it))
        }
        result = null
        resetRange()
    }

    private fun resetRange() {
        val d = timedDurationMs.toFloat()
        range = 0f..minOf(d, DEFAULT_LEN_MS)
    }

    private suspend fun doCreate() {
        val v = visual ?: return
        busy = true; error = null
        val dir = library.newDir()
        try {
            val spec = SoundStickerSpec(
                visual = v, kind = visualKind, audio = audio,
                startMs = range.start.toLong(), durMs = (range.endInclusive - range.start).toLong(), crop = crop,
            )
            val seconds = withContext(Dispatchers.Default) {
                SoundStickerMaker.make(context, spec, dir) { p -> progress = p }
            }
            result = library.commit(dir, seconds)
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            error = e.message ?: e.toString()
        } finally {
            busy = false
            progress = null
        }
    }

    private fun mediaDuration(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun videoSize(uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (e: Exception) { 0 to 0 } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun imageSize(uri: Uri): Pair<Int, Int> = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        o.outWidth.coerceAtLeast(0) to o.outHeight.coerceAtLeast(0)
    } catch (e: Exception) { 0 to 0 }

    @Suppress("DEPRECATION")
    private fun gifDuration(uri: Uri): Long {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return 0
        return Movie.decodeByteArray(bytes, 0, bytes.size)?.duration()?.toLong()?.takeIf { it > 0 } ?: 1000L
    }

    private fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null } ?: (uri.lastPathSegment ?: "file")


    companion object {
        const val DEFAULT_LEN_MS = 5000f
        const val MAX_LEN_MS = 15_000f
        const val GOOD_LEN_MS = 10_000f
    }
}
