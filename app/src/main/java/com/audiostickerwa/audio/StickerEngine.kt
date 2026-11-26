package com.audiostickerwa.audio

import android.content.Context
import com.audiostickerwa.waengine.Engine
import com.audiostickerwa.waengine.Listener
import com.audiostickerwa.waengine.Waengine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** Link state of the WhatsApp engine. */
sealed interface EngineState {
    data object Starting : EngineState
    data object Unlinked : EngineState
    /** Waiting for the user to type [code] in WhatsApp → Linked devices. */
    data class Pairing(val code: String) : EngineState
    data object Connecting : EngineState
    data class Linked(val phone: String) : EngineState
    data class Failed(val reason: String) : EngineState
}

data class WaChat(val id: String, val title: String, val group: Boolean)

/**
 * Kotlin side of the Go engine (whatsmeow). It is a linked WhatsApp device, like WhatsApp Web, but with no browser.
 * All calls block in Go, so every function here runs on the IO dispatcher; the caller owns cancellation.
 * The session is kept in files/wa (SQLite).
 */
class StickerEngine(context: Context) {
    private val dataDir = File(context.filesDir, "wa").path
    @Volatile private var engine: Engine? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val listener = object : Listener {
        override fun onState(state: String, detail: String) {
            _state.value = when (state) {
                "linked" -> EngineState.Linked(detail)
                "pairing" -> EngineState.Pairing(detail)
                "connecting" -> if (_state.value is EngineState.Pairing) _state.value else EngineState.Connecting
                "unlinked" -> if (detail.isNotEmpty()) EngineState.Failed(detail) else EngineState.Unlinked
                "loggedout" -> EngineState.Unlinked
                // Auto-reconnect is on; keep showing the last good state.
                else -> _state.value
            }
        }
    }

    // Only one Go engine may own the session database: creation is serialized.
    private val createLock = Mutex()

    private suspend fun <T> io(block: (Engine) -> T): T = withContext(Dispatchers.IO) {
        val e = engine ?: createLock.withLock { engine ?: Waengine.new_(dataDir, listener).also { engine = it } }
        block(e)
    }

    /** Opens the session and connects when the device is already linked. */
    suspend fun start() {
        try {
            io { e -> if (e.isLinked) e.connect() else _state.value = EngineState.Unlinked }
        } catch (e: Exception) {
            _state.value = EngineState.Failed(e.message ?: e.toString())
        }
    }

    /** Returns the 8-character code to type in WhatsApp. */
    suspend fun pair(phone: String): String = io { it.pairPhone(phone) }

    suspend fun logout() { runCatching { io { it.logout() } }; _state.value = EngineState.Unlinked }

    suspend fun chats(): List<WaChat> = io { e ->
        val arr = JSONArray(e.chats())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            WaChat(o.getString("id"), o.getString("title"), o.optBoolean("group"))
        }
    }

    /** Sends a made sound sticker. Returns the message ID. */
    suspend fun send(chatId: String, s: MadeSticker): String =
        io { it.sendSoundSticker(chatId, s.webp.path, s.ogg.path, s.seconds.toLong(), true, "🔊") }

    fun close() { engine?.disconnect() }
}
