package com.audiostickerwa.audio

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    var chatPicker by remember { mutableStateOf<List<WaChat>?>(null) }

    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) maker.setVisual(uri)
    }
    // GIF and audio come from the document picker; the result goes to the picture or the sound by its type.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("audio/") || mime == "application/ogg") maker.setAudio(uri) else maker.setVisual(uri)
    }

    AudioMakerContent(
        ui = maker.ui(),
        engineState = engineState,
        capsWarning = capsWarning?.ifEmpty { null },
        progress = progress,
        onBack = onBack,
        onLink = onShowWhatsAppWeb,
        onRetry = engine::reload,
        onTab = maker::selectTab,
        onPickVisual = {
            when (maker.tab) {
                VisualKind.Video -> pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                VisualKind.Image -> pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                VisualKind.Gif -> pickFile.launch("image/gif")
            }
        },
        onPickAudio = { pickFile.launch("audio/*") },
        onSave = maker::save,
        onRemoveAudio = { maker.setAudio(null) },
        onRangeChange = maker::changeRange,
        onCropChange = maker::changeCrop,
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

/** Stateless, previewable content. Flow: tab → choose → (sound) → trim with preview → create → hear → send / save. */
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
    onTab: (VisualKind) -> Unit,
    onPickVisual: () -> Unit,
    onPickAudio: () -> Unit,
    onRemoveAudio: () -> Unit,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onCropChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    onSendClick: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = engineState == EngineState.Ready
    var playing by remember(ui.visual, ui.audio) { mutableStateOf(false) }
    var moreOptions by remember { mutableStateOf(false) }
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
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ready) EngineCard(engineState, onLink = onLink, onRetry = onRetry)
            capsWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                VisualKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = ui.tab == k,
                        onClick = { onTab(k) },
                        shape = SegmentedButtonDefaults.itemShape(i, VisualKind.entries.size),
                    ) { Text(k.label) }
                }
            }

            // ---- 1. Choose
            Step(1, "Choose ${ui.tab.article} ${ui.tab.label.lowercase()}")
            FileCard(
                title = ui.visual?.name,
                subtitle = ui.visual?.let { if (it.durationMs > 0) fmt(it.durationMs.toFloat()) else "Picture" },
                emptyText = "Select ${ui.tab.article} ${ui.tab.label.lowercase()}",
                onClick = onPickVisual,
            )

            // ---- Sound
            if (ui.visual != null) {
                if (ui.tab == VisualKind.Video) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (ui.audio == null) "Sound: the video's own sound" else "Sound: ${ui.audio.name}",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        )
                        if (ui.audio == null) TextButton(onClick = onPickAudio) { Text("Use other sound") }
                        else TextButton(onClick = onRemoveAudio) { Text("Use video sound") }
                    }
                } else {
                    Step(2, "Choose a sound")
                    FileCard(
                        title = ui.audio?.name,
                        subtitle = ui.audio?.let { fmt(it.durationMs.toFloat()) },
                        emptyText = "Select an audio file (MP3, M4A, WAV, OGG…)",
                        onClick = onPickAudio,
                    )
                }
            }

            // ---- Preview + trim
            if (ui.visual != null) {
                ClipPreview(
                    kind = ui.tab, visual = ui.visual.uri, audio = ui.audio?.uri, range = ui.range,
                    playing = playing, onFinished = { playing = false },
                )
            }
            if (ui.timedMs > 0) {
                RangeSlider(
                    value = ui.range,
                    onValueChange = { r ->
                        playing = false
                        onRangeChange(r.start..minOf(r.endInclusive, r.start + AudioMakerState.MAX_LEN_MS))
                    },
                    valueRange = 0f..ui.timedMs.toFloat(),
                )
                val len = ui.range.endInclusive - ui.range.start
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "■ Stop" else "▶ Play") }
                    Spacer(Modifier.width(12.dp))
                    Text("${fmt(ui.range.start)} → ${fmt(ui.range.endInclusive)}", Modifier.weight(1f))
                    AssistChip(onClick = {}, label = { Text(fmt(len)) })
                }
                Text(
                    "Drag the two ends to choose the part. " +
                        if (len > AudioMakerState.GOOD_LEN_MS) "Shorter than 10 s looks better." else "10 seconds or less works best.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- 2/3. Create and send
            HorizontalDivider()
            Step(if (ui.tab == VisualKind.Video) 2 else 3, "Create and send")
            Button(onClick = onCreate, enabled = ui.canCreate && ready && ui.result == null, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        ui.busy && ui.result == null -> "Creating…"
                        ui.result != null -> "✓ Sticker created"
                        else -> "Create sticker"
                    },
                )
            }
            if (ui.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress ?: "Working…", style = MaterialTheme.typography.bodySmall)
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.lastSent?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            ui.result?.let { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TapToHearSticker(r, Modifier.size(140.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Tap the sticker to hear it.", style = MaterialTheme.typography.bodySmall)
                        Text("${r.seconds} s · ${r.webpBytes / 1024} KB + ${r.oggBytes / 1024} KB sound",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSendClick, enabled = !ui.busy && ready, modifier = Modifier.weight(1f)) { Text("Send") }
                    OutlinedButton(onClick = onSave, enabled = !ui.busy) { Text("Save") }
                }
                ui.saved?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            // ---- More options
            TextButton(onClick = { moreOptions = !moreOptions }) { Text(if (moreOptions) "▾ More options" else "▸ More options") }
            if (moreOptions) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Crop to fill the square", Modifier.weight(1f))
                    Switch(checked = ui.crop, onCheckedChange = onCropChange)
                }
                if (ready) TextButton(onClick = onLink) { Text("Show WhatsApp Web") }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

private val VisualKind.label get() = when (this) { VisualKind.Video -> "Video"; VisualKind.Image -> "Image"; VisualKind.Gif -> "GIF" }
private val VisualKind.article get() = if (this == VisualKind.Image) "an" else "a"

@Composable
private fun Step(n: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FileCard(title: String?, subtitle: String?, emptyText: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title ?: emptyText, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text(if (title == null) "Select" else "Change", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The made sticker; a tap plays its sound. The MediaPlayer belongs to this composable. */
@Composable
private fun TapToHearSticker(r: MadeSticker, modifier: Modifier = Modifier) {
    var player by remember(r.stamp) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(r.stamp) { onDispose { player?.release(); player = null } }
    AnimatedStickerImage(
        r.webp, "sticker preview",
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x22888888)).clickable {
            player?.release()
            player = try { MediaPlayer().apply { setDataSource(r.ogg.path); prepare(); start() } } catch (e: Exception) { null }
        },
    )
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
