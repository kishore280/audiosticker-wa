package com.audiostickerwa.audio

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Send / export / delete for saved audio stickers, shared by the home screen and the maker screen.
 * It lives in the App composition with the engine; [scope] is that composition's scope, so a send
 * continues when the user changes screens.
 */
@Stable
class SoundActions(
    private val context: Context,
    private val engine: StickerEngine,
    val library: SoundLibrary,
    private val scope: CoroutineScope,
) {
    /** ID of the sticker that is being sent now, or null. */
    var sendingId by mutableStateOf<String?>(null); private set
    /** A short message for a snackbar. The screen calls [messageShown] after it shows it. */
    var message by mutableStateOf<String?>(null); private set

    fun messageShown() { message = null }

    fun send(item: SavedSound, chat: WaChat) {
        if (sendingId != null) return
        sendingId = item.id
        scope.launch {
            message = try {
                engine.send(chat, item)
                library.markSent(item.id)
                "Sent to ${chat.title} ✓"
            } catch (e: Exception) {
                "Not sent: ${e.message ?: e}"
            } finally {
                sendingId = null
            }
        }
    }

    fun export(item: SavedSound) = scope.launch {
        val a = saveToDownloads(context, item.webp, "sticker_${item.id}.webp", "image/webp")
        saveToDownloads(context, item.ogg, "sticker_${item.id}.ogg", "audio/ogg")
        message = if (a.startsWith("Save failed")) a else "Exported to ${a.substringBeforeLast('/')}/"
    }

    fun delete(item: SavedSound) = scope.launch {
        library.delete(item.id)
        message = "Deleted"
    }
}
