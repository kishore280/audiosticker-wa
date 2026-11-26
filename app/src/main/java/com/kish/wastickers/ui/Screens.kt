package com.kish.wastickers.ui

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
import com.kish.wastickers.sticker.StickerPack
import com.kish.wastickers.audio.SavedSound
import com.kish.wastickers.settings.AppUpdate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.kish.wastickers.audio.SoundStickerTile
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
    fileOf: (packId: String, name: String) -> File,
    linkedPhone: String?,
    snackbar: SnackbarHostState,
    onNewAudioSticker: () -> Unit,
    onOpenSound: (SavedSound) -> Unit,
    onOpenPack: (String) -> Unit,
    onCreatePack: (name: String, publisher: String, animated: Boolean) -> Unit,
    defaultPublisher: String = "",
    update: AppUpdate? = null,
    onOpenSettings: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sticker Studio", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (linkedPhone != null) "WhatsApp linked (+$linkedPhone) ✓" else "WhatsApp not linked yet. You link it when you send the first audio sticker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (linkedPhone != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
            }
            if (update != null) {
                item {
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpenUrl(update.downloadUrl) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Update available: ${update.version}", style = MaterialTheme.typography.titleSmall)
                                Text("Tap to download the new APK, then open it to install.", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Download", color = MaterialTheme.colorScheme.primary)
                        }
                    }
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
                PackRow(p, added[p.id] == true, { name -> fileOf(p.id, name) }, onClick = { onOpenPack(p.id) })
            }
        }
    }
    if (showCreate) {
        CreatePackDialog(
            defaultPublisher = defaultPublisher,
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
private fun PackRow(pack: StickerPack, isAdded: Boolean, fileOf: (String) -> File, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${pack.publisher} · ${pack.stickers.size} stickers" + if (pack.animated) " · animated" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    when (pack.status(isAdded)) {
                        PackStatus.InWhatsApp -> "✓ In WhatsApp"
                        PackStatus.HasChanges -> "Changes to add"
                        PackStatus.NotAdded -> "Not added"
                        PackStatus.NeedsMore -> "Needs ${StickerPack.MIN_STICKERS - pack.stickers.size} more"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (pack.status(isAdded) == PackStatus.InWhatsApp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pack.stickers.take(5).forEach { s -> StickerImage(fileOf(s.file), null, Modifier.weight(1f).aspectRatio(1f)) }
                repeat(5 - minOf(5, pack.stickers.size)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** New pack: name, publisher, still or animated. */
@Composable
fun CreatePackDialog(
    defaultPublisher: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean) -> Unit,
    initialAnimated: Boolean = true,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var animated by rememberSaveable { mutableStateOf(initialAnimated) }
    var publisher by rememberSaveable { mutableStateOf(defaultPublisher) }
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

