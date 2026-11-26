package com.audiostickerwa.sticker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.audiostickerwa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WhatsApp integration from the official sticker sample (intent + whitelist check). */
object WhatsApp {
    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Intent that asks WhatsApp to add the pack. The user confirms in WhatsApp. */
    fun addPackIntent(pack: StickerPack, pkg: String? = null): Intent =
        Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            putExtra("sticker_pack_name", pack.name)
            if (pkg != null) setPackage(pkg)
        }

    /** True when the pack is in every installed WhatsApp app (consumer and/or business). */
    suspend fun isAdded(ctx: Context, packId: String): Boolean = withContext(Dispatchers.IO) {
        val apps = listOf(CONSUMER, BUSINESS).filter { isInstalled(ctx, it) }
        apps.isNotEmpty() && apps.all { query(ctx, it, packId) }
    }

    private fun query(ctx: Context, pkg: String, packId: String): Boolean {
        val auth = if (pkg == BUSINESS) "com.whatsapp.w4b.provider.sticker_whitelist_check"
        else "com.whatsapp.provider.sticker_whitelist_check"
        val uri = Uri.Builder().scheme("content").authority(auth).appendPath("is_whitelisted")
            .appendQueryParameter("authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendQueryParameter("identifier", packId).build()
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
