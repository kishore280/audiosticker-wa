package com.kish.wastickers.settings

import com.kish.wastickers.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release on GitHub. [downloadUrl] is the APK file, or the release page when it has no APK. */
data class AppUpdate(val version: String, val pageUrl: String, val downloadUrl: String, val notes: String)

/** Checks GitHub Releases (repo in BuildConfig.GITHUB_REPO) for a version newer than this app. */
object UpdateChecker {
    /** Returns the newer release, or null when this app is up to date. Throws on network errors. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "wastickers-android")
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        try {
            when (c.responseCode) {
                200 -> Unit
                404 -> return@withContext null // no release yet
                else -> error("GitHub answered ${c.responseCode}")
            }
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = o.optString("tag_name").removePrefix("v")
            if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null
            val assets = o.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
            val page = o.optString("html_url")
            AppUpdate(tag, page, apk ?: page, o.optString("body").take(600))
        } finally {
            c.disconnect()
        }
    }

    /** Semantic version compare: "1.2.0" > "1.1.9". Non-number parts count as 0. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
