package com.audiostickerwa.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import kotlin.math.roundToInt

/**
 * Reads video frames by decoding the clip one time with MediaCodec (hardware decoder), and converts each
 * needed frame straight from YUV to a small bitmap. Works for 4K, HEVC, VP9/AV1, 10-bit and rotated videos,
 * where MediaMetadataRetriever is slow or gives no frames.
 *
 * HDR: on Android 13+ the decoder is asked to tone-map to SDR. Without it, 10-bit frames are read as 8-bit
 * (colours are a little flat, but the picture is correct).
 */
object VideoFrameReader {
    private const val TIMEOUT_US = 10_000L

    /**
     * @param fps frames per second to keep; frames are taken at startMs + k / fps.
     * @param side 512: Fit = the longer side is [side]; Fill (crop) = a centred [side]×[side] square.
     */
    fun read(
        ctx: Context, uri: Uri, startMs: Long, durMs: Long, fps: Int, maxFrames: Int,
        crop: Boolean, side: Int, onProgress: (Float) -> Unit,
    ): List<Bitmap> {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run { ex.release(); error("no video track") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0

        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        if (Build.VERSION.SDK_INT >= 33) {
            fmt.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val dec = MediaCodec.createDecoderByType(mime)
        try {
            dec.configure(fmt, null, null, 0)
        } catch (e: Exception) {
            // Some decoders refuse the HDR request: try again without it.
            fmt.removeKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
            dec.reset()
            dec.configure(fmt, null, null, 0)
        }
        dec.start()

        val startUs = startMs * 1000
        val endUs = (startMs + durMs) * 1000
        val count = ((durMs * fps) / 1000).toInt().coerceIn(1, maxFrames)
        val stepUs = (endUs - startUs) / count
        var nextUs = startUs
        val out = ArrayList<Bitmap>(count)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idle = 0
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        try {
            while (out.size < count && idle < 300) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > endUs + 1_000_000) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o >= 0) {
                    idle = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && info.presentationTimeUs >= nextUs && info.presentationTimeUs < endUs) {
                        dec.getOutputImage(o)?.use { img ->
                            out.add(toBitmap(img, rotation, crop, side))
                            onProgress(out.size.toFloat() / count)
                        }
                        // Skip targets that this frame already covers (low-fps video).
                        while (nextUs <= info.presentationTimeUs) nextUs += stepUs
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (eos || info.presentationTimeUs >= endUs) break
                } else if (inputDone) {
                    idle++
                }
            }
        } finally {
            try { dec.stop() } catch (_: Exception) {}
            dec.release(); ex.release()
        }
        return out
    }

    /** YUV image -> ARGB bitmap of the shown size (rotation, Fit or Fill), sampling only the needed pixels. */
    private fun toBitmap(img: Image, rotation: Int, crop: Boolean, side: Int): Bitmap {
        val cr = img.cropRect
        val srcW = cr.width(); val srcH = cr.height()
        val turned = rotation == 90 || rotation == 270
        val dispW = if (turned) srcH else srcW
        val dispH = if (turned) srcW else srcH
        // Region (in shown coordinates) and output size.
        val (regW, regH, outW, outH) = if (crop) {
            val m = minOf(dispW, dispH); listOf(m, m, side, side)
        } else {
            val s = side.toFloat() / maxOf(dispW, dispH)
            listOf(dispW, dispH, (dispW * s).roundToInt().coerceAtLeast(1), (dispH * s).roundToInt().coerceAtLeast(1))
        }
        // The same region in source (not rotated) coordinates; it is centred, so only w/h swap.
        val rw = if (turned) regH else regW
        val rh = if (turned) regW else regH
        val rl = cr.left + (srcW - rw) / 2
        val rt = cr.top + (srcH - rh) / 2
        val ow = if (turned) outH else outW
        val oh = if (turned) outW else outH

        val wide = img.format != ImageFormat.YUV_420_888 // P010 and other 16-bit formats: read the high byte
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val px = IntArray(ow * oh)
        for (y in 0 until oh) {
            val sy = rt + ((y + 0.5f) * rh / oh).toInt()
            val yRow = sy * yP.rowStride
            val cRowU = (sy / 2) * uP.rowStride
            val cRowV = (sy / 2) * vP.rowStride
            for (x in 0 until ow) {
                val sx = rl + ((x + 0.5f) * rw / ow).toInt()
                val yy = sample(yB, yRow + sx * yP.pixelStride, wide) - 16
                val u = sample(uB, cRowU + (sx / 2) * uP.pixelStride, wide) - 128
                val v = sample(vB, cRowV + (sx / 2) * vP.pixelStride, wide) - 128
                // BT.709 limited range
                val c = 1.164f * yy
                val r = (c + 1.793f * v).toInt().coerceIn(0, 255)
                val g = (c - 0.213f * u - 0.533f * v).toInt().coerceIn(0, 255)
                val b = (c + 2.112f * u).toInt().coerceIn(0, 255)
                px[y * ow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bmp
        val turnedBmp = Bitmap.createBitmap(bmp, 0, 0, ow, oh, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (turnedBmp !== bmp) bmp.recycle()
        return turnedBmp
    }

    private fun sample(b: java.nio.ByteBuffer, i: Int, wide: Boolean): Int {
        val idx = if (wide) i + 1 else i
        return if (idx in 0 until b.limit()) b.get(idx).toInt() and 0xFF else 128
    }
}
