package com.audiostickerwa.audio

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val WA_URL = "https://web.whatsapp.com/"

/**
 * WhatsApp Web in a WebView, with the audio-sticker studio of the Chrome extension injected.
 * The extension sends real audio stickers (WebP + Ogg/Opus with stickerAudio) through WhatsApp Web.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AudioStudioScreen(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Loading studio scripts…") }
    var scripts by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingChooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val currentExit by rememberUpdatedState(onExit)

    LaunchedEffect(Unit) {
        status = try {
            scripts = ExtensionScripts.load(context)
            "Open WhatsApp Web and link your phone (Linked devices)."
        } catch (e: Exception) {
            "Cannot download the studio scripts: ${e.message}"
        }
    }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        pendingChooser?.onReceiveValue(if (res.resultCode == Activity.RESULT_OK) uris else null)
        pendingChooser = null
    }

    fun inject(view: WebView) {
        val js = scripts ?: return
        if (view.url?.startsWith(WA_URL) != true) return
        view.evaluateJavascript("(function(){ if (window.WAAS && window.WAAS._started) return; \n$js\n})();", null)
    }

    // Inject when the scripts arrive after the page has loaded.
    LaunchedEffect(scripts, webView) { webView?.let { inject(it) } }

    BackHandler {
        val v = webView
        if (v != null && v.canGoBack()) v.goBack() else currentExit()
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        allowFileAccess = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        // WhatsApp Web refuses mobile browsers: present as desktop Chrome, same engine version.
                        val chrome = Regex("Chrome/[\\d.]+").find(userAgentString)?.value ?: "Chrome/140.0.0.0"
                        userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
                    }
                    addJavascriptInterface(FileSaver(ctx.applicationContext), "AndroidSaver")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) = inject(view)
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            view: WebView?,
                            callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams,
                        ): Boolean {
                            pendingChooser?.onReceiveValue(null)
                            pendingChooser = callback
                            return try {
                                chooser.launch(params.createIntent()); true
                            } catch (e: Exception) {
                                pendingChooser = null; false
                            }
                        }
                    }
                    loadUrl(WA_URL)
                    webView = this
                }
            },
        )
        ExtendedFloatingActionButton(
            onClick = { webView?.evaluateJavascript("window.postMessage({__waas:'togglePanel'}, location.origin)", null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Text("Audio sticker") }
        if (scripts == null) {
            Text(status, Modifier.align(Alignment.TopCenter).padding(8.dp))
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
}
