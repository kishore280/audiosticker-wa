package com.kish.wastickers.audio

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kish.wastickers.ui.AnimatedStickerImage
import java.text.DateFormat
import java.util.Date

/** Plays a sticker's sound. The MediaPlayer belongs to the composable that calls this, and is released with it. */
@Composable
fun rememberSoundPlayer(key: Any?): (java.io.File) -> Unit {
    var player by remember(key) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(key) { onDispose { player?.release(); player = null } }
    return { f ->
        player?.release()
        player = try { MediaPlayer().apply { setDataSource(f.path); prepare(); start() } } catch (e: Exception) { null }
    }
}

/** Sticker with sound: animation plays, a tap plays the sound. */
@Composable
fun SoundStickerTile(item: SavedSound, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val play = rememberSoundPlayer(item.id)
    AnimatedStickerImage(
        item.webp, "audio sticker",
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x22888888))
            .clickable { if (onClick != null) onClick() else play(item.ogg) },
    )
}

/** Details of a saved audio sticker: hear it, send it, export it, delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSheet(
    item: SavedSound,
    sending: Boolean,
    linked: Boolean,
    onSend: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val play = rememberSoundPlayer(item.id)
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedStickerImage(
                item.webp, "audio sticker",
                Modifier.size(220.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x22888888)).clickable { play(item.ogg) },
            )
            Text("Tap the sticker to hear it", style = MaterialTheme.typography.bodySmall)
            Text(
                "${item.seconds} s · ${(item.webpBytes + item.oggBytes) / 1024} KB · " +
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.createdAt)) +
                    if (item.sentCount > 0) " · sent ${item.sentCount}×" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSend, enabled = linked && !sending, modifier = Modifier.fillMaxWidth()) {
                Text(if (sending) "Sending…" else if (linked) "Send" else "Link WhatsApp to send")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport) { Text("Export to Downloads") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this audio sticker?") },
            text = { Text("It is removed from this app. Messages that you already sent stay in WhatsApp.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
