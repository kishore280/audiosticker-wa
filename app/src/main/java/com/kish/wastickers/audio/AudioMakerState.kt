package com.kish.wastickers.audio

import android.content.Context
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.kish.wastickers.sticker.Framing
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
    val framing: Framing = Framing(),
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
    private val settings: com.kish.wastickers.settings.AppSettings,
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
    var framing by mutableStateOf(Framing()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<SavedSound?>(null); private set

    /** Immutable snapshot for the stateless content composable. */
    fun ui() = AudioMakerUi(
        tab = tab, visual = visual, audio = audio,
        needsAudio = needsAudio, timedMs = timedDurationMs, range = range, framing = framing,
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
    fun changeCrop(c: Boolean) { if (c != framing.crop) { framing = framing.copy(crop = c); clearResult() } }
    /** Moves the Fill square; x, y are 0..1 (0.5 = centre). */
    fun changePosition(x: Float, y: Float) {
        val f = framing.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))
        if (f != framing) { framing = f; clearResult() }
    }
    private fun clearResult() { if (!busy) result = null }

    /** Starts again with an empty form (the made sticker stays in the library). */
    fun reset() {
        if (busy) return
        visual = null; audio = null; result = null; error = null; framing = Framing()
        resetRange()
    }

    fun setVisual(uri: Uri) { scope.launch { loadVisual(uri) } }
    fun setAudio(uri: Uri?) { scope.launch { loadAudio(uri) } }
    fun create() { if (!busy) scope.launch { doCreate() } }

    private suspend fun loadVisual(uri: Uri) {
        val (kind, media) = MediaProbe.visual(context, uri)
        visual = media
        visualKind = kind
        tab = kind
        framing = framing.copy(x = 0.5f, y = 0.5f)
        result = null
        resetRange()
    }

    private suspend fun loadAudio(uri: Uri?) {
        audio = uri?.let { MediaProbe.audio(context, it) }
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
                startMs = range.start.toLong(), durMs = (range.endInclusive - range.start).toLong(), framing = framing,
                packName = settings.names.value.packName, publisher = settings.names.value.publisher,
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

    companion object {
        const val DEFAULT_LEN_MS = 5000f
        const val MAX_LEN_MS = 15_000f
        const val GOOD_LEN_MS = 10_000f
    }
}
