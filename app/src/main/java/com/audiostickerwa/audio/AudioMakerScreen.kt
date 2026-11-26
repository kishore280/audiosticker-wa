package com.audiostickerwa.audio

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Wiring: engine, maker state, shared sticker actions and system pickers -> [AudioMakerContent]. */
@Composable
fun AudioMakerScreen(
    engine: StickerEngine,
    maker: AudioMakerState,
    actions: SoundActions,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engineState by engine.state.collectAsStateWithLifecycle()
    val recent by engine.recentChats.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sendSheet by remember { mutableStateOf(false) }
    var linkBusy by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    // The keep-alive notification during linking needs this permission on Android 13+ (linking works without it).
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) maker.setVisual(uri)
    }
    // GIF and audio come from the document picker; the result goes to the picture or the sound by its type.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("audio/") || mime == "application/ogg") maker.setAudio(uri) else maker.setVisual(uri)
    }
    val result = maker.result

    AudioMakerContent(
        ui = maker.ui(),
        engineState = engineState,
        progress = maker.progress,
        link = LinkUi(busy = linkBusy, error = linkError),
        quickChats = (listOf(SelfChat) + recent.filter { it.id != SelfChat.id }).take(3),
        sending = result != null && actions.sendingId == result.id,
        snackbar = snackbar,
        onBack = onBack,
        onPair = { phone ->
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            scope.launch {
                linkBusy = true; linkError = null
                try { engine.pair(phone) } catch (e: Exception) { linkError = e.message ?: e.toString() }
                linkBusy = false
            }
        },
        onCancelPairing = { scope.launch { engine.cancelPairing() } },
        onLogout = { scope.launch { engine.logout() } },
        onTab = maker::selectTab,
        onPickVisual = {
            when (maker.tab) {
                VisualKind.Video -> pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                VisualKind.Image -> pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                VisualKind.Gif -> pickFile.launch("image/gif")
            }
        },
        onPickAudio = { pickFile.launch("audio/*") },
        onRemoveAudio = { maker.setAudio(null) },
        onRangeChange = maker::changeRange,
        onCropChange = maker::changeCrop,
        onPosition = maker::changePosition,
        onCreate = maker::create,
        onQuickSend = { c -> result?.let { actions.send(it, c) } },
        onMoreChats = { sendSheet = true },
        onExport = { result?.let { actions.export(it) } },
        onMakeAnother = maker::reset,
        modifier = modifier,
    )

    if (sendSheet && result != null) {
        SendSheet(engine, onPick = { c -> sendSheet = false; actions.send(result, c) }, onDismiss = { sendSheet = false })
    }
}

/** Stateless, previewable content. Flow: tab → choose → (sound) → square preview + trim → create → hear → send. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AudioMakerContent(
    ui: AudioMakerUi,
    engineState: EngineState,
    progress: String?,
    link: LinkUi,
    quickChats: List<WaChat>,
    sending: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onPair: (String) -> Unit,
    onCancelPairing: () -> Unit,
    onLogout: () -> Unit,
    onTab: (VisualKind) -> Unit,
    onPickVisual: () -> Unit,
    onPickAudio: () -> Unit,
    onRemoveAudio: () -> Unit,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onCropChange: (Boolean) -> Unit,
    onPosition: (Float, Float) -> Unit,
    onCreate: () -> Unit,
    onQuickSend: (WaChat) -> Unit,
    onMoreChats: () -> Unit,
    onExport: () -> Unit,
    onMakeAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linked = engineState is EngineState.Linked
    var playing by remember(ui.visual, ui.audio) { mutableStateOf(false) }
    var moreOptions by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("New audio sticker") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!linked) LinkCard(engineState, link, onPair, onCancelPairing)

            ui.result?.let { r ->
                // ---- Done: the sticker is saved in the library.
                ResultCard(r, quickChats, linked, sending, onQuickSend, onMoreChats, onExport, onMakeAnother)
                return@Column
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                VisualKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = ui.tab == k, onClick = { onTab(k) },
                        shape = SegmentedButtonDefaults.itemShape(i, VisualKind.entries.size),
                    ) { Text(k.label) }
                }
            }

            Step(1, "Choose ${ui.tab.article} ${ui.tab.label.lowercase()}")
            FileCard(
                title = ui.visual?.name,
                subtitle = ui.visual?.let { if (it.durationMs > 0) fmt(it.durationMs.toFloat()) else "Picture" },
                emptyText = "Select ${ui.tab.article} ${ui.tab.label.lowercase()}",
                onClick = onPickVisual,
            )

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

                // ---- Square preview = the sticker, with live Fit / Fill.
                ClipPreview(
                    kind = ui.tab, visual = ui.visual, audio = ui.audio, range = ui.range, framing = ui.framing,
                    playing = playing, onFinished = { playing = false }, onPosition = onPosition,
                )
                FramingToggle(ui.framing.crop, onCropChange)
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
                    OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "■ Stop" else "▶ Play part") }
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

            HorizontalDivider()
            Button(onClick = onCreate, enabled = ui.canCreate, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.busy) "Creating…" else "Create sticker")
            }
            if (ui.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress ?: "Working…", style = MaterialTheme.typography.bodySmall)
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            TextButton(onClick = { moreOptions = !moreOptions }) { Text(if (moreOptions) "▾ More options" else "▸ More options") }
            if (moreOptions && engineState is EngineState.Linked) {
                Text("Linked to +${engineState.phone}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onLogout) { Text("Unlink this app from WhatsApp") }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    r: SavedSound,
    quickChats: List<WaChat>,
    linked: Boolean,
    sending: Boolean,
    onQuickSend: (WaChat) -> Unit,
    onMoreChats: () -> Unit,
    onExport: () -> Unit,
    onMakeAnother: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✓ Sticker created", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            SoundStickerTile(r, Modifier.size(200.dp))
            Text(
                "Tap it to hear it. ${r.seconds} s · ${(r.webpBytes + r.oggBytes) / 1024} KB. Saved in My audio stickers.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Send to", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickChats.forEach { c ->
                    FilledTonalButton(onClick = { onQuickSend(c) }, enabled = linked && !sending) {
                        Text(if (c.id == "self") "Me" else c.title, maxLines = 1)
                    }
                }
                OutlinedButton(onClick = onMoreChats, enabled = linked && !sending) { Text("Other chat…") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExport) { Text("Export") }
                Button(onClick = onMakeAnother) { Text("Make another") }
            }
        }
    }
}

/** Fit (whole picture) / Fill (square that you can move). Shared with the sticker-pack editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FramingToggle(crop: Boolean, onCropChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = !crop, onClick = { onCropChange(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
            Text("Fit (whole)")
        }
        SegmentedButton(selected = crop, onClick = { onCropChange(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
            Text("Fill (drag to move)")
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

private fun fmt(ms: Float): String = "%.1f s".format(ms / 1000f)
