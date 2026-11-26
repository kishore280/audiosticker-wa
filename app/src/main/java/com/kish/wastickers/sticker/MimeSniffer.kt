package com.kish.wastickers.sticker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * The real media type of a file. Many apps share files as "application/octet-stream" (or a wrong type),
 * so this checks, in order: the type the app gave (when it is a media type), the file name extension,
 * and the first bytes of the file (MP4/MOV, WebM/MKV, GIF, PNG, JPEG, WebP, HEIC, MP3, Ogg, WAV, FLAC, AAC/M4A).
 * Returns "application/octet-stream" when it is not a known media file.
 */
object MimeSniffer {
    private const val UNKNOWN = "application/octet-stream"

    fun mime(ctx: Context, uri: Uri): String {
        val given = try { ctx.contentResolver.getType(uri) } catch (e: Exception) { null }
        if (given != null && isMedia(given)) return given
        fromName(name(ctx, uri))?.let { return it }
        return sniff(ctx, uri) ?: given ?: UNKNOWN
    }

    fun isMedia(mime: String) = mime.startsWith("video/") || mime.startsWith("image/") || mime.startsWith("audio/") || mime == "application/ogg"

    private fun name(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null } ?: uri.lastPathSegment

    private fun fromName(name: String?): String? = when (name?.substringAfterLast('.', "")?.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "amr" -> "audio/amr"
        else -> null
    }

    private fun sniff(ctx: Context, uri: Uri): String? {
        val b = try {
            ctx.contentResolver.openInputStream(uri)?.use { s -> ByteArray(32).let { a -> val n = s.read(a); if (n <= 0) null else a.copyOf(n) } }
        } catch (e: Exception) { null } ?: return null
        fun at(o: Int, s: String) = b.size >= o + s.length && String(b, o, s.length, Charsets.ISO_8859_1) == s
        fun u(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(4, "ftyp") -> {
                val brand = if (b.size >= 12) String(b, 8, 4, Charsets.ISO_8859_1) else ""
                when {
                    brand.startsWith("hei") || brand.startsWith("mif1") || brand == "heix" || brand == "avif" -> "image/heif"
                    brand.startsWith("M4A") -> "audio/mp4"
                    brand.startsWith("qt") -> "video/quicktime"
                    else -> "video/mp4"
                }
            }
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> "video/webm"
            at(0, "GIF8") -> "image/gif"
            u(0) == 0x89 && at(1, "PNG") -> "image/png"
            u(0) == 0xFF && u(1) == 0xD8 -> "image/jpeg"
            at(0, "RIFF") && at(8, "WEBP") -> "image/webp"
            at(0, "RIFF") && at(8, "WAVE") -> "audio/wav"
            at(0, "OggS") -> "audio/ogg"
            at(0, "fLaC") -> "audio/flac"
            at(0, "ID3") || (u(0) == 0xFF && (u(1) and 0xE0) == 0xE0) -> "audio/mpeg"
            at(0, "#!AMR") -> "audio/amr"
            else -> null
        }
    }
}
