package com.audiostickerwa.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the MAIN-world scripts of the 4x4Tools-Wa-Audio-Stickers Chrome extension.
 *
 * The extension repo has NO license file, so this app does not bundle its code.
 * The app downloads the files from GitHub at a pinned commit on first use and keeps them in the cache.
 * Get permission from the author before you publish an app that uses them.
 */
object ExtensionScripts {
    const val REPO = "Insider4444/4x4Tools-Wa-Audio-Stickers"
    const val COMMIT = "c13314683f020015846854e73921421ffc4d7c02"

    // Same order as the "world": "MAIN" content_scripts entry in the extension manifest.json.
    private val FILES = listOf("src/logger.js", "src/convert.js", "src/wa.js", "src/ui.js", "src/main.js")

    suspend fun load(ctx: Context): String = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "ext/$COMMIT").apply { mkdirs() }
        val parts = FILES.map { path ->
            val f = File(dir, path.substringAfterLast('/'))
            if (!f.exists() || f.length() == 0L) {
                val text = download("https://raw.githubusercontent.com/$REPO/$COMMIT/$path")
                f.writeText(text)
            }
            "// ---- $path\ntry {\n${f.readText()}\n} catch (e) { console.error('[waas] $path', e); }\n"
        }
        PRELUDE + parts.joinToString("\n")
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /**
     * Replaces the extension's isolated-world bridge.js (chrome.storage is not available in a WebView):
     *  - keeps diagnostic logs in memory and answers "getLog"
     *  - sends <a download href="blob:..."> clicks to Android, so the .webp/.ogg export works
     */
    private val PRELUDE = """
        (function () {
          if (window.__waasShim) return; window.__waasShim = true;
          var LOG = [];
          window.addEventListener('message', function (ev) {
            if (ev.source !== window) return;
            var d = ev.data; if (!d || typeof d !== 'object') return;
            if (d.__waas === 'log' && d.entry) { LOG.push(d.entry); if (LOG.length > 2000) LOG.shift(); }
            else if (d.__waas === 'getLog') window.postMessage({ __waas: 'logResponse', reqId: d.reqId, log: LOG.slice() }, location.origin);
            else if (d.__waas === 'clearLog') LOG = [];
          });
          var origClick = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function () {
            var href = this.href || '';
            if (this.download && href.indexOf('blob:') === 0 && window.AndroidSaver) {
              var name = this.download;
              fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
                var fr = new FileReader();
                fr.onload = function () {
                  var s = String(fr.result); var i = s.indexOf(',');
                  window.AndroidSaver.save(name, b.type || 'application/octet-stream', s.slice(i + 1));
                };
                fr.readAsDataURL(b);
              });
              return;
            }
            return origClick.apply(this, arguments);
          };
        })();
    """.trimIndent() + "\n"
}
