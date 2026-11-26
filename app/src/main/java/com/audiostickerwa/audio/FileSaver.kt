package com.audiostickerwa.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copies a made file to Downloads/AudioStickers. Returns a short message for the user. */
suspend fun saveToDownloads(ctx: Context, src: File, name: String, mime: String): String = withContext(Dispatchers.IO) {
    try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AudioStickers")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("no uri")
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            "Downloads/AudioStickers/$name"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            src.copyTo(File(dir, name), overwrite = true)
            "${dir.path}/$name"
        }
    } catch (e: Exception) {
        "Save failed: ${e.message}"
    }
}
