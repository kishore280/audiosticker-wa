package com.audiostickerwa.audio

import android.app.Activity
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the engine's WebView. It stays in composition all the time (so the session stays alive);
 * when [visible] is false it is 1 dp in size and the user does not see it.
 */
@Composable
fun WaWebHost(engine: WaEngine, visible: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val pending = remember { arrayOfNulls<ValueCallback<Array<Uri>>>(1) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        pending[0]?.onReceiveValue(if (res.resultCode == Activity.RESULT_OK) uris else null)
        pending[0] = null
    }
    DisposableEffect(engine) {
        engine.fileChooser = { params, cb ->
            pending[0]?.onReceiveValue(null)
            pending[0] = cb
            try { chooser.launch(params.createIntent()); true } catch (e: Exception) { pending[0] = null; false }
        }
        onDispose { engine.fileChooser = null }
    }
    if (visible) BackHandler(onBack = onClose)

    val diag by engine.diagnostics.collectAsStateWithLifecycle()
    // Always full size (WhatsApp Web lays out badly at a tiny size). When hidden, it is drawn under the app screens.
    Box(modifier.fillMaxSize().zIndex(if (visible) 1f else -1f)) {
        AndroidView(factory = { engine.webView }, modifier = Modifier.fillMaxSize())
        if (!visible) {
            // Hidden: catch every touch that the app screens above do not use, so nothing reaches WhatsApp Web.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            })
        }
        if (visible) {
            Text(
                diag,
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0xCC000000)).padding(6.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 3,
            )
            TextButton(onClick = engine::reload, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) { Text("Reload") }
            Button(onClick = onClose, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("Done") }
        }
    }
}
