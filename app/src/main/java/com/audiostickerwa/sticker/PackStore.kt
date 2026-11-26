package com.audiostickerwa.sticker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores sticker packs in the app files dir:
 *   files/packs.json            -> pack metadata (same shape as WhatsApp sample contents.json)
 *   files/packs/<id>/<file>     -> sticker .webp files and tray .png
 */
object PackStore {
  private val lock = Any()

  fun root(ctx: Context): File = File(ctx.filesDir, "packs").apply { mkdirs() }
  fun packDir(ctx: Context, id: String): File = File(root(ctx), id).apply { mkdirs() }
  private fun jsonFile(ctx: Context) = File(ctx.filesDir, "packs.json")

  fun load(ctx: Context): JSONArray = synchronized(lock) {
    val f = jsonFile(ctx)
    if (!f.exists()) JSONArray() else try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
  }

  fun save(ctx: Context, packs: JSONArray) = synchronized(lock) {
    val f = jsonFile(ctx)
    val tmp = File(f.path + ".tmp")
    tmp.writeText(packs.toString())
    tmp.renameTo(f)
  }

  fun find(packs: JSONArray, id: String): JSONObject? {
    for (i in 0 until packs.length()) {
      val p = packs.getJSONObject(i)
      if (p.getString("identifier") == id) return p
    }
    return null
  }

  /** Change the data version so WhatsApp reloads the pack. */
  fun bumpVersion(p: JSONObject) {
    val v = p.optString("image_data_version", "1").toIntOrNull() ?: 1
    p.put("image_data_version", (v + 1).toString())
  }
}
