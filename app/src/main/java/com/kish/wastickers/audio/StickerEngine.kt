package com.kish.wastickers.audio

import android.content.Context
import com.kish.wastickers.waengine.Engine
import com.kish.wastickers.waengine.Listener
import com.kish.wastickers.waengine.Waengine
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
    private val appContext = context.applicationContext
    private val dataDir = File(context.filesDir, "wa").path
    @Volatile private var engine: Engine? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val listener = object : Listener {
        override fun onState(state: String, detail: String) {
            // Linking ends (linked, or failed): the keep-alive service is no longer needed.
            if (state == "linked" || state == "loggedout" || (state == "unlinked" && detail.isNotEmpty())) {
                EngineService.stop(appContext)
            }
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
        // Never linked (no session database): do not load the Go engine at app start. It loads on "Get link code".
        // The app opens faster, and the sticker features never depend on the engine.
        if (!File(dataDir, "session.db").exists()) {
            _state.value = EngineState.Unlinked
            return
        }
        try {
            io { e -> if (e.isLinked) e.connect() else _state.value = EngineState.Unlinked }
        } catch (e: Exception) {
            _state.value = EngineState.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Returns the code to type in WhatsApp (as "ABCD-EFGH"). A foreground service keeps the app running
     * while the user is in WhatsApp; it stops when linking ends or after 3 minutes.
     */
    suspend fun pair(phone: String): String {
        EngineService.start(appContext, "Linking WhatsApp… type the code in WhatsApp.", 180_000L)
        try {
            return io { it.pairPhone(phone) }
        } catch (e: Exception) {
            EngineService.stop(appContext)
            throw e
        }
    }

    /** Stops a link attempt so a new code can be requested. */
    suspend fun cancelPairing() {
        EngineService.stop(appContext)
        runCatching { io { it.disconnect() } }
        _state.value = EngineState.Unlinked
    }

    suspend fun logout() { runCatching { io { it.logout() } }; _state.value = EngineState.Unlinked }

    suspend fun chats(): List<WaChat> = io { e ->
        val arr = JSONArray(e.chats())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            WaChat(o.getString("id"), o.getString("title"), o.optBoolean("group"))
        }
    }

    /** Sends a made sound sticker. Returns the message ID. */
    suspend fun send(chat: WaChat, s: SavedSound): String {
        EngineService.start(appContext, "Sending sticker…", 120_000L)
        try {
            val id = io { it.sendSoundSticker(chat.id, s.webp.path, s.ogg.path, s.seconds.toLong(), true, "🔊") }
            rememberChat(chat)
            return id
        } finally {
            EngineService.stop(appContext)
        }
    }

    // ---------------------------------------------------------------- recent chats (for one-tap send)

    private val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(loadRecent())
    /** Chats the user sent to, newest first (max 6). */
    val recentChats: StateFlow<List<WaChat>> = _recent.asStateFlow()

    private fun loadRecent(): List<WaChat> = try {
        val arr = JSONArray(prefs.getString("recent", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            WaChat(o.getString("id"), o.getString("title"), o.optBoolean("group"))
        }
    } catch (e: Exception) { emptyList() }

    private fun rememberChat(chat: WaChat) {
        val list = (listOf(chat) + _recent.value.filter { it.id != chat.id }).take(6)
        _recent.value = list
        val arr = JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("title", it.title).put("group", it.group)) }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    fun close() { engine?.disconnect() }
}
