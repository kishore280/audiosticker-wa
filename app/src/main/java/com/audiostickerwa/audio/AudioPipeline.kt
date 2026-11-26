package com.audiostickerwa.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Audio for sound stickers: any audio/video file part -> mono 48 kHz PCM -> Opus in Ogg (native MediaCodec). */
object AudioPipeline {
    const val RATE = 48000
    private const val TIMEOUT_US = 10_000L

    /** Decodes [startMs, startMs+durMs) of the first audio track to mono 16-bit PCM at 48 kHz. */
    fun decodePcm(ctx: Context, uri: Uri, startMs: Long, durMs: Long): ShortArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); error("This file has no sound.") }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(inFmt, null, null, 0)
        dec.start()
        val startUs = startMs * 1000
        val endUs = (startMs + durMs) * 1000
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val mono = ShortArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > endUs + 200_000) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytesPerFrame = channels * (if (floatPcm) 4 else 2)
                        val frames = info.size / bytesPerFrame
                        for (k in 0 until frames) {
                            val tUs = info.presentationTimeUs + k * 1_000_000L / rate
                            var sum = 0f
                            for (c in 0 until channels) sum += if (floatPcm) buf.float * 32767f else buf.short.toFloat()
                            if (tUs in startUs until endUs) mono.add((sum / channels).roundToInt().coerceIn(-32768, 32767).toShort())
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            dec.stop(); dec.release(); ex.release()
        }
        val pcm = resample(mono.toArray(), rate, RATE)
        fade(pcm, RATE * 8 / 1000)
        return pcm
    }

    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val pos = i.toDouble() * from / to
            val a = pos.toInt().coerceAtMost(src.lastIndex)
            val b = (a + 1).coerceAtMost(src.lastIndex)
            val f = pos - a
            (src[a] * (1 - f) + src[b] * f).roundToInt().toShort()
        }
    }

    /** Short fade in/out against clicks at the cut points. */
    private fun fade(p: ShortArray, n: Int) {
        val m = minOf(n, p.size / 2)
        for (i in 0 until m) {
            val g = i.toFloat() / m
            p[i] = (p[i] * g).toInt().toShort()
            p[p.size - 1 - i] = (p[p.size - 1 - i] * g).toInt().toShort()
        }
    }

    /** Encodes mono 48 kHz PCM to an Ogg/Opus file (Android 10+: MediaCodec Opus encoder). */
    @RequiresApi(29)
    fun encodeOggOpus(pcm: ShortArray, bitrate: Int = 32_000): ByteArray {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val packets = ArrayList<ByteArray>()
        var head: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = enc.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        val n = minOf(buf.remaining() / 2, pcm.size - pos)
                        if (n <= 0) {
                            enc.queueInputBuffer(i, 0, 0, pos * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.asShortBuffer().put(pcm, pos, n)
                            enc.queueInputBuffer(i, 0, n * 2, pos * 1_000_000L / RATE, 0)
                            pos += n
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    enc.outputFormat.getByteBuffer("csd-0")?.let { head = it.toBytes() }
                } else if (o >= 0) {
                    val buf = enc.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val bytes = buf.toBytes()
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // Some encoders give OpusHead here instead of in csd-0.
                        if (bytes.size >= 19 && String(bytes, 0, 8) == "OpusHead") head = bytes.copyOf(19)
                    } else if (bytes.isNotEmpty()) {
                        packets.add(bytes)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            enc.stop(); enc.release()
        }
        if (packets.isEmpty()) error("The Opus encoder gave no audio.")
        val h = head?.takeIf { it.size >= 19 && String(it, 0, 8) == "OpusHead" }?.copyOf(19)
            ?: OggOpus.opusHead(channels = 1, preSkip = 312)
        return OggOpus.build(packets, h, pcm.size.toLong())
    }

    private fun ByteBuffer.toBytes(): ByteArray = ByteArray(remaining()).also { get(it) }

    private class ShortArrayBuilder {
        private var a = ShortArray(1 shl 16)
        private var n = 0
        fun add(v: Short) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
}
