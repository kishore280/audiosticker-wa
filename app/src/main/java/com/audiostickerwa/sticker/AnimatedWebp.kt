package com.audiostickerwa.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Makes animated WebP stickers from video or GIF without native libraries:
 *  1. extract frames (MediaMetadataRetriever for video, Movie for GIF)
 *  2. encode each frame as a still WebP with Bitmap.compress
 *  3. mux the still frames into one animated WebP (RIFF: VP8X + ANIM + ANMF...)
 *
 * WhatsApp limits for animated stickers: 512x512, < 500 KB, total duration <= 10 s,
 * each frame >= 8 ms.
 */
/**
 * How the picture goes into the 512×512 square.
 * Fit: the whole picture, transparent bars. Fill ([crop]): a square cut; [x], [y] (0..1) place the cut
 * along the long side (0 = left/top, 0.5 = centre, 1 = right/bottom).
 */
data class Framing(val crop: Boolean = false, val x: Float = 0.5f, val y: Float = 0.5f)

object AnimatedWebp {
    const val SIZE = 512
    const val MAX_BYTES = 500 * 1024
    const val MAX_DURATION_MS = 8_000L   // keep below the 10 s limit
    private const val MAX_FRAMES = 60
    private const val MAX_FPS = 12

    class Frames(val bitmaps: List<Bitmap>, val frameMs: Int) {
        fun recycle() = bitmaps.forEach { it.recycle() }
    }

    // ------------------------------------------------------------ frame extraction

    /**
     * Reads frames of [startMs, startMs + maxMs). Frames are read already scaled (Android 8.1+), which is
     * faster, uses less memory and works for more 4K / HEVC / HDR videos. Throws with the real reason
     * (codec and size included) when the video cannot be read.
     */
    fun fromVideo(
        ctx: Context, uri: Uri, startMs: Long = 0, maxMs: Long = MAX_DURATION_MS, framing: Framing = Framing(),
        maxFrames: Int = MAX_FRAMES, onProgress: (Float) -> Unit = {},
    ): Frames {
        val r = MediaMetadataRetriever()
        try {
            try {
                r.setDataSource(ctx, uri)
            } catch (e: Exception) {
                throw IllegalStateException("Cannot open this video (${describe(ctx, uri)}): ${e.message ?: e.javaClass.simpleName}")
            }
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("This video has no length information (${describe(ctx, uri)}).")
            val useMs = (durMs - startMs).coerceIn(1, maxMs)
            val count = ((useMs * MAX_FPS) / 1000).toInt().coerceIn(1, maxFrames)
            val step = useMs / count
            // 1. Decode the clip one time with MediaCodec (fast; 4K / HEVC / HDR / rotation).
            var lastError: Throwable? = null
            try {
                val frames = VideoFrameReader.read(ctx, uri, startMs, useMs, MAX_FPS, maxFrames, framing, SIZE, onProgress)
                if (frames.isNotEmpty()) return Frames(frames, maxOf(8, (useMs / frames.size).toInt()))
            } catch (e: Throwable) {
                lastError = e
            }
            // 2. Fallback: MediaMetadataRetriever, one frame at a time.
            // Fit: the frame fits in 512. Fill: the shorter side must still be >= 512 after the cut.
            val bound = if (framing.crop) SIZE * 2 else SIZE
            val out = ArrayList<Bitmap>(count)
            for (i in 0 until count) {
                val us = (startMs + i * step + step / 2) * 1000
                val f = try {
                    frameAt(r, us, MediaMetadataRetriever.OPTION_CLOSEST, bound)
                        ?: frameAt(r, us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, bound)
                } catch (e: Throwable) { lastError = e; null }
                if (f != null) {
                    out.add(scale(f, framing))
                    f.recycle()
                }
                onProgress((i + 1f) / count)
            }
            if (out.isEmpty()) {
                throw IllegalStateException(
                    "Cannot read frames from this video (${describe(ctx, uri)})" +
                        (lastError?.let { ": ${it.message ?: it.javaClass.simpleName}" } ?: ": the phone's decoder gave no frames") +
                        ". Try another video, or convert it to H.264 MP4.",
                )
            }
            return Frames(out, maxOf(8, (useMs / out.size).toInt()))
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun frameAt(r: MediaMetadataRetriever, us: Long, option: Int, bound: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, option, bound, bound)
        else r.getFrameAtTime(us, option)

    /** "video/hevc, 3840×2160" from the first video track, for error messages. */
    fun describe(ctx: Context, uri: Uri): String = try {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.let { f ->
                    val w = if (f.containsKey(android.media.MediaFormat.KEY_WIDTH)) f.getInteger(android.media.MediaFormat.KEY_WIDTH) else 0
                    val h = if (f.containsKey(android.media.MediaFormat.KEY_HEIGHT)) f.getInteger(android.media.MediaFormat.KEY_HEIGHT) else 0
                    "${f.getString(android.media.MediaFormat.KEY_MIME)}, ${w}×$h"
                } ?: "no video track"
        } finally { ex.release() }
    } catch (e: Exception) {
        ctx.contentResolver.getType(uri) ?: "unknown format"
    }

    @Suppress("DEPRECATION")
    fun fromGif(ctx: Context, uri: Uri, framing: Framing = Framing(), maxFrames: Int = MAX_FRAMES): Frames? {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (movie.width() <= 0 || movie.height() <= 0) return null
        val dur = movie.duration().toLong().let { if (it <= 0) 1000L else it }
        val useMs = dur.coerceAtMost(MAX_DURATION_MS)
        val count = ((useMs * MAX_FPS) / 1000).toInt().coerceIn(1, maxFrames)
        val step = useMs / count
        val full = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        val out = ArrayList<Bitmap>(count)
        for (i in 0 until count) {
            full.eraseColor(Color.TRANSPARENT)
            movie.setTime((i * step).toInt())
            movie.draw(c, 0f, 0f)
            out.add(scale(full, framing))
        }
        full.recycle()
        return Frames(out, maxOf(8, (useMs / count).toInt()))
    }

    /**
     * Loads a picture at about [maxSide] px: large photos (50+ MP, HEIC) do not use much memory, and the
     * camera rotation (EXIF) is applied. Android 9+: ImageDecoder; older: BitmapFactory with sampling.
     */
    fun loadImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return try {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val s = maxSide.toFloat() / maxOf(w, h)
                    if (s < 1f) d.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
                    d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) { null }
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
    }

    /**
     * fit: the longer side becomes 512 (letterbox is added at encode time).
     * crop: the shorter side becomes 512 and the centre 512x512 is kept.
     */
    fun scale(src: Bitmap, framing: Framing = Framing()): Bitmap {
        val crop = framing.crop
        val s = if (crop) maxOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        else minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (!crop || (w <= SIZE && h <= SIZE)) return scaled
        val cw = minOf(w, SIZE); val ch = minOf(h, SIZE)
        val left = ((w - cw) * framing.x.coerceIn(0f, 1f)).toInt()
        val top = ((h - ch) * framing.y.coerceIn(0f, 1f)).toInt()
        val out = Bitmap.createBitmap(scaled, left, top, cw, ch)
        if (out !== scaled) scaled.recycle()
        return out
    }

    /**
     * WhatsApp sticker metadata: a big-endian TIFF block with one tag 0x5741 ("WA") that holds UTF-8 JSON
     * (sticker-pack-id, emojis, and for sound stickers "sound-sha256" = base64 SHA-256 of the Ogg file).
     */
    fun whatsAppExif(json: String): ByteArray {
        val js = json.toByteArray(Charsets.UTF_8)
        val b = java.nio.ByteBuffer.allocate(26 + js.size).order(java.nio.ByteOrder.BIG_ENDIAN)
        b.put(byteArrayOf(0x4d, 0x4d, 0x00, 0x2a)).putInt(8).putShort(1).putShort(0x5741.toShort())
            .putShort(7).putInt(js.size).putInt(26).putInt(0).put(js)
        return b.array()
    }

    // ------------------------------------------------------------ encode

    /**
     * Tries quality and frame-rate steps until the file is < 500 KB.
     * Returns null when no step fits.
     */
    fun encodeToFit(frames: Frames, maxBytes: Int = MAX_BYTES, exif: ByteArray? = null): ByteArray? {
        val qualities = intArrayOf(75, 60, 45, 30, 20)
        val skips = intArrayOf(1, 2, 3)  // keep every n-th frame
        for (skip in skips) {
            val picked = frames.bitmaps.filterIndexed { i, _ -> i % skip == 0 }
            val ms = frames.frameMs * skip
            for (q in qualities) {
                val data = encode(picked, ms, q, exif)
                if (data.size < maxBytes) return data
            }
        }
        return null
    }

    fun encode(frames: List<Bitmap>, frameMs: Int, quality: Int, exif: ByteArray? = null): ByteArray {
        val canvasBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val stills = frames.map { f ->
            canvasBmp.eraseColor(Color.TRANSPARENT)
            val l = (SIZE - f.width) / 2f
            val t = (SIZE - f.height) / 2f
            canvas.drawBitmap(f, null, RectF(l, t, l + f.width, t + f.height), paint)
            compressStill(canvasBmp, quality)
        }
        canvasBmp.recycle()
        return muxStills(stills, frameMs, exif)
    }

    /** Still 512x512 WebP files -> one animated WebP. Pure bytes (no Android classes): unit-tested. */
    fun muxStills(stills: List<ByteArray>, frameMs: Int, exif: ByteArray? = null): ByteArray =
        mux(stills.map { frameChunks(it) }, frameMs, exif)

    @Suppress("DEPRECATION")
    private fun compressStill(b: Bitmap, q: Int): ByteArray {
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val bos = ByteArrayOutputStream()
        b.compress(fmt, q, bos)
        return bos.toByteArray()
    }

    /** Still WebP -> only the image chunks (ALPH, VP8, VP8L), for use inside an ANMF chunk. */
    private fun frameChunks(webp: ByteArray): ByteArray {
        require(webp.size > 12 && String(webp, 0, 4) == "RIFF" && String(webp, 8, 4) == "WEBP") { "not a WebP" }
        val out = ByteArrayOutputStream()
        var p = 12
        while (p + 8 <= webp.size) {
            val id = String(webp, p, 4, Charsets.US_ASCII)
            val len = le32(webp, p + 4)
            val total = 8 + len + (len and 1)
            if (id == "ALPH" || id == "VP8 " || id == "VP8L") out.write(webp, p, minOf(total, webp.size - p))
            p += total
        }
        return out.toByteArray()
    }

    private fun mux(frames: List<ByteArray>, frameMs: Int, exif: ByteArray? = null): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        // VP8X: flags = animation (0x02) | alpha (0x10) | EXIF (0x08)
        chunk(body, "VP8X", ByteArray(10).also {
            it[0] = (0x12 or (if (exif != null) 0x08 else 0)).toByte()
            put24(it, 4, SIZE - 1); put24(it, 7, SIZE - 1)
        })
        // ANIM: background = transparent, loop = 0 (forever)
        chunk(body, "ANIM", ByteArray(6))
        val dur = frameMs.coerceIn(8, 0xFFFFFF)
        for (f in frames) {
            val hdr = ByteArray(16)
            put24(hdr, 0, 0); put24(hdr, 3, 0)                  // x/2, y/2
            put24(hdr, 6, SIZE - 1); put24(hdr, 9, SIZE - 1)    // w-1, h-1
            put24(hdr, 12, dur)
            hdr[15] = 0x02                                       // no blend, no dispose
            chunk(body, "ANMF", hdr + f)
        }
        if (exif != null) chunk(body, "EXIF", exif)
        val b = body.toByteArray()
        val out = ByteArrayOutputStream(b.size + 8)
        out.write("RIFF".toByteArray())
        out.write(le32bytes(b.size))
        out.write(b)
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(le32bytes(data.size))
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le32bytes(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun put24(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte()
    }
}
