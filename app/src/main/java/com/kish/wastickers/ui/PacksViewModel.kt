package com.kish.wastickers.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kish.wastickers.sticker.StickerPack
import com.kish.wastickers.sticker.StickerRepository
import com.kish.wastickers.sticker.WhatsApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import com.kish.wastickers.sticker.Framing

data class PacksUiState(
    val packs: List<StickerPack> = emptyList(),
    /** Pack id -> true when the pack is already in WhatsApp. */
    val added: Map<String, Boolean> = emptyMap(),
    val busy: Boolean = false,
    /** Short message for a snackbar. The screen calls [PacksViewModel.messageShown] after it shows it. */
    val message: String? = null,
)

/** Screen state holder for the sticker packs. It owns the durable pack data. */
class PacksViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = StickerRepository(app)
    private val _state = MutableStateFlow(PacksUiState())
    val state: StateFlow<PacksUiState> = _state.asStateFlow()

    init { refresh() }

    fun fileOf(packId: String, name: String): File = repo.fileOf(packId, name)

    fun refresh() = viewModelScope.launch {
        var packs = repo.packs()
        val added = packs.associate { it.id to WhatsApp.isAdded(getApplication(), it.id) }
        // Packs added before version tracking: take the current version as the added one.
        val untracked = packs.filter { added[it.id] == true && it.addedVersion == null }
        if (untracked.isNotEmpty()) {
            untracked.forEach { repo.markAdded(it.id) }
            packs = repo.packs()
        }
        _state.update { it.copy(packs = packs, added = added) }
    }

    fun createPack(name: String, publisher: String, animated: Boolean, onCreated: (String) -> Unit) = viewModelScope.launch {
        val id = repo.createPack(name.ifBlank { "My stickers" }, publisher.ifBlank { "Me" }, animated)
        refresh().join()
        onCreated(id)
    }

    fun deletePack(id: String) = viewModelScope.launch {
        repo.deletePack(id)
        refresh()
    }

    fun addImages(id: String, uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _state.update { it.copy(busy = true, message = "Converting ${uris.size} file(s)…") }
        val r = repo.addImages(id, uris)
        val msg = buildString {
            append("Added ${r.added} sticker(s)")
            if (r.failed > 0) append(", ${r.failed} failed")
            r.firstError?.let { append(". ").append(it) }
            if (r.skippedFull > 0) append(", ${r.skippedFull} skipped (max ${StickerPack.MAX_STICKERS})")
        }
        _state.update { it.copy(busy = false, message = msg) }
        refresh()
    }

    fun addClip(id: String, uri: Uri, startMs: Long, durMs: Long, framing: Framing) = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = "Converting…") }
        val r = repo.addClip(id, uri, startMs, durMs, framing)
        val msg = when {
            r.added > 0 -> "Sticker added"
            r.skippedFull > 0 -> "The pack is full (max ${StickerPack.MAX_STICKERS})"
            else -> "Not added: ${r.firstError ?: "unknown error"}"
        }
        _state.update { it.copy(busy = false, message = msg) }
        refresh()
    }

    fun setEmojis(id: String, file: String, emojis: List<String>) = viewModelScope.launch {
        repo.setEmojis(id, file, emojis)
        refresh()
    }

    /** Result from WhatsApp's add-pack screen. */
    fun onWhatsAppResult(id: String, ok: Boolean, alreadyAdded: Boolean, validationError: String?) = viewModelScope.launch {
        when {
            ok || alreadyAdded -> { repo.markAdded(id); showMessage(if (alreadyAdded) "Already in WhatsApp ✓" else "Added to WhatsApp ✓") }
            validationError != null -> showMessage("WhatsApp error: $validationError")
        }
        refresh()
    }

    fun removeSticker(id: String, file: String) = viewModelScope.launch {
        repo.removeSticker(id, file)
        refresh()
    }

    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }
    fun messageShown() = _state.update { it.copy(message = null) }
}
