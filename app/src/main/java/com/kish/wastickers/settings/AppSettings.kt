package com.kish.wastickers.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The names WhatsApp shows in sticker info ("pack name · publisher"). */
data class StickerNames(val packName: String, val publisher: String)

/** Small user settings, kept in SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _names = MutableStateFlow(
        StickerNames(
            packName = prefs.getString("packName", null) ?: DEFAULT_PACK,
            publisher = prefs.getString("publisher", null) ?: "",
        ),
    )
    val names: StateFlow<StickerNames> = _names.asStateFlow()

    fun setNames(n: StickerNames) {
        val clean = StickerNames(n.packName.trim().take(128).ifEmpty { DEFAULT_PACK }, n.publisher.trim().take(128))
        prefs.edit().putString("packName", clean.packName).putString("publisher", clean.publisher).apply()
        _names.value = clean
    }

    companion object {
        const val DEFAULT_PACK = "My audio stickers"
    }
}
