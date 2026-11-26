package com.kish.wastickers.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class OggOpusTest {
    @Test fun packetSamples_readsTocFrameSize() {
        // config 31 (CELT 20 ms), code 0 -> 960 samples; config 15 (hybrid 20 ms), code 1 -> 2 frames
        assertEquals(960, OggOpus.packetSamples(byteArrayOf((31 shl 3).toByte())))
        assertEquals(1920, OggOpus.packetSamples(byteArrayOf(((15 shl 3) or 1).toByte())))
        assertEquals(480, OggOpus.packetSamples(byteArrayOf((30 shl 3).toByte())))
    }

    @Test fun build_writesValidPagesFromRealPackets() {
        val src = System.getProperty("opus.packets")?.let(::File)?.takeIf { it.exists() } ?: return
        val packets = ArrayList<ByteArray>()
        DataInputStream(src.inputStream()).use { d ->
            while (d.available() > 0) { val n = d.readInt(); packets.add(ByteArray(n).also { d.readFully(it) }) }
        }
        val ogg = OggOpus.build(packets, OggOpus.opusHead(1, 312), 48000L)
        assertTrue(String(ogg, 0, 4) == "OggS")
        File(System.getProperty("opus.out")!!).writeBytes(ogg)
    }
}
