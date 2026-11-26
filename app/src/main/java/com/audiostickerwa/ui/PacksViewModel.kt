package com.audiostickerwa.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiostickerwa.sticker.StickerPack
import com.audiostickerwa.sticker.StickerRepository
import com.audiostickerwa.sticker.WhatsApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

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
        val packs = repo.packs()
        val added = packs.associate { it.id to WhatsApp.isAdded(getApplication(), it.id) }
        _state.update { it.copy(packs = packs, added = added) }
    }

    fun createPack(name: String, publisher: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        val id = repo.createPack(name.ifBlank { "My stickers" }, publisher.ifBlank { "Me" })
        refresh().join()
        onCreated(id)
    }

    fun deletePack(id: String) = viewModelScope.launch {
        repo.deletePack(id)
        refresh()
    }

    fun addImages(id: String, uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _state.update { it.copy(busy = true) }
        val r = repo.addImages(id, uris)
        val msg = buildString {
            append("Added ${r.added} sticker(s)")
            if (r.failed > 0) append(", ${r.failed} failed")
            if (r.skippedFull > 0) append(", ${r.skippedFull} skipped (max ${StickerPack.MAX_STICKERS})")
        }
        _state.update { it.copy(busy = false, message = msg) }
        refresh()
    }

    fun removeSticker(id: String, file: String) = viewModelScope.launch {
        repo.removeSticker(id, file)
        refresh()
    }

    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }
    fun messageShown() = _state.update { it.copy(message = null) }
}
