package com.audiostickerwa.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class Sticker(val file: String, val emojis: List<String>)

data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String,
    val trayFile: String,
    val stickers: List<Sticker>,
) {
    val canAddToWhatsApp: Boolean get() = stickers.size in MIN_STICKERS..MAX_STICKERS

    companion object {
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
    }
}

data class AddResult(val added: Int, val failed: Int, val skippedFull: Int)

/**
 * Makes and changes sticker packs on disk. All functions are suspend functions:
 * the caller owns cancellation and the result.
 *
 * WhatsApp rules (from the WhatsApp sticker sample):
 *  - sticker: 512x512 WebP, < 100 KB (static)
 *  - tray icon: 96x96 PNG, < 50 KB
 *  - 3..30 stickers per pack, 1..3 emojis per sticker
 */
class StickerRepository(private val context: Context) {

    suspend fun packs(): List<StickerPack> = withContext(Dispatchers.IO) {
        val arr = PackStore.load(context)
        (0 until arr.length()).map { toPack(arr.getJSONObject(it)) }
    }

    fun fileOf(packId: String, name: String): File = File(PackStore.packDir(context, packId), name)

    suspend fun createPack(name: String, publisher: String): String = withContext(Dispatchers.IO) {
        val id = "pack_" + System.currentTimeMillis()
        val p = JSONObject()
            .put("identifier", id)
            .put("name", name.take(128))
            .put("publisher", publisher.take(128))
            .put("tray_image_file", TRAY)
            .put("image_data_version", "1")
            .put("avoid_cache", false)
            .put("animated_sticker_pack", false)
            .put("stickers", JSONArray())
        val arr = PackStore.load(context)
        arr.put(p)
        PackStore.save(context, arr)
        id
    }

    suspend fun deletePack(id: String) = withContext(Dispatchers.IO) {
        val arr = PackStore.load(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.getString("identifier") != id) out.put(p)
        }
        PackStore.save(context, out)
        PackStore.packDir(context, id).deleteRecursively()
    }

    suspend fun addImages(id: String, uris: List<Uri>, emoji: String = DEFAULT_EMOJI): AddResult =
        withContext(Dispatchers.IO) {
            val arr = PackStore.load(context)
            val p = PackStore.find(arr, id) ?: return@withContext AddResult(0, uris.size, 0)
            val stickers = p.getJSONArray("stickers")
            val dir = PackStore.packDir(context, id)
            var added = 0
            var failed = 0
            var skipped = 0
            for (uri in uris) {
                if (stickers.length() >= StickerPack.MAX_STICKERS) { skipped++; continue }
                val bytes = try { toStickerWebp(uri) } catch (e: Exception) { null }
                if (bytes == null) { failed++; continue }
                val name = "s_${System.currentTimeMillis()}_$added.webp"
                File(dir, name).writeBytes(bytes)
                stickers.put(JSONObject().put("image_file", name).put("emojis", JSONArray().put(emoji)))
                added++
            }
            if (added > 0) {
                writeTray(id, p)
                PackStore.bumpVersion(p)
                PackStore.save(context, arr)
            }
            AddResult(added, failed, skipped)
        }

    suspend fun removeSticker(id: String, file: String) = withContext(Dispatchers.IO) {
        val arr = PackStore.load(context)
        val p = PackStore.find(arr, id) ?: return@withContext
        val old = p.getJSONArray("stickers")
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val s = old.getJSONObject(i)
            if (s.getString("image_file") != file) out.put(s)
        }
        p.put("stickers", out)
        fileOf(id, file).delete()
        writeTray(id, p)
        PackStore.bumpVersion(p)
        PackStore.save(context, arr)
    }

    // ------------------------------------------------------------------ image work

    private fun decode(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Fit the image in a 512x512 transparent square (keeps aspect ratio). */
    private fun fitSquare(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.TRANSPARENT)
        val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val l = (size - w) / 2f
        val t = (size - h) / 2f
        Canvas(out).drawBitmap(src, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun toStickerWebp(uri: Uri): ByteArray? {
        val src = decode(uri, 512) ?: return null
        val bmp = fitSquare(src, 512)
        if (src !== bmp) src.recycle()
        var q = 90
        while (q >= 10) {
            val b = compressWebp(bmp, q)
            if (b.size < MAX_STICKER_BYTES) { bmp.recycle(); return b }
            q -= 10
        }
        bmp.recycle()
        return null
    }

    @Suppress("DEPRECATION")
    private fun compressWebp(bmp: Bitmap, quality: Int): ByteArray {
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val bos = ByteArrayOutputStream()
        bmp.compress(fmt, quality, bos)
        return bos.toByteArray()
    }

    /** Tray icon = first sticker, 96x96 PNG. */
    private fun writeTray(id: String, p: JSONObject) {
        val stickers = p.getJSONArray("stickers")
        val tray = fileOf(id, TRAY)
        if (stickers.length() == 0) { tray.delete(); return }
        val first = fileOf(id, stickers.getJSONObject(0).getString("image_file"))
        val src = BitmapFactory.decodeFile(first.path) ?: return
        val small = Bitmap.createScaledBitmap(src, 96, 96, true)
        tray.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        src.recycle(); small.recycle()
    }

    private fun toPack(p: JSONObject): StickerPack {
        val arr = p.optJSONArray("stickers") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val e = s.optJSONArray("emojis") ?: JSONArray()
            Sticker(s.getString("image_file"), (0 until e.length()).map { e.getString(it) })
        }
        return StickerPack(
            id = p.getString("identifier"),
            name = p.getString("name"),
            publisher = p.optString("publisher", ""),
            trayFile = p.optString("tray_image_file", TRAY),
            stickers = list,
        )
    }

    companion object {
        const val TRAY = "tray.png"
        const val DEFAULT_EMOJI = "😀"
        private const val MAX_STICKER_BYTES = 100 * 1024
    }
}
