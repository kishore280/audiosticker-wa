package com.audiostickerwa.audio

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiostickerwa.ui.AnimatedStickerImage
import kotlinx.coroutines.launch

/**
 * Wiring: connects the engine, the maker state holder and the system pickers to [AudioMakerContent].
 * WhatsApp Web runs hidden; it shows only when the user links the account.
 */
@Composable
fun AudioMakerScreen(
    engine: WaEngine,
    maker: AudioMakerState,
    onBack: () -> Unit,
    onShowWhatsAppWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engineState by engine.state.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()
    val capsWarning by engine.capsWarning.collectAsStateWithLifecycle()
    // The chat list dialog is local UI: loading it is a user-event coroutine of this screen.
    val scope = rememberCoroutineScope()
    var chatPicker by remember { mutableStateOf<List<WaChat>?>(null) }

    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) maker.setVisual(uri)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) maker.setAudio(uri)
    }

    AudioMakerContent(
        ui = maker.ui(),
        engineState = engineState,
        capsWarning = capsWarning?.ifEmpty { null },
        progress = progress,
        onBack = onBack,
        onLink = onShowWhatsAppWeb,
        onRetry = engine::reload,
        onPickVisual = { pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        onPickAudio = { pickAudio.launch("audio/*") },
        onRemoveAudio = { maker.setAudio(null) },
        onRangeChange = { maker.range = it },
        onCropChange = { maker.crop = it },
        onNormalizeChange = { maker.normalize = it },
        onCreate = maker::create,
        onSendClick = {
            scope.launch {
                chatPicker = try { engine.chats() } catch (e: Exception) { maker.error = e.message; null }
            }
        },
        modifier = modifier,
    )

    chatPicker?.let { chats ->
        ChatPickerDialog(
            chats = chats,
            onDismiss = { chatPicker = null },
            onPick = { c -> chatPicker = null; maker.send(c) },
        )
    }
}

/** Stateless, previewable content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMakerContent(
    ui: AudioMakerUi,
    engineState: EngineState,
    capsWarning: String?,
    progress: String?,
    onBack: () -> Unit,
    onLink: () -> Unit,
    onRetry: () -> Unit,
    onPickVisual: () -> Unit,
    onPickAudio: () -> Unit,
    onRemoveAudio: () -> Unit,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onCropChange: (Boolean) -> Unit,
    onNormalizeChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = engineState == EngineState.Ready
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Audio sticker") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EngineCard(engineState, onLink = onLink, onRetry = onRetry)
            capsWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("1. Picture", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPickVisual) {
                    Text(if (ui.visualKind == null) "Select video, GIF or image" else "Change")
                }
                Spacer(Modifier.width(12.dp))
                ui.visualKind?.let { Text(it.name + if (ui.visualMs > 0) " · ${fmt(ui.visualMs.toFloat())}" else "") }
            }

            Text("2. Sound", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPickAudio) { Text(if (ui.audioMs == null) "Select audio file" else "Change audio") }
                Spacer(Modifier.width(8.dp))
                if (ui.audioMs != null) TextButton(onClick = onRemoveAudio) { Text("Remove") }
            }
            Text(
                when {
                    ui.audioMs != null -> "Audio file · ${fmt(ui.audioMs.toFloat())}"
                    ui.visualKind == VisualKind.Video -> "The video's own sound is used."
                    ui.needsAudio -> "A GIF or image needs an audio file."
                    else -> "Select the picture first."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (ui.timedMs > 0) {
                Text("3. Trim", style = MaterialTheme.typography.titleMedium)
                RangeSlider(
                    value = ui.range,
                    onValueChange = { r ->
                        // Keep the clip at most MAX_LEN_MS long.
                        onRangeChange(r.start..minOf(r.endInclusive, r.start + AudioMakerState.MAX_LEN_MS))
                    },
                    valueRange = 0f..ui.timedMs.toFloat(),
                )
                Text("${fmt(ui.range.start)} – ${fmt(ui.range.endInclusive)}  (${fmt(ui.range.endInclusive - ui.range.start)})")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Crop to fill the square", Modifier.weight(1f))
                Switch(checked = ui.crop, onCheckedChange = onCropChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Make sound louder (normalize)", Modifier.weight(1f))
                Switch(checked = ui.normalize, onCheckedChange = onNormalizeChange)
            }

            Button(onClick = onCreate, enabled = ui.canCreate && ready, modifier = Modifier.fillMaxWidth()) {
                Text("Create sticker")
            }
            if (ui.busy) Text(progress ?: "Working…", style = MaterialTheme.typography.bodySmall)
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            ui.result?.let { r ->
                HorizontalDivider()
                Text("Preview", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedStickerImage(r.webp, "sticker preview", Modifier.size(160.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoundButton(r)
                        Text(
                            "Sticker ${r.webpBytes / 1024} KB · sound ${r.oggBytes / 1024} KB, ${r.seconds} s",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Button(onClick = onSendClick, enabled = !ui.busy && ready, modifier = Modifier.fillMaxWidth()) {
                    Text("Send to WhatsApp")
                }
            }
            ui.lastSent?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun EngineCard(state: EngineState, onLink: () -> Unit, onRetry: () -> Unit) {
    val (title, body) = when (state) {
        EngineState.Starting -> "Starting…" to "Downloading the sticker engine."
        is EngineState.NotReady -> "WhatsApp not linked" to
            "Link one time: push \"Link WhatsApp\", then on that page select \"Link with phone number instead\" " +
            "and type the code in WhatsApp → Settings → Linked devices. (${state.detail})"
        EngineState.Ready -> "WhatsApp linked" to
            "Ready. This uses WhatsApp Web, which is not an official API: send stickers by hand only, never in bulk."
    }
    Card(
        colors = if (state == EngineState.Ready) CardDefaults.cardColors() else
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state != EngineState.Ready) OutlinedButton(onClick = onLink) { Text("Link WhatsApp") }
                if (state != EngineState.Ready) TextButton(onClick = onRetry) { Text("Reload") }
                if (state == EngineState.Ready) TextButton(onClick = onLink) { Text("Show WhatsApp Web") }
            }
        }
    }
}

/** Plays the Ogg/Opus sound. The MediaPlayer belongs to this composable and is released with it. */
@Composable
private fun SoundButton(r: MadeSticker) {
    var player by remember(r.stamp) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(r.stamp) { onDispose { player?.release(); player = null } }
    OutlinedButton(onClick = {
        player?.release()
        player = try {
            MediaPlayer().apply { setDataSource(r.ogg.path); prepare(); start() }
        } catch (e: Exception) { null }
    }) { Text("Play sound") }
}

@Composable
private fun ChatPickerDialog(chats: List<WaChat>, onDismiss: () -> Unit, onPick: (WaChat) -> Unit) {
    var q by remember { mutableStateOf("") }
    val me = WaChat("self", "Me (message yourself)", false)
    val shown = listOf(me) + chats.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { c ->
                        Text(
                            (if (c.group) "👥 " else "") + c.title,
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun fmt(ms: Float): String = "%.1f s".format(ms / 1000f)
