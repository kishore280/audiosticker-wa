package com.kish.wastickers.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kish.wastickers.sticker.StickerPack

/** What was shared to the app. */
enum class SharedKind { Video, Gif, Image, Audio, Unsupported }

/** Where shared files go: an audio sticker, an existing pack, or a new pack. */
sealed interface ShareTarget {
    data object AudioSticker : ShareTarget
    data class Pack(val id: String) : ShareTarget
    data object NewPack : ShareTarget
}

/**
 * "Use this for…" sheet after another app shares files to Sticker Studio.
 * Videos and GIFs fit animated packs; images fit every pack (in an animated pack an image becomes a still
 * animation). Several files can only go into a pack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetSheet(
    kind: SharedKind,
    count: Int,
    packs: List<StickerPack>,
    onPick: (ShareTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val fits = packs.filter { p -> kind == SharedKind.Image || p.animated }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val what = when (kind) {
                SharedKind.Video -> "this video"; SharedKind.Gif -> "this GIF"
                SharedKind.Image -> if (count > 1) "these $count images" else "this image"
                SharedKind.Audio -> "this sound"
                SharedKind.Unsupported -> "this file"
            }
            Text("Use $what for…", style = MaterialTheme.typography.titleLarge)
            if (count == 1) {
                Option(
                    "🔊  Audio sticker",
                    if (kind == SharedKind.Video) "A sticker that plays this video's sound (or another sound)." else "Add a sound to it.",
                    highlight = true,
                ) { onPick(ShareTarget.AudioSticker) }
            }
            Text("Sticker packs (no sound)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            if (fits.isEmpty()) {
                Text(
                    if (kind == SharedKind.Image) "You have no packs yet." else "You have no animated packs yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            fits.forEach { p ->
                val full = p.stickers.size >= StickerPack.MAX_STICKERS
                Option(
                    "Add to \"${p.name}\"",
                    "${p.stickers.size}/${StickerPack.MAX_STICKERS}" + (if (p.animated) " · animated" else "") + if (full) " · full" else "",
                    enabled = !full,
                ) { onPick(ShareTarget.Pack(p.id)) }
            }
            Option(
                "＋  New ${if (kind == SharedKind.Image) "" else "animated "}pack",
                "Make a pack and add ${if (count > 1) "them" else "it"}.",
            ) { onPick(ShareTarget.NewPack) }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun Option(title: String, subtitle: String, highlight: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = if (highlight) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
