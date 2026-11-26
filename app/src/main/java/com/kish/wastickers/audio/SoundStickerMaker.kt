package com.kish.wastickers.audio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.kish.wastickers.sticker.AnimatedWebp
import com.kish.wastickers.sticker.Framing
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** What to make: the picture, the sound (null = the video's own sound) and the trim. */
data class SoundStickerSpec(
    val visual: Media,
    val kind: VisualKind,
    val audio: Media?,
    val startMs: Long,
    val durMs: Long,
    val framing: Framing,
    /** WhatsApp sticker info: pack name and publisher. */
    val packName: String = "",
    val publisher: String = "",
)

/**
 * Makes a WhatsApp sound sticker on the phone, without WhatsApp Web:
 *  sound   -> mono 48 kHz PCM -> Opus (MediaCodec) -> Ogg (OggOpus)
 *  picture -> frames -> animated WebP < 950 KB with EXIF {"sound-sha256": base64(SHA-256(ogg))}
 * Blocking; call it on a background dispatcher.
 */
object SoundStickerMaker {
    private const val MAX_WEBP = 950_000
    private const val MAX_OGG = 950_000
    private const val MAX_FRAMES = 90

    /** Writes sticker.webp and sound.ogg into [outDir] and returns the sound length in seconds. */
    fun make(ctx: Context, spec: SoundStickerSpec, outDir: File, progress: (String) -> Unit): Int {
        if (Build.VERSION.SDK_INT < 29) error("Sound stickers need Android 10 or newer (Opus encoder).")
        // 1. Sound
        progress("Reading the sound…")
        val soundSrc = spec.audio?.uri ?: spec.visual.uri
        val pcm = try {
            AudioPipeline.decodePcm(ctx, soundSrc, spec.startMs, spec.durMs)
        } catch (e: Exception) {
            val type = com.kish.wastickers.sticker.MimeSniffer.mime(ctx, soundSrc)
            throw IllegalStateException("Cannot read the sound ($type): ${e.message ?: e.javaClass.simpleName}")
        }
        if (pcm.size < AudioPipeline.RATE / 4) error("The sound part is too short.")
        progress("Encoding the sound…")
        val seconds = pcm.size.toDouble() / AudioPipeline.RATE
        val bitrate = (MAX_OGG * 8 * 0.9 / seconds).toInt().coerceIn(12_000, 32_000)
        val ogg = AudioPipeline.encodeOggOpus(pcm, bitrate)
        if (ogg.size > MAX_OGG) error("The sound is too long for WhatsApp. Make it shorter.")
        val soundSha = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(ogg), Base64.NO_WRAP)

        // 2. Picture (the WebP loops by itself while the sound plays)
        progress("Reading the picture…")
        val frames = when (spec.kind) {
            VisualKind.Video -> {
                // Own sound: the trimmed part. Other sound: the video from 0, as long as the sound.
                val start = if (spec.audio == null) spec.startMs else 0L
                AnimatedWebp.fromVideo(ctx, spec.visual.uri, start, spec.durMs, spec.framing, MAX_FRAMES) { f ->
                    progress("Reading the picture… ${(f * 100).toInt()}%")
                }
            }
            VisualKind.Gif -> AnimatedWebp.fromGif(ctx, spec.visual.uri, spec.framing, MAX_FRAMES)
            VisualKind.Image -> stillFrames(ctx, spec.visual.uri, spec.framing)
        } ?: error("Cannot read the picture (${spec.visual.mime}).")

        progress("Encoding the sticker…")
        // A stable id per (pack name, publisher): WhatsApp groups the stickers of one pack.
        val packId = UUID.nameUUIDFromBytes("${spec.packName}|${spec.publisher}".toByteArray()).toString()
        val meta = JSONObject()
            .put("sticker-pack-id", packId)
        if (spec.packName.isNotBlank()) meta.put("sticker-pack-name", spec.packName)
        if (spec.publisher.isNotBlank()) meta.put("sticker-pack-publisher", spec.publisher)
        meta
            .put("emojis", JSONArray().put("🔊"))
            .put("sound-sha256", soundSha)
        val webp = try {
            AnimatedWebp.encodeToFit(frames, MAX_WEBP, AnimatedWebp.whatsAppExif(meta.toString()))
        } finally {
            frames.recycle()
        } ?: error("The sticker is too big. Make the part shorter.")

        File(outDir, SoundLibrary.WEBP).writeBytes(webp)
        File(outDir, SoundLibrary.OGG).writeBytes(ogg)
        return maxOf(1, Math.round(seconds).toInt())
    }

    /** An image as a 2-frame animation (WhatsApp sound stickers are animated). */
    private fun stillFrames(ctx: Context, uri: Uri, framing: Framing): AnimatedWebp.Frames? {
        val src = AnimatedWebp.loadImage(ctx, uri, if (framing.crop) 1536 else 512) ?: return null
        val f = AnimatedWebp.scale(src, framing)
        if (f !== src) src.recycle()
        return AnimatedWebp.Frames(listOf(f, f.copy(f.config ?: Bitmap.Config.ARGB_8888, false)), 1000)
    }
}
