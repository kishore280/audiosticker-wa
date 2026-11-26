package com.audiostickerwa.audio

import android.content.Context
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

enum class VisualKind { Video, Gif, Image }

data class Media(val uri: Uri, val mime: String, val durationMs: Long)

/** What the audio maker screen renders. [visualKind] is null when no picture is selected. */
data class AudioMakerUi(
    val visualKind: VisualKind? = null,
    val visualMs: Long = 0,
    val audioMs: Long? = null,
    val needsAudio: Boolean = false,
    val timedMs: Long = 0,
    val range: ClosedFloatingPointRange<Float> = 0f..0f,
    val crop: Boolean = false,
    val normalize: Boolean = true,
    val canCreate: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val result: MadeSticker? = null,
    val lastSent: String? = null,
)

data class MadeSticker(val webp: File, val ogg: File, val webpBytes: Int, val oggBytes: Int, val seconds: Int, val stamp: Long)

/**
 * Plain UI state holder for the audio sticker maker. It owns the user's choices and the last result.
 *
 * It lives in the App composition next to [WaEngine], and [scope] is that composition's scope:
 * a create or send continues when the user leaves the audio screen, and stops only when the app UI goes.
 */
@Stable
class AudioMakerState(
    private val context: Context,
    private val engine: WaEngine,
    private val scope: CoroutineScope,
) {
    var visual by mutableStateOf<Media?>(null); private set
    var visualKind by mutableStateOf(VisualKind.Video); private set
    var audio by mutableStateOf<Media?>(null); private set
    /** Trim range in ms on the timed source (separate audio if set, else the video). */
    var range by mutableStateOf(0f..0f)
    var crop by mutableStateOf(false)
    var normalize by mutableStateOf(true)
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<MadeSticker?>(null); private set
    var lastSent by mutableStateOf<String?>(null)

    /** Immutable snapshot for the stateless content composable. */
    fun ui() = AudioMakerUi(
        visualKind = visual?.let { visualKind }, visualMs = visual?.durationMs ?: 0, audioMs = audio?.durationMs,
        needsAudio = needsAudio, timedMs = timedDurationMs, range = range, crop = crop, normalize = normalize,
        canCreate = canCreate, busy = busy, error = error, result = result, lastSent = lastSent,
    )

    /** The source that the trim slider controls. */
    val timedDurationMs: Long get() = audio?.durationMs ?: if (visualKind == VisualKind.Video) visual?.durationMs ?: 0 else 0

    val needsAudio: Boolean get() = visual != null && visualKind != VisualKind.Video && audio == null

    val canCreate: Boolean get() = visual != null && !needsAudio && !busy && range.endInclusive - range.start >= 300f

    fun setVisual(uri: Uri) { scope.launch { loadVisual(uri) } }
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
        visual = Media(uri, mime, dur)
        visualKind = kind
        result = null
        resetRange()
    }

    private suspend fun loadAudio(uri: Uri?) {
        audio = uri?.let {
            val mime = context.contentResolver.getType(it) ?: "audio/mpeg"
            Media(it, mime, withContext(Dispatchers.IO) { mediaDuration(it) })
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
        try {
            val startS = range.start / 1000.0
            val lenS = (range.endInclusive - range.start) / 1000.0
            val a = audio
            val vis = JSONObject()
                .put("url", engine.shareFile(v.uri, v.mime)).put("mime", v.mime).put("name", fileName(v.mime))
                .put("kind", when (visualKind) { VisualKind.Video -> "video"; VisualKind.Gif -> "gif"; VisualKind.Image -> "image" })
            when {
                visualKind == VisualKind.Image -> Unit
                visualKind == VisualKind.Gif -> vis.put("start", 0).put("dur", v.durationMs / 1000.0)
                a == null -> vis.put("start", startS).put("dur", lenS)
                // Video + other audio: video from 0, as long as the audio (it loops if shorter).
                else -> vis.put("start", 0).put("dur", minOf(v.durationMs / 1000.0, lenS))
            }
            val opts = JSONObject().put("visual", vis)
                .put("crop", crop)
                .put("normalize", normalize)
            if (a != null) {
                opts.put("audio", JSONObject().put("url", engine.shareFile(a.uri, a.mime)).put("mime", a.mime)
                    .put("name", fileName(a.mime)).put("start", startS).put("dur", lenS))
            }
            val r = engine.call("__app.make($opts)") as JSONObject
            result = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "made").apply { mkdirs() }
                val webp = File(dir, "sticker.webp").apply { writeBytes(Base64.decode(r.getString("webp"), Base64.DEFAULT)) }
                val ogg = File(dir, "sticker.ogg").apply { writeBytes(Base64.decode(r.getString("ogg"), Base64.DEFAULT)) }
                MadeSticker(webp, ogg, r.getInt("webpBytes"), r.getInt("oggBytes"), r.optInt("seconds"), System.currentTimeMillis())
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            busy = false
            engine.clearProgress()
        }
    }

    private suspend fun doSend(chat: WaChat) {
        busy = true; error = null
        try {
            val title = engine.call("__app.send(${JSONObject.quote(chat.id)})") as? String ?: chat.title
            lastSent = "Sent to $title. Open WhatsApp to forward it anywhere."
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            busy = false
            engine.clearProgress()
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

    private fun fileName(mime: String) = "media." + (mime.substringAfter('/').substringBefore(';').ifEmpty { "bin" })

    companion object {
        const val DEFAULT_LEN_MS = 5000f
        const val MAX_LEN_MS = 30_000f
    }
}
