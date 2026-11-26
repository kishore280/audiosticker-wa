package com.audiostickerwa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.audiostickerwa.sticker.StickerPack
import com.audiostickerwa.audio.SavedSound
import com.audiostickerwa.audio.SoundStickerTile
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CardDefaults
import java.io.File

// Stateless, previewable content composables. They get state and callbacks only.

/** Home: start an audio sticker, reuse saved ones, and manage sticker packs. Stateless; previewable with sample data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    sounds: List<SavedSound>,
    packs: List<StickerPack>,
    added: Map<String, Boolean>,
    trayFile: (StickerPack) -> File,
    linkedPhone: String?,
    snackbar: SnackbarHostState,
    onNewAudioSticker: () -> Unit,
    onOpenSound: (SavedSound) -> Unit,
    onOpenPack: (String) -> Unit,
    onCreatePack: (name: String, publisher: String, animated: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text("Sticker Studio", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (linkedPhone != null) "WhatsApp linked (+$linkedPhone) ✓" else "WhatsApp not linked yet. You link it when you send the first audio sticker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (linkedPhone != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Card(
                    Modifier.fillMaxWidth().clickable(onClick = onNewAudioSticker),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔊  Audio sticker", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "A video, GIF or image with any sound. It plays the sound in WhatsApp when someone taps it.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onNewAudioSticker) { Text("Make an audio sticker") }
                    }
                }
            }

            item { SectionHeader("My audio stickers", if (sounds.isEmpty()) null else "${sounds.size}") }
            if (sounds.isEmpty()) {
                item { EmptyNote("Audio stickers that you make show here. Tap one to hear it and send it again.") }
            } else {
                items(sounds.chunked(3), key = { row -> row.first().id }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { sound ->
                            SoundStickerTile(sound, Modifier.weight(1f).aspectRatio(1f), onClick = { onOpenSound(sound) })
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionHeader("Sticker packs", if (packs.isEmpty()) null else "${packs.size}") }
                    OutlinedButton(onClick = { showCreate = true }) { Text("New pack") }
                }
            }
            if (packs.isEmpty()) {
                item {
                    EmptyNote(
                        "A pack is a set of 3 to 30 stickers (no sound) that you add to the WhatsApp sticker tray. " +
                            "Make one from images, or from videos and GIFs (animated).",
                    )
                }
            }
            items(packs, key = { it.id }) { p ->
                PackRow(p, added[p.id] == true, trayFile(p), onClick = { onOpenPack(p.id) })
            }
        }
    }
    if (showCreate) {
        CreatePackDialog(
            onDismiss = { showCreate = false },
            onCreate = { n, pub, a -> showCreate = false; onCreatePack(n, pub, a) },
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: String?) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        count?.let { Text("  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PackRow(pack: StickerPack, isAdded: Boolean, tray: File, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StickerImage(tray, null, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.titleSmall)
                Text("${pack.publisher} · ${pack.stickers.size} stickers" + if (pack.animated) " · animated" else "", style = MaterialTheme.typography.bodySmall)
            }
            if (isAdded) Text("Added", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CreatePackDialog(onDismiss: () -> Unit, onCreate: (String, String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var animated by rememberSaveable { mutableStateOf(true) }
    var publisher by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New sticker pack") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(128) }, label = { Text("Pack name") }, singleLine = true)
                OutlinedTextField(publisher, { publisher = it.take(128) }, label = { Text("Publisher") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Animated pack")
                        Text(
                            if (animated) "Video and GIF become moving stickers." else "Still images only.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = animated, onCheckedChange = { animated = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim(), publisher.trim(), animated) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailContent(
    pack: StickerPack,
    isAdded: Boolean,
    busy: Boolean,
    stickerFile: (String) -> File,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAddImages: () -> Unit,
    onRemoveSticker: (String) -> Unit,
    onAddToWhatsApp: () -> Unit,
    onDeletePack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(pack.name) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${pack.stickers.size} / ${StickerPack.MAX_STICKERS} stickers. WhatsApp needs ${StickerPack.MIN_STICKERS} or more. " +
                    (if (pack.animated) "Videos and GIFs become animated 512×512 WebP (< 500 KB, first 8 s). "
                    else "Images become 512×512 WebP (< 100 KB). ") + "Tap a sticker to remove it.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAddImages, enabled = !busy && pack.stickers.size < StickerPack.MAX_STICKERS) {
                    Text(if (pack.animated) "Add video / GIF" else "Add images")
                }
                FilledTonalButton(onClick = onAddToWhatsApp, enabled = !busy && pack.canAddToWhatsApp && !isAdded) {
                    Text(if (isAdded) "Added to WhatsApp" else "Add to WhatsApp")
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pack.stickers, key = { it.file }) { s ->
                    var ask by remember { mutableStateOf(false) }
                    AnimatedStickerImage(
                        stickerFile(s.file), "sticker",
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0x22888888))
                            .clickable { ask = true },
                    )
                    if (ask) {
                        AlertDialog(
                            onDismissRequest = { ask = false },
                            title = { Text("Remove this sticker?") },
                            confirmButton = { TextButton(onClick = { ask = false; onRemoveSticker(s.file) }) { Text("Remove") } },
                            dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${pack.name}\"?") },
            text = { Text("This removes the pack from this app. WhatsApp can keep its copy.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeletePack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
