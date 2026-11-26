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

    fun fromVideo(ctx: Context, uri: Uri): Frames? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val useMs = durMs.coerceIn(1, MAX_DURATION_MS)
            val count = ((useMs * MAX_FPS) / 1000).toInt().coerceIn(1, MAX_FRAMES)
            val step = useMs / count
            val out = ArrayList<Bitmap>(count)
            for (i in 0 until count) {
                val us = (i * step + step / 2) * 1000
                val f = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                out.add(scaleToFit(f))
                f.recycle()
            }
            if (out.isEmpty()) return null
            return Frames(out, maxOf(8, (useMs / out.size).toInt()))
        } catch (e: Exception) {
            return null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    fun fromGif(ctx: Context, uri: Uri): Frames? {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (movie.width() <= 0 || movie.height() <= 0) return null
        val dur = movie.duration().toLong().let { if (it <= 0) 1000L else it }
        val useMs = dur.coerceAtMost(MAX_DURATION_MS)
        val count = ((useMs * MAX_FPS) / 1000).toInt().coerceIn(1, MAX_FRAMES)
        val step = useMs / count
        val full = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        val out = ArrayList<Bitmap>(count)
        for (i in 0 until count) {
            full.eraseColor(Color.TRANSPARENT)
            movie.setTime((i * step).toInt())
            movie.draw(c, 0f, 0f)
            out.add(scaleToFit(full))
        }
        full.recycle()
        return Frames(out, maxOf(8, (useMs / count).toInt()))
    }

    /** Scale so the longer side is 512; aspect ratio kept. Letterbox is added at encode time. */
    private fun scaleToFit(src: Bitmap): Bitmap {
        val s = minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    // ------------------------------------------------------------ encode

    /**
     * Tries quality and frame-rate steps until the file is < 500 KB.
     * Returns null when no step fits.
     */
    fun encodeToFit(frames: Frames): ByteArray? {
        val qualities = intArrayOf(75, 60, 45, 30, 20)
        val skips = intArrayOf(1, 2, 3)  // keep every n-th frame
        for (skip in skips) {
            val picked = frames.bitmaps.filterIndexed { i, _ -> i % skip == 0 }
            val ms = frames.frameMs * skip
            for (q in qualities) {
                val data = encode(picked, ms, q)
                if (data.size < MAX_BYTES) return data
            }
        }
        return null
    }

    fun encode(frames: List<Bitmap>, frameMs: Int, quality: Int): ByteArray {
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
        return mux(stills.map { frameChunks(it) }, frameMs)
    }

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

    private fun mux(frames: List<ByteArray>, frameMs: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        // VP8X: flags = animation (0x02) | alpha (0x10)
        chunk(body, "VP8X", ByteArray(10).also {
            it[0] = 0x12
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
