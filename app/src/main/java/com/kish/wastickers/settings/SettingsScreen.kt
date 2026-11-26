package com.kish.wastickers.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kish.wastickers.BuildConfig

/** Update check state for the settings screen and the home banner. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/** Full-screen settings: sticker info, WhatsApp link, updates, about. Stateless except for the text being typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    names: StickerNames,
    linkedPhone: String?,
    update: UpdateState,
    onSaveNames: (StickerNames) -> Unit,
    onUnlink: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pack by remember(names) { mutableStateOf(names.packName) }
    var publisher by remember(names) { mutableStateOf(names.publisher) }
    val changed = pack.trim() != names.packName || publisher.trim() != names.publisher
    val save = { if (changed) onSaveNames(StickerNames(pack, publisher)) }
    // Back (gesture or arrow) keeps the typed names.
    BackHandler { save(); onBack() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { save(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Sticker info") {
                Text(
                    "WhatsApp shows these in the sticker info of your audio stickers. New sticker packs use the publisher too.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(pack, { pack = it.take(128) }, label = { Text("Pack name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(publisher, { publisher = it.take(128) }, label = { Text("Publisher (your name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = save, enabled = changed) { Text(if (changed) "Save" else "Saved ✓") }
            }

            Section("WhatsApp") {
                if (linkedPhone != null) {
                    Text("Linked to +$linkedPhone. Used only to send audio stickers.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onUnlink) { Text("Unlink this app", color = MaterialTheme.colorScheme.error) }
                } else {
                    Text(
                        "Not linked. You link it when you send the first audio sticker. Sticker packs do not need a link.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Section("Updates") {
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                when (update) {
                    UpdateState.Checking -> Text("Checking…", style = MaterialTheme.typography.bodySmall)
                    UpdateState.UpToDate -> Text("You have the latest version ✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    is UpdateState.Failed -> Text("Cannot check: ${update.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    is UpdateState.Available -> {
                        Text("Version ${update.update.version} is available.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onOpenUrl(update.update.downloadUrl) }) { Text("Download ${update.update.version}") }
                    }
                    UpdateState.Idle -> Unit
                }
                OutlinedButton(onClick = onCheckUpdate, enabled = update != UpdateState.Checking) { Text("Check for updates") }
            }

            Section("About") {
                Text("Sticker Studio", style = MaterialTheme.typography.titleMedium)
                Text("Made by Kish", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onOpenUrl("https://github.com/kishore280") }) { Text("github.com/kishore280") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onOpenUrl("https://github.com/${BuildConfig.GITHUB_REPO}") }) { Text("Source code") }
                    TextButton(onClick = { onOpenUrl("https://github.com/${BuildConfig.GITHUB_REPO}/issues") }) { Text("Report a problem") }
                }
                Text(
                    "Free and open source (MIT). Not affiliated with WhatsApp or Meta. Audio stickers use a linked-device " +
                        "connection (whatsmeow), which is not an official API: send by hand only, never in bulk.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}
