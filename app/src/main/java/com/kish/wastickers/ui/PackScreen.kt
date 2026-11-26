package com.kish.wastickers.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kish.wastickers.audio.ClipPreview
import com.kish.wastickers.audio.FramingToggle
import com.kish.wastickers.audio.Media
import com.kish.wastickers.audio.MediaProbe
import com.kish.wastickers.audio.VisualKind
import com.kish.wastickers.sticker.Framing
import com.kish.wastickers.sticker.Sticker
import com.kish.wastickers.sticker.StickerPack
import java.io.File

/** Where the pack is relative to WhatsApp; drives the floating button. */
enum class PackStatus { NeedsMore, NotAdded, HasChanges, InWhatsApp }

fun StickerPack.status(isAdded: Boolean): PackStatus = when {
    !canAddToWhatsApp -> PackStatus.NeedsMore
    !isAdded -> PackStatus.NotAdded
    hasChangesSinceAdded -> PackStatus.HasChanges
    else -> PackStatus.InWhatsApp
}

/**
 * Sticker pack editor: tray icon, numbered slots 1..30 (empty slots add stickers), emoji badges, and a floating
 * Add / Add changes button. Stateless except for local UI (menu, open sticker); previewable with sample data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackContent(
    pack: StickerPack,
    status: PackStatus,
    busy: Boolean,
    fileOf: (String) -> File,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSetEmojis: (Sticker, List<String>) -> Unit,
    onRemoveSticker: (Sticker) -> Unit,
    onAddToWhatsApp: () -> Unit,
    onDeletePack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var openSticker by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(pack.name, maxLines = 1)
                        Text(pack.publisher, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete pack") }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { PackActionBar(pack, status, busy, onAddToWhatsApp) },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        (if (pack.animated) "Animated pack · videos and GIFs" else "Sticker pack · images") +
                            " · ${pack.stickers.size}/${StickerPack.MAX_STICKERS}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            item(span = { GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Slot(Modifier.width(120.dp), label = "tray icon") {
                        if (pack.stickers.isNotEmpty()) StickerImage(fileOf(pack.trayFile), "tray icon", Modifier.fillMaxSize().padding(16.dp))
                        else Text("Made from\nsticker 1", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            itemsIndexed(List(StickerPack.MAX_STICKERS) { pack.stickers.getOrNull(it) }) { i, s ->
                if (s != null) {
                    Slot(Modifier.clickable { openSticker = s.file }, label = "${i + 1}", badge = s.emojis.firstOrNull()) {
                        if (pack.animated) AnimatedStickerImage(fileOf(s.file), "sticker ${i + 1}", Modifier.fillMaxSize().padding(10.dp))
                        else StickerImage(fileOf(s.file), "sticker ${i + 1}", Modifier.fillMaxSize().padding(10.dp))
                    }
                } else {
                    Slot(Modifier.clickable(enabled = !busy, onClick = onAdd), label = "${i + 1}", faded = i > pack.stickers.size) {
                        Icon(Icons.Filled.Add, "Add sticker", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    }
                }
            }
            item(span = { GridItemSpan(3) }) { Spacer(Modifier.size(72.dp)) }
        }
    }

    pack.stickers.firstOrNull { it.file == openSticker }?.let { s ->
        StickerSheet(
            sticker = s, file = fileOf(s.file), animated = pack.animated,
            onEmojis = { onSetEmojis(s, it) },
            onRemove = { openSticker = null; onRemoveSticker(s) },
            onDismiss = { openSticker = null },
        )
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

@Composable
private fun PackActionBar(pack: StickerPack, status: PackStatus, busy: Boolean, onAddToWhatsApp: () -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
        val need = StickerPack.MIN_STICKERS - pack.stickers.size
        when (status) {
            PackStatus.InWhatsApp -> OutlinedButton(onClick = onAddToWhatsApp, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("✓ In WhatsApp")
            }
            else -> Button(
                onClick = onAddToWhatsApp,
                enabled = !busy && status != PackStatus.NeedsMore,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366), contentColor = Color.Black),
            ) {
                Text(
                    when (status) {
                        PackStatus.NeedsMore -> "Add $need more sticker${if (need == 1) "" else "s"} to send to WhatsApp"
                        PackStatus.HasChanges -> "Add changes to WhatsApp"
                        else -> "Add to WhatsApp"
                    },
                )
            }
        }
    }
}

/** Dashed rounded slot with a number label and an optional emoji badge, like WhatsApp sticker makers. */
@Composable
private fun Slot(
    modifier: Modifier = Modifier,
    label: String,
    badge: String? = null,
    faded: Boolean = false,
    content: @Composable () -> Unit,
) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = if (faded) 0.35f else 0.8f)
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).drawBehind {
            drawRoundRect(
                color = color, cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
        Text(
            label,
            Modifier.align(Alignment.TopStart).padding(6.dp).background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall,
        )
        badge?.let {
            Text(
                it, Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x33FFFFFF), CircleShape).padding(3.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val EMOJIS = listOf(
    "😀", "😂", "🤣", "😍", "🥰", "😘", "😎", "🤔", "😮", "😢", "😭", "😡", "🤬", "😱", "🥳", "😴",
    "👍", "👏", "🙏", "💪", "❤️", "💔", "🔥", "🎉", "💯", "😏", "🙄", "🤦", "🤷", "👋", "🐱", "🔊",
)

/** One sticker: big preview, 1–3 emojis (WhatsApp uses them for search), remove. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StickerSheet(
    sticker: Sticker,
    file: File,
    animated: Boolean,
    onEmojis: (List<String>) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember(sticker.file) { mutableStateOf(sticker.emojis) }
    ModalBottomSheet(onDismissRequest = { if (chosen != sticker.emojis && chosen.isNotEmpty()) onEmojis(chosen); onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (animated) AnimatedStickerImage(file, "sticker", Modifier.size(200.dp)) else StickerImage(file, "sticker", Modifier.size(200.dp))
            Text("Emojis (1 to 3) · ${chosen.joinToString(" ")}", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EMOJIS.forEach { e ->
                    FilterChip(
                        selected = e in chosen,
                        onClick = {
                            chosen = when {
                                e in chosen && chosen.size > 1 -> chosen - e
                                e in chosen -> chosen
                                chosen.size >= 3 -> chosen.drop(1) + e
                                else -> chosen + e
                            }
                        },
                        label = { Text(e) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRemove) { Text("Remove sticker", color = MaterialTheme.colorScheme.error) }
                Button(onClick = { if (chosen != sticker.emojis) onEmojis(chosen); onDismiss() }) { Text("Done") }
            }
        }
    }
}

/**
 * Editor for one video or GIF before it goes into an animated pack: square preview, Fit / Fill with a movable
 * square, and trim (WhatsApp animated stickers: up to 10 s and 500 KB, so short parts work best).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipEditorDialog(uri: Uri, onAdd: (startMs: Long, durMs: Long, framing: Framing) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var probed by remember(uri) { mutableStateOf<Pair<VisualKind, Media>?>(null) }
    var framing by remember(uri) { mutableStateOf(Framing()) }
    var range by remember(uri) { mutableStateOf(0f..0f) }
    var playing by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val p = MediaProbe.visual(context, uri)
        probed = p
        val d = p.second.durationMs.toFloat()
        range = 0f..minOf(d, 3000f)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("New sticker") },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("Cancel") } },
                )
            },
        ) { pad ->
            val p = probed
            Column(
                Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (p == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                val (kind, media) = p
                ClipPreview(
                    kind = kind, visual = media, audio = null, range = range, framing = framing,
                    playing = playing, onFinished = { playing = false },
                    onPosition = { x, y -> framing = framing.copy(x = x, y = y) },
                )
                FramingToggle(framing.crop) { framing = framing.copy(crop = it) }
                if (kind == VisualKind.Video && media.durationMs > 0) {
                    RangeSlider(
                        value = range,
                        onValueChange = { r -> playing = false; range = r.start..minOf(r.endInclusive, r.start + 8000f) },
                        valueRange = 0f..media.durationMs.toFloat(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "■ Stop" else "▶ Play part") }
                        Spacer(Modifier.width(12.dp))
                        Text("%.1f s".format((range.endInclusive - range.start) / 1000f), Modifier.weight(1f))
                    }
                    Text(
                        "WhatsApp allows 500 KB per animated sticker: 2–4 seconds work best.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = {
                        val dur = if (kind == VisualKind.Video) (range.endInclusive - range.start).toLong() else 8000L
                        onAdd(range.start.toLong(), dur.coerceAtLeast(300), framing)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add to pack") }
            }
        }
    }
}

/** WhatsApp and WhatsApp Business are both installed: the user chooses (no system chooser). */
@Composable
fun WhatsAppChoiceDialog(apps: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to which app?") },
        text = {
            Column {
                apps.forEach { (pkg, name) ->
                    Text(name, Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
