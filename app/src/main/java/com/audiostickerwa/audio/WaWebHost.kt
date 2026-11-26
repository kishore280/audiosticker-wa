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
import androidx.compose.foundation.layout.size
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

    Box(if (visible) modifier.fillMaxSize() else modifier.size(1.dp)) {
        AndroidView(factory = { engine.webView }, modifier = Modifier.fillMaxSize())
        if (visible) {
            Button(onClick = onClose, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Text("Done") }
        }
    }
}
