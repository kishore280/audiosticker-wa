package com.audiostickerwa.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File

/** JS bridge "AndroidSaver": saves exported .webp / .ogg files to Downloads. */
class FileSaver(private val ctx: Context) {
    @JavascriptInterface
    fun save(name: String, mime: String, base64: String) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "sticker" }
        val msg = try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AudioStickers")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("no uri")
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                "Saved to Downloads/AudioStickers/$safe"
            } else {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                File(dir, safe).writeBytes(bytes)
                "Saved to ${dir.path}/$safe"
            }
        } catch (e: Exception) {
            "Save failed: ${e.message}"
        }
        android.os.Handler(ctx.mainLooper).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }
}
