package com.kish.wastickers.audio

import java.io.ByteArrayOutputStream

/**
 * Minimal Ogg/Opus writer (RFC 3533 + RFC 7845): OpusHead page, OpusTags page, then audio pages.
 * Pure Kotlin, no Android classes, so it can be unit-tested on the JVM.
 */
object OggOpus {
    private const val SERIAL = 0x57415353 // "WASS"

    /** Number of 48 kHz samples in one Opus packet (from its TOC byte, RFC 6716 section 3.1). */
    fun packetSamples(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xFF
        val config = toc shr 3
        val frame10us = when {               // frame size in units of 0.1 ms
            config < 12 -> intArrayOf(100, 200, 400, 600)[config and 3]
            config < 16 -> intArrayOf(100, 200)[config and 1]
            else -> intArrayOf(25, 50, 100, 200)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (p.size > 1) p[1].toInt() and 0x3F else 0
        }
        return frames * frame10us * 48 / 10
    }

    fun opusHead(channels: Int, preSkip: Int, inputRate: Int = 48000): ByteArray {
        val b = ByteArray(19)
        "OpusHead".toByteArray().copyInto(b)
        b[8] = 1; b[9] = channels.toByte()
        le16(b, 10, preSkip); le32(b, 12, inputRate); le16(b, 16, 0); b[18] = 0
        return b
    }

    private fun opusTags(): ByteArray {
        val vendor = "wastickers".toByteArray()
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray())
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(0))
        return out.toByteArray()
    }

    /**
     * @param head the OpusHead (use the encoder's csd-0 when it has one).
     * @param totalSamples real audio length in 48 kHz samples (end trimming of the last page).
     */
    fun build(packets: List<ByteArray>, head: ByteArray, totalSamples: Long): ByteArray {
        val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        val out = ByteArrayOutputStream()
        var seq = 0
        writePage(out, listOf(head), granule = 0, seq = seq++, bos = true, eos = false)
        writePage(out, listOf(opusTags()), granule = 0, seq = seq++, bos = false, eos = false)

        var granule = 0L
        val page = ArrayList<ByteArray>()
        var segs = 0
        val endGranule = preSkip + totalSamples
        packets.forEachIndexed { i, p ->
            val need = p.size / 255 + 1
            if (segs + need > 255 || page.size >= 50) {
                writePage(out, page, granule, seq++, bos = false, eos = false)
                page.clear(); segs = 0
            }
            page.add(p); segs += need
            granule += packetSamples(p)
            if (i == packets.lastIndex) {
                writePage(out, page, minOf(granule, endGranule).coerceAtLeast(0), seq++, bos = false, eos = true)
            }
        }
        return out.toByteArray()
    }

    private fun writePage(out: ByteArrayOutputStream, packets: List<ByteArray>, granule: Long, seq: Int, bos: Boolean, eos: Boolean) {
        val lacing = ByteArrayOutputStream()
        for (p in packets) {
            var n = p.size
            while (n >= 255) { lacing.write(255); n -= 255 }
            lacing.write(n)
        }
        val lace = lacing.toByteArray()
        val hdr = ByteArray(27 + lace.size)
        "OggS".toByteArray().copyInto(hdr)
        hdr[4] = 0
        hdr[5] = ((if (bos) 2 else 0) or (if (eos) 4 else 0)).toByte()
        for (k in 0 until 8) hdr[6 + k] = (granule shr (8 * k)).toByte()
        le32(hdr, 14, SERIAL); le32(hdr, 18, seq); le32(hdr, 22, 0)
        hdr[26] = lace.size.toByte()
        lace.copyInto(hdr, 27)
        val body = ByteArrayOutputStream().also { b -> packets.forEach { b.write(it) } }.toByteArray()
        val crc = crc(crc(0, hdr), body)
        le32(hdr, 22, crc)
        out.write(hdr); out.write(body)
    }

    private val TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun crc(start: Int, data: ByteArray): Int {
        var c = start
        for (b in data) c = (c shl 8) xor TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun le16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, o: Int, v: Int) { for (k in 0 until 4) b[o + k] = (v shr (8 * k)).toByte() }
    private fun le32(v: Int) = ByteArray(4).also { le32(it, 0, v) }
}
