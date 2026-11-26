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

data class Media(val uri: Uri, val mime: String, val durationMs: Long, val name: String)

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
    val result: MadeSticker? = null,
    val lastSent: String? = null,
    val saved: String? = null,
)

data class MadeSticker(val webp: File, val ogg: File, val webpBytes: Int, val oggBytes: Int, val seconds: Int, val stamp: Long)

/**
 * Plain UI state holder for the audio sticker maker. It owns the user's choices and the last result.
 *
 * It lives in the App composition next to [StickerEngine], and [scope] is that composition's scope:
 * a create or send continues when the user leaves the audio screen, and stops only when the app UI goes.
 */
@Stable
class AudioMakerState(
    private val context: Context,
    private val engine: StickerEngine,
    private val scope: CoroutineScope,
) {
    var visual by mutableStateOf<Media?>(null); private set
    var visualKind by mutableStateOf(VisualKind.Video); private set
    var tab by mutableStateOf(VisualKind.Video); private set
    var saved by mutableStateOf<String?>(null); private set
    /** Short text while busy (create/send steps). */
    var progress by mutableStateOf<String?>(null); private set
    var audio by mutableStateOf<Media?>(null); private set
    /** Trim range in ms on the timed source (separate audio if set, else the video). */
    var range by mutableStateOf(0f..0f); private set
    var crop by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<MadeSticker?>(null); private set
    var lastSent by mutableStateOf<String?>(null)

    /** Immutable snapshot for the stateless content composable. */
    fun ui() = AudioMakerUi(
        tab = tab, visual = visual, audio = audio,
        needsAudio = needsAudio, timedMs = timedDurationMs, range = range, crop = crop,
        canCreate = canCreate, busy = busy, error = error, result = result, lastSent = lastSent, saved = saved,
    )

    /** The source that the trim slider controls. */
    val timedDurationMs: Long get() = audio?.durationMs ?: if (visualKind == VisualKind.Video) visual?.durationMs ?: 0 else 0

    val needsAudio: Boolean get() = visual != null && visualKind != VisualKind.Video && audio == null

    val canCreate: Boolean get() = visual != null && !needsAudio && !busy && range.endInclusive - range.start >= 300f

    /** Changing the tab clears a picture of the other kind (and the result). */
    fun selectTab(kind: VisualKind) {
        if (kind == tab) return
        tab = kind
        if (visual != null && visualKind != kind) { visual = null; result = null; lastSent = null; resetRange() }
    }

    /** A new trim or crop makes the old sticker out of date. */
    fun changeRange(r: ClosedFloatingPointRange<Float>) { if (r != range) { range = r; clearResult() } }
    fun changeCrop(c: Boolean) { if (c != crop) { crop = c; clearResult() } }
    private fun clearResult() { if (!busy) { result = null; lastSent = null; saved = null } }

    fun setVisual(uri: Uri) { scope.launch { loadVisual(uri) } }
    fun save() {
        val r = result ?: return
        scope.launch {
            val a = saveToDownloads(context, r.webp, "sticker_${r.stamp}.webp", "image/webp")
            saveToDownloads(context, r.ogg, "sticker_${r.stamp}.ogg", "audio/ogg")
            saved = "Saved: ${a.substringBeforeLast('/')}/ (.webp + .ogg)"
        }
    }
    fun setAudio(uri: Uri?) { scope.launch { loadAudio(uri) } }
    fun create() { if (!busy) scope.launch { doCreate() } }
    fun send(chat: WaChat) { if (!busy) scope.launch { doSend(chat) } }

    private suspend fun loadVisual(uri: Uri) {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("video/") -> VisualKind.Video
            mime == "image/gif" -> VisualKind.Gif
            else -> VisualKind.Image
        }
        val dur = withContext(Dispatchers.IO) {
            when (kind) {
                VisualKind.Video -> mediaDuration(uri)
                VisualKind.Gif -> gifDuration(uri)
                VisualKind.Image -> 0L
            }
        }
        visual = Media(uri, mime, dur, displayName(uri))
        visualKind = kind
        tab = kind
        result = null; lastSent = null; saved = null
        resetRange()
    }

    private suspend fun loadAudio(uri: Uri?) {
        audio = uri?.let {
            val mime = context.contentResolver.getType(it) ?: "audio/mpeg"
            Media(it, mime, withContext(Dispatchers.IO) { mediaDuration(it) }, displayName(it))
        }
        result = null; lastSent = null; saved = null
        resetRange()
    }

    private fun resetRange() {
        val d = timedDurationMs.toFloat()
        range = 0f..minOf(d, DEFAULT_LEN_MS)
    }

    private suspend fun doCreate() {
        val v = visual ?: return
        busy = true; error = null; lastSent = null; saved = null
        try {
            val spec = SoundStickerSpec(
                visual = v, kind = visualKind, audio = audio,
                startMs = range.start.toLong(), durMs = (range.endInclusive - range.start).toLong(), crop = crop,
            )
            result = withContext(Dispatchers.Default) {
                SoundStickerMaker.make(context, spec) { p -> progress = p }
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            busy = false
            progress = null
        }
    }

    private suspend fun doSend(chat: WaChat) {
        val r = result ?: return
        busy = true; error = null
        progress = "Sending…"
        try {
            engine.send(chat.id, r)
            lastSent = "Sent to ${chat.title} ✓  Open WhatsApp to forward it anywhere."
        } catch (e: Exception) {
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
