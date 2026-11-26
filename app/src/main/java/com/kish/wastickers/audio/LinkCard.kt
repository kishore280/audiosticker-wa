package com.kish.wastickers.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Local state of the link card. */
data class LinkUi(val busy: Boolean = false, val error: String? = null)

/** One-time linking of the app as a WhatsApp linked device (phone number -> code). */
@Composable
fun LinkCard(state: EngineState, link: LinkUi, onPair: (String) -> Unit, onCancel: () -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                EngineState.Starting, EngineState.Connecting -> {
                    Text("Connecting to WhatsApp…", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is EngineState.Pairing -> {
                    Text("Type this code in WhatsApp", style = MaterialTheme.typography.titleSmall)
                    Text(state.code, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
                    Text(
                        "1. Copy the code.  2. Open WhatsApp → Settings → Linked devices → Link a device → " +
                            "Link with phone number instead.  3. Type the code.  4. Come back here: this card " +
                            "changes to \"Linked\". The code expires after about 2 minutes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { clipboard.setText(AnnotatedString(state.code.replace("-", ""))) }) { Text("Copy code") }
                        OutlinedButton(onClick = onCancel) { Text("New code") }
                    }
                }
                else -> {
                    Text("Link WhatsApp (one time)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "To send audio stickers, this app becomes a linked device of your WhatsApp, like WhatsApp Web. " +
                            "This is not an official WhatsApp API: use it by hand only, never for bulk messages. " +
                            "Test with a second number first if you can.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        phone, { phone = it.filter { c -> c.isDigit() || c == '+' } },
                        label = { Text("Your number with country code, e.g. +91 98765 43210") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    Button(onClick = { onPair(phone) }, enabled = !link.busy && phone.count { it.isDigit() } >= 8) {
                        Text(if (link.busy) "Getting code…" else "Get link code")
                    }
                    if (state is EngineState.Failed) Text(state.reason, color = MaterialTheme.colorScheme.error)
                }
            }
            link.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

