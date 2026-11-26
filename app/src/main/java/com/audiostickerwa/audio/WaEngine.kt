package com.audiostickerwa.audio

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.content.Intent
import androidx.webkit.WebViewCompat
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Connection state of the hidden WhatsApp Web session. */
sealed interface EngineState {
    data object Starting : EngineState
    /** Page is open but WhatsApp is not linked (or still loading chats). */
    data class NotReady(val detail: String) : EngineState
    data object Ready : EngineState
}

data class WaChat(val id: String, val title: String, val group: Boolean)

/**
 * Owns one WhatsApp Web WebView (normally hidden) with the audio-sticker scripts injected.
 * The composition that shows the WebView owns this object and calls [destroy].
 */
@SuppressLint("SetJavaScriptEnabled")
class WaEngine(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val nextId = AtomicInteger(1)
    private val localFiles = ConcurrentHashMap<String, Pair<Uri, String>>()
    private val appContext = context.applicationContext
    private val scripts: String = EngineScripts.wrap(EngineScripts.load(context))
    private var started = false

    private val _diag = MutableStateFlow("")
    /** One line for the user: load %, URL, last error. Shown on the WhatsApp Web page. */
    val diagnostics: StateFlow<String> = _diag.asStateFlow()
    private var loadPct = 0
    private var lastError = ""
    private fun updateDiag() {
        val url = webView.url ?: "-"
        _diag.value = "Load $loadPct% · $url" + if (lastError.isNotEmpty()) " · $lastError" else ""
    }
    private fun error(msg: String) { lastError = msg.take(160); main.post { updateDiag() } }

    private val _caps = MutableStateFlow<String?>(null)
    /** Null when the encoders are supported; else a short warning for the user. */
    val capsWarning: StateFlow<String?> = _caps.asStateFlow()

    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    /** Set by the UI: shows the system file chooser for <input type=file> (used only when the page is visible). */
    var fileChooser: ((WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>>) -> Boolean)? = null

    val webView: WebView = WebView(context).apply {
        CookieManager.getInstance().setAcceptCookie(true)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // WhatsApp Web refuses mobile browsers: present as desktop Chrome with the real WebView version
            // (same as the WhatsappWebToGo app). Do not fake a newer version: update Android System WebView instead.
            val version = WebViewCompat.getCurrentWebViewPackage(context)?.versionName
                ?: Regex("Chrome/([\\d.]+)").find(userAgentString)?.groupValues?.get(1) ?: "142.0.0.0"
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$version Safari/537.36"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // Debug builds: the page can be inspected from a PC with chrome://inspect.
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        addJavascriptInterface(Bridge(), "AndroidBridge")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) = inject()
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                if (req.isForMainFrame) error("Error ${err.errorCode}: ${err.description}")
            }
            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                if (req.isForMainFrame) error("HTTP ${res.statusCode}")
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                error("WebView crashed. Restart the app.")
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                lastError = ""; updateDiag()
                // A new page loses all running JS calls: fail them now, so no caller waits forever.
                pending.values.forEach { it.completeExceptionally(IllegalStateException("WhatsApp Web reloaded. Try again.")) }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "web.whatsapp.com") return false
                // WhatsApp sends "phone" browsers to www.whatsapp.com: stay on WhatsApp Web.
                if (u.host == "www.whatsapp.com" || u.host == "whatsapp.com") { view.loadUrl(WA_URL); return true }
                // Other links open in the user's browser.
                try { context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = serveLocal(request)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { loadPct = newProgress; updateDiag() }
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR &&
                    !m.message().contains("WebSocket") && !m.message().contains("ErrorUtils")) {
                    error("JS: ${m.message()}")
                }
                return false
            }
            override fun onShowFileChooser(v: WebView?, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean =
                fileChooser?.invoke(p, cb) ?: false
        }
    }

    /** Opens WhatsApp Web. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        webView.loadUrl(WA_URL)
    }

    fun reload() {
        _state.value = EngineState.Starting
        webView.loadUrl(WA_URL)
    }

    private fun inject() {
        if (webView.url?.startsWith(WA_URL) != true) return
        webView.evaluateJavascript(scripts, null)
        if (_caps.value == null) checkCaps()
    }

    /** The engine needs WebCodecs Opus and canvas WebP. Tell the user early if the WebView lacks them. */
    private fun checkCaps() {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<String>()
        pending[id] = d
        d.invokeOnCompletion {
            pending.remove(id)
            val o = try { JSONObject(d.getCompleted()).optJSONObject("v") } catch (e: Exception) { null } ?: return@invokeOnCompletion
            val problems = buildList {
                if (o.optBoolean("opus") != true) add("Opus audio encoding")
                if (o.optString("webpEncode") != "image/webp") add("WebP image encoding")
                if (o.optBoolean("imageDecoder") != true) add("GIF decoding")
            }
            _caps.value = if (problems.isEmpty()) "" else
                "This phone's WebView does not support: ${problems.joinToString()}. Update \"Android System WebView\" and \"Chrome\" in Google Play."
        }
        webView.evaluateJavascript(
            "(async function(){ try { const v = await __app.caps(); AndroidBridge.resolve($id, JSON.stringify({ok:true, v})); }" +
                " catch (e) { AndroidBridge.resolve($id, JSON.stringify({ok:false})); } })();", null,
        )
    }

    private val poller = object : Runnable {
        override fun run() {
            if (started) {
                webView.evaluateJavascript("window.__app ? JSON.stringify(__app.state()) : 'null'") { raw ->
                    val s = decodeJsString(raw)
                    _state.value = if (s == null || s == "null") EngineState.NotReady("Loading WhatsApp Web…")
                    else {
                        val o = JSONObject(s)
                        if (o.optBoolean("ready")) EngineState.Ready
                        else EngineState.NotReady(o.optString("detail").ifEmpty { "Not linked yet" })
                    }
                }
            }
            main.postDelayed(this, 2000)
        }
    }

    init {
        // WhatsApp Web has a service worker: its fetches must also reach serveLocal().
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest) = serveLocal(request)
            })
        } catch (_: Exception) {}
        main.post(poller)
    }

    // ------------------------------------------------------------------ calls into the page

    /**
     * Runs an async JS expression and returns its JSON result. Throws with the page's error text,
     * on timeout, or when the page reloads. Cancellation of the caller stops the wait (the JS cannot be stopped).
     */
    suspend fun call(expr: String, timeoutMs: Long = 180_000): Any? {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<String>()
        pending[id] = d
        val js = """
            (async function(){ try { const v = await ($expr);
              AndroidBridge.resolve($id, JSON.stringify({ok:true, v: v === undefined ? null : v})); }
            catch (e) { AndroidBridge.resolve($id, JSON.stringify({ok:false, e: String(e && e.message || e)})); } })();
        """.trimIndent()
        main.post { webView.evaluateJavascript(js, null) }
        try {
            val o = JSONObject(withTimeout(timeoutMs) { d.await() })
            if (!o.optBoolean("ok")) error(o.optString("e", "Unknown error"))
            return o.opt("v")
        } finally {
            pending.remove(id)
        }
    }

    /** Gives the page a same-origin URL for a local file (served by [serveLocal]). */
    fun shareFile(uri: Uri, mime: String): String {
        val token = UUID.randomUUID().toString()
        localFiles[token] = uri to mime
        return "$WA_URL$LOCAL_PREFIX$token"
    }

    suspend fun chats(): List<WaChat> {
        val arr = call("__app.chats()", timeoutMs = 15_000) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            WaChat(o.getString("id"), o.getString("title"), o.optBoolean("group"))
        }
    }

    fun clearProgress() { _progress.value = null }

    fun destroy() {
        main.removeCallbacks(poller)
        pending.values.forEach { it.completeExceptionally(IllegalStateException("closed")) }
        webView.destroy()
    }

    private fun serveLocal(req: WebResourceRequest): WebResourceResponse? {
        val path = req.url.path ?: return null
        if (req.url.host != "web.whatsapp.com" || !path.startsWith("/$LOCAL_PREFIX")) return null
        val (uri, mime) = localFiles[path.removePrefix("/$LOCAL_PREFIX")] ?: return WebResourceResponse("text/plain", null, 404, "Not Found", emptyMap(), null)
        val stream = appContext.contentResolver.openInputStream(uri)
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Cache-Control" to "no-store"), stream)
    }

    private inner class Bridge {
        @JavascriptInterface fun resolve(id: Int, json: String) { pending[id]?.complete(json) }
        @JavascriptInterface fun progress(text: String, fraction: Double) {
            _progress.value = if (fraction >= 0) "$text ${(fraction * 100).toInt()}%" else text
        }
    }

    companion object {
        const val WA_URL = "https://web.whatsapp.com/"
        private const val LOCAL_PREFIX = "__waas_local/"

        /** evaluateJavascript returns a JSON-encoded value; for strings this undoes the quoting. */
        fun decodeJsString(raw: String?): String? {
            if (raw == null || raw == "null") return null
            return try { JSONArray("[$raw]").getString(0) } catch (e: Exception) { raw }
        }
    }
}
