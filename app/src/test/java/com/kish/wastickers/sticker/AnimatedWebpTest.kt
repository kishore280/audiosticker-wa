package com.kish.wastickers.sticker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AnimatedWebpTest {
    @Test fun exif_hasWhatsAppTag() {
        val e = AnimatedWebp.whatsAppExif("""{"a":1}""")
        assertEquals(0x4d, e[0].toInt()); assertEquals(0x57, e[10].toInt()); assertEquals(0x41, e[11].toInt())
        assertEquals("""{"a":1}""", String(e, 26, e.size - 26))
    }

    @Test fun muxStills_fromRealFrames() {
        val dir = System.getProperty("webp.dir")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val stills = listOf(File(dir, "f0.webp").readBytes(), File(dir, "f1.webp").readBytes())
        val exif = AnimatedWebp.whatsAppExif("""{"sticker-pack-id":"t","emojis":["x"],"sound-sha256":"abc="}""")
        File(dir, "anim.webp").writeBytes(AnimatedWebp.muxStills(stills, 250, exif))
    }
}
