package com.audiostickerwa.audio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** "Me" = the user's own chat (Message yourself). */
val SelfChat = WaChat("self", "Me (message yourself)", false)

/**
 * Bottom sheet to choose a chat: Me and recent chats at the top (one tap), then search over all chats.
 * It loads the chat list itself (keyed to the sheet's life).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SendSheet(engine: StickerEngine, onPick: (WaChat) -> Unit, onDismiss: () -> Unit) {
    val recent by engine.recentChats.collectAsStateWithLifecycle()
    var all by remember { mutableStateOf<List<WaChat>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(engine) {
        try { all = engine.chats() } catch (e: Exception) { loadError = e.message; all = emptyList() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Send to", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(SelfChat) + recent.filter { it.id != SelfChat.id }).forEach { c ->
                    AssistChip(onClick = { onPick(c) }, label = { Text(if (c.id == "self") "Me" else c.title, maxLines = 1) })
                }
            }
            OutlinedTextField(q, { q = it }, label = { Text("Search chats") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            when {
                all == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text("Loading chats…")
                }
                else -> {
                    loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val shown = all!!.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) }
                    if (shown.isEmpty()) Text("No chats found. Contacts appear some minutes after linking.", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.id }) { c ->
                            Text(
                                (if (c.group) "👥  " else "👤  ") + c.title,
                                Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
