package com.kish.wastickers.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** One saved audio sticker: an animated WebP and its Ogg/Opus sound. */
data class SavedSound(
    val id: String,
    val webp: File,
    val ogg: File,
    val seconds: Int,
    val webpBytes: Int,
    val oggBytes: Int,
    val createdAt: Long,
    val sentCount: Int,
)

/**
 * The user's audio stickers, kept in files/sounds/<id>/ (sticker.webp, sound.ogg, meta.json).
 * Every sticker the user makes is saved here, so it can be sent again without making it again.
 * Suspend functions run on IO; [items] is the current list, newest first.
 */
class SoundLibrary(context: Context) {
    private val root = File(context.filesDir, "sounds").apply { mkdirs() }
    private val _items = MutableStateFlow<List<SavedSound>>(emptyList())
    val items: StateFlow<List<SavedSound>> = _items.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _items.value = (root.listFiles() ?: emptyArray())
            .mapNotNull { read(it) }
            .sortedByDescending { it.createdAt }
    }

    /** A new, empty folder for a sticker that is being made. */
    fun newDir(): File {
        val id = System.currentTimeMillis().toString()
        return File(root, id).apply { mkdirs() }
    }

    /** Writes meta.json for a finished sticker in [dir] and returns it. */
    suspend fun commit(dir: File, seconds: Int): SavedSound = withContext(Dispatchers.IO) {
        JSONObject().put("seconds", seconds).put("createdAt", System.currentTimeMillis()).put("sentCount", 0)
            .let { File(dir, META).writeText(it.toString()) }
        refresh()
        read(dir) ?: error("Cannot save the sticker.")
    }

    suspend fun markSent(id: String) = withContext(Dispatchers.IO) {
        val f = File(File(root, id), META)
        if (f.exists()) {
            val o = JSONObject(f.readText())
            o.put("sentCount", o.optInt("sentCount") + 1)
            f.writeText(o.toString())
        }
        refresh()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        refresh()
    }

    /** Removes folders of stickers that were never finished (for example, the app stopped while encoding). */
    suspend fun cleanUnfinished() = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { !File(it, META).exists() }?.forEach { it.deleteRecursively() }
    }

    private fun read(dir: File): SavedSound? {
        val meta = File(dir, META)
        val webp = File(dir, WEBP)
        val ogg = File(dir, OGG)
        if (!meta.exists() || !webp.exists() || !ogg.exists()) return null
        val o = try { JSONObject(meta.readText()) } catch (e: Exception) { return null }
        return SavedSound(
            id = dir.name, webp = webp, ogg = ogg, seconds = o.optInt("seconds", 1),
            webpBytes = webp.length().toInt(), oggBytes = ogg.length().toInt(),
            createdAt = o.optLong("createdAt"), sentCount = o.optInt("sentCount"),
        )
    }

    companion object {
        const val WEBP = "sticker.webp"
        const val OGG = "sound.ogg"
        private const val META = "meta.json"
    }
}
