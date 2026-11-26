package com.kish.wastickers.sticker

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
    /** WhatsApp packs are all-still or all-animated. Animated packs take video and GIF. */
    val animated: Boolean,
    /** Changes each time the stickers change (WhatsApp reloads the pack when it changes). */
    val dataVersion: String = "1",
    /** [dataVersion] at the last successful add to WhatsApp; null = never added from this app. */
    val addedVersion: String? = null,
) {
    val canAddToWhatsApp: Boolean get() = stickers.size in MIN_STICKERS..MAX_STICKERS
    val hasChangesSinceAdded: Boolean get() = addedVersion != null && addedVersion != dataVersion

    companion object {
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
    }
}

data class AddResult(val added: Int, val failed: Int, val skippedFull: Int, val firstError: String? = null)

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

    suspend fun createPack(name: String, publisher: String, animated: Boolean): String = withContext(Dispatchers.IO) {
        val id = "pack_" + System.currentTimeMillis()
        val p = JSONObject()
            .put("identifier", id)
            .put("name", name.take(128))
            .put("publisher", publisher.take(128))
            .put("tray_image_file", TRAY)
            .put("image_data_version", "1")
            .put("avoid_cache", false)
            .put("animated_sticker_pack", animated)
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
            var firstError: String? = null
            val animated = p.optBoolean("animated_sticker_pack", false)
            for (uri in uris) {
                if (stickers.length() >= StickerPack.MAX_STICKERS) { skipped++; continue }
                val bytes = try {
                    if (animated) toAnimatedWebp(uri) else toStickerWebp(uri)
                } catch (e: Throwable) {
                    if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                    null
                }
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
            AddResult(added, failed, skipped, firstError)
        }

    /** One video or GIF -> one animated sticker, with trim and framing (from the clip editor). */
    suspend fun addClip(id: String, uri: Uri, startMs: Long, durMs: Long, framing: Framing): AddResult =
        withContext(Dispatchers.IO) {
            val arr = PackStore.load(context)
            val p = PackStore.find(arr, id) ?: return@withContext AddResult(0, 1, 0, "Pack not found")
            val stickers = p.getJSONArray("stickers")
            if (stickers.length() >= StickerPack.MAX_STICKERS) return@withContext AddResult(0, 0, 1)
            val bytes = try {
                toAnimatedWebp(uri, startMs, durMs, framing) ?: error("The sticker is too big for WhatsApp (500 KB). Make the part shorter.")
            } catch (e: Throwable) {
                return@withContext AddResult(0, 1, 0, e.message ?: e.javaClass.simpleName)
            }
            val name = "s_${System.currentTimeMillis()}.webp"
            File(PackStore.packDir(context, id), name).writeBytes(bytes)
            stickers.put(JSONObject().put("image_file", name).put("emojis", JSONArray().put(DEFAULT_EMOJI)))
            writeTray(id, p)
            PackStore.bumpVersion(p)
            PackStore.save(context, arr)
            AddResult(1, 0, 0)
        }

    /** 1 to 3 emojis for one sticker (WhatsApp uses them for search and categories). */
    suspend fun setEmojis(id: String, file: String, emojis: List<String>) = withContext(Dispatchers.IO) {
        val arr = PackStore.load(context)
        val p = PackStore.find(arr, id) ?: return@withContext
        val st = p.getJSONArray("stickers")
        for (i in 0 until st.length()) {
            val s = st.getJSONObject(i)
            if (s.getString("image_file") == file) s.put("emojis", JSONArray().also { a -> emojis.take(3).forEach { a.put(it) } })
        }
        PackStore.bumpVersion(p)
        PackStore.save(context, arr)
    }

    /** WhatsApp accepted the pack at its current version. */
    suspend fun markAdded(id: String) = withContext(Dispatchers.IO) {
        val arr = PackStore.load(context)
        val p = PackStore.find(arr, id) ?: return@withContext
        p.put("added_version", p.optString("image_data_version", "1"))
        PackStore.save(context, arr)
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

    private fun decode(uri: Uri, maxSide: Int): Bitmap? = AnimatedWebp.loadImage(context, uri, maxSide)

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

    /** Video or GIF -> animated WebP < 500 KB. A still image becomes a 2-frame "animation". */
    private fun toAnimatedWebp(
        uri: Uri, startMs: Long = 0, durMs: Long = AnimatedWebp.MAX_DURATION_MS, framing: Framing = Framing(),
    ): ByteArray? {
        val mime = MimeSniffer.mime(context, uri)
        val frames = when {
            mime.startsWith("video/") -> AnimatedWebp.fromVideo(context, uri, startMs, durMs, framing)
            mime == "image/gif" -> AnimatedWebp.fromGif(context, uri, framing)
            mime.startsWith("image/") -> decode(uri, if (framing.crop) 1536 else 512)?.let { b ->
                val f = if (framing.crop) AnimatedWebp.scale(b, framing) else fitSquare(b, 512); b.recycle()
                AnimatedWebp.Frames(listOf(f, f.copy(f.config ?: Bitmap.Config.ARGB_8888, false)), 500)
            }
            else -> null
        } ?: return null
        try {
            return AnimatedWebp.encodeToFit(frames)
        } finally {
            frames.recycle()
        }
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
        // For an animated WebP, BitmapFactory gives the first frame (Android 9+). If it fails, keep the old tray.
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
            animated = p.optBoolean("animated_sticker_pack", false),
            dataVersion = p.optString("image_data_version", "1"),
            addedVersion = p.optString("added_version", "").ifEmpty { null },
        )
    }

    companion object {
        const val TRAY = "tray.png"
        const val DEFAULT_EMOJI = "😀"
        private const val MAX_STICKER_BYTES = 100 * 1024
    }
}
