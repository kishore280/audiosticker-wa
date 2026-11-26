package com.kish.wastickers.sticker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kish.wastickers.BuildConfig
import java.io.File
import java.io.FileNotFoundException

/**
 * Port of the WhatsApp sample StickerContentProvider.
 * It reads packs from PackStore (files dir) instead of assets, so users can make packs in the app.
 */
class StickerContentProvider : ContentProvider() {

  companion object {
    val AUTHORITY: String = BuildConfig.CONTENT_PROVIDER_AUTHORITY

    const val METADATA = "metadata"
    const val STICKERS = "stickers"
    const val STICKERS_ASSET = "stickers_asset"

    const val STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier"
    const val STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name"
    const val STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher"
    const val STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon"
    const val ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link"
    const val IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link"
    const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
    const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
    const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
    const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
    const val IMAGE_DATA_VERSION = "image_data_version"
    const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
    const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

    const val STICKER_FILE_NAME_IN_QUERY = "sticker_file_name"
    const val STICKER_FILE_EMOJI_IN_QUERY = "sticker_emoji"
    const val STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY = "sticker_accessibility_text"

    private const val METADATA_CODE = 1
    private const val METADATA_CODE_FOR_SINGLE_PACK = 2
    private const val STICKERS_CODE = 3
    private const val STICKERS_ASSET_CODE = 4

    private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
      addURI(AUTHORITY, METADATA, METADATA_CODE)
      addURI(AUTHORITY, "$METADATA/*", METADATA_CODE_FOR_SINGLE_PACK)
      addURI(AUTHORITY, "$STICKERS/*", STICKERS_CODE)
      addURI(AUTHORITY, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
    }
  }

  override fun onCreate(): Boolean {
    val auth = BuildConfig.CONTENT_PROVIDER_AUTHORITY
    check(auth.startsWith(context!!.packageName)) {
      "your authority ($auth) for the content provider should start with your package name: ${context!!.packageName}"
    }
    return true
  }

  override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
    val packs = PackStore.load(context!!)
    return when (MATCHER.match(uri)) {
      METADATA_CODE -> packCursor(uri, (0 until packs.length()).map { packs.getJSONObject(it) })
      METADATA_CODE_FOR_SINGLE_PACK -> packCursor(uri, listOfNotNull(PackStore.find(packs, uri.lastPathSegment ?: "")))
      STICKERS_CODE -> stickerCursor(uri, PackStore.find(packs, uri.lastPathSegment ?: ""))
      else -> throw IllegalArgumentException("Unknown URI: $uri")
    }
  }

  private fun packCursor(uri: Uri, list: List<org.json.JSONObject>): Cursor {
    val c = MatrixCursor(arrayOf(
      STICKER_PACK_IDENTIFIER_IN_QUERY, STICKER_PACK_NAME_IN_QUERY, STICKER_PACK_PUBLISHER_IN_QUERY,
      STICKER_PACK_ICON_IN_QUERY, ANDROID_APP_DOWNLOAD_LINK_IN_QUERY, IOS_APP_DOWNLOAD_LINK_IN_QUERY,
      PUBLISHER_EMAIL, PUBLISHER_WEBSITE, PRIVACY_POLICY_WEBSITE, LICENSE_AGREEMENT_WEBSITE,
      IMAGE_DATA_VERSION, AVOID_CACHE, ANIMATED_STICKER_PACK,
    ))
    for (p in list) {
      c.newRow()
        .add(p.getString("identifier"))
        .add(p.getString("name"))
        .add(p.optString("publisher", ""))
        .add(p.optString("tray_image_file", "tray.png"))
        .add(p.optString("android_play_store_link", ""))
        .add(p.optString("ios_app_store_link", ""))
        .add(p.optString("publisher_email", ""))
        .add(p.optString("publisher_website", ""))
        .add(p.optString("privacy_policy_website", ""))
        .add(p.optString("license_agreement_website", ""))
        .add(p.optString("image_data_version", "1"))
        .add(if (p.optBoolean("avoid_cache", false)) 1 else 0)
        .add(if (p.optBoolean("animated_sticker_pack", false)) 1 else 0)
    }
    c.setNotificationUri(context!!.contentResolver, uri)
    return c
  }

  private fun stickerCursor(uri: Uri, p: org.json.JSONObject?): Cursor {
    val c = MatrixCursor(arrayOf(STICKER_FILE_NAME_IN_QUERY, STICKER_FILE_EMOJI_IN_QUERY, STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY))
    val arr = p?.optJSONArray("stickers")
    if (arr != null) {
      for (i in 0 until arr.length()) {
        val s = arr.getJSONObject(i)
        val emojis = s.optJSONArray("emojis")
        val e = if (emojis == null) "" else (0 until emojis.length()).joinToString(",") { emojis.getString(it) }
        c.addRow(arrayOf(s.getString("image_file"), e, s.optString("accessibility_text", "")))
      }
    }
    c.setNotificationUri(context!!.contentResolver, uri)
    return c
  }

  override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
    if (MATCHER.match(uri) != STICKERS_ASSET_CODE) throw FileNotFoundException("Bad URI: $uri")
    val seg = uri.pathSegments
    if (seg.size != 3) throw FileNotFoundException("Bad URI: $uri")
    val id = seg[1]
    val name = seg[2]
    // Block path tricks such as "../"
    if (name.contains('/') || name.contains("..") || id.contains('/') || id.contains("..")) throw FileNotFoundException("Bad name")
    val packs = PackStore.load(context!!)
    val p = PackStore.find(packs, id) ?: throw FileNotFoundException("No pack $id")
    val known = mutableSetOf(p.optString("tray_image_file", "tray.png"))
    p.optJSONArray("stickers")?.let { a -> for (i in 0 until a.length()) known.add(a.getJSONObject(i).getString("image_file")) }
    if (name !in known) throw FileNotFoundException("No file $name")
    val f = File(PackStore.packDir(context!!, id), name)
    if (!f.exists()) throw FileNotFoundException("Missing $name")
    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
  }

  override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
    METADATA_CODE -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$METADATA"
    METADATA_CODE_FOR_SINGLE_PACK -> "vnd.android.cursor.item/vnd.$AUTHORITY.$METADATA"
    STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$STICKERS"
    STICKERS_ASSET_CODE -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
    else -> throw IllegalArgumentException("Unknown URI: $uri")
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()
}
