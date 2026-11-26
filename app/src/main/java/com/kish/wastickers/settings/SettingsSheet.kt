package com.kish.wastickers.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kish.wastickers.BuildConfig

/** Update check state for the settings sheet and the home banner. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/** Sticker names, WhatsApp link, updates and about. Stateless except for the text being typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    names: StickerNames,
    linkedPhone: String?,
    update: UpdateState,
    onSaveNames: (StickerNames) -> Unit,
    onUnlink: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pack by remember(names) { mutableStateOf(names.packName) }
    var publisher by remember(names) { mutableStateOf(names.publisher) }
    ModalBottomSheet(onDismissRequest = { onSaveNames(StickerNames(pack, publisher)); onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Text("Sticker names", style = MaterialTheme.typography.titleSmall)
            Text(
                "WhatsApp shows these in the sticker info of your audio stickers. New sticker packs use the publisher too.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(pack, { pack = it.take(128) }, label = { Text("Pack name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(publisher, { publisher = it.take(128) }, label = { Text("Publisher (your name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSaveNames(StickerNames(pack, publisher)) }) { Text("Save names") }

            HorizontalDivider()
            Text("WhatsApp", style = MaterialTheme.typography.titleSmall)
            if (linkedPhone != null) {
                Text("Linked to +$linkedPhone (for audio stickers).", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onUnlink) { Text("Unlink this app from WhatsApp", color = MaterialTheme.colorScheme.error) }
            } else {
                Text("Not linked. You link it when you send the first audio sticker. Sticker packs do not need a link.", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text("App version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            when (update) {
                UpdateState.Checking -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
                UpdateState.UpToDate -> Text("You have the latest version ✓", style = MaterialTheme.typography.bodySmall)
                is UpdateState.Failed -> Text("Cannot check: ${update.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is UpdateState.Available -> {
                    Text("Version ${update.update.version} is available.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onOpenUrl(update.update.downloadUrl) }) { Text("Download ${update.update.version}") }
                }
                UpdateState.Idle -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheckUpdate, enabled = update != UpdateState.Checking) { Text("Check for updates") }
                TextButton(onClick = { onOpenUrl("https://github.com/${BuildConfig.GITHUB_REPO}") }) { Text("GitHub") }
            }
            Text(
                "Open source. Not affiliated with WhatsApp or Meta. Audio stickers use a linked-device connection " +
                    "(whatsmeow), which is not an official API: send by hand only.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}
