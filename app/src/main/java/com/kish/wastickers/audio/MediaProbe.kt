package com.kish.wastickers.audio

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the kind, length, shown size and name of a picked file (IO dispatcher). */
object MediaProbe {
    suspend fun visual(ctx: Context, uri: Uri): Pair<VisualKind, Media> = withContext(Dispatchers.IO) {
        val mime = com.kish.wastickers.sticker.MimeSniffer.mime(ctx, uri)
        val kind = when {
            mime.startsWith("video/") -> VisualKind.Video
            mime == "image/gif" -> VisualKind.Gif
            else -> VisualKind.Image
        }
        val (dur, size) = when (kind) {
            VisualKind.Video -> duration(ctx, uri) to videoSize(ctx, uri)
            VisualKind.Gif -> gifDuration(ctx, uri) to imageSize(ctx, uri)
            VisualKind.Image -> 0L to imageSize(ctx, uri)
        }
        kind to Media(uri, mime, dur, name(ctx, uri), size.first, size.second)
    }

    suspend fun audio(ctx: Context, uri: Uri): Media = withContext(Dispatchers.IO) {
        Media(uri, com.kish.wastickers.sticker.MimeSniffer.mime(ctx, uri), duration(ctx, uri), name(ctx, uri))
    }

    private fun duration(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { try { r.release() } catch (_: Exception) {} }
    }

    /** Shown size (rotation applied). */
    private fun videoSize(ctx: Context, uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (e: Exception) { 0 to 0 } finally { try { r.release() } catch (_: Exception) {} }
    }

    /** Shown size; EXIF rotation applied for photos. */
    private fun imageSize(ctx: Context, uri: Uri): Pair<Int, Int> = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        val rot = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                androidx.exifinterface.media.ExifInterface(it).rotationDegrees
            } ?: 0
        } catch (e: Exception) { 0 }
        val w = o.outWidth.coerceAtLeast(0); val h = o.outHeight.coerceAtLeast(0)
        if (rot == 90 || rot == 270) h to w else w to h
    } catch (e: Exception) { 0 to 0 }

    @Suppress("DEPRECATION")
    private fun gifDuration(ctx: Context, uri: Uri): Long {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return 0
        return Movie.decodeByteArray(bytes, 0, bytes.size)?.duration()?.toLong()?.takeIf { it > 0 } ?: 1000L
    }

    fun name(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null } ?: (uri.lastPathSegment ?: "file")
}
