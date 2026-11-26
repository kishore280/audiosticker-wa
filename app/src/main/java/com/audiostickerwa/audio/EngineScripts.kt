package com.audiostickerwa.audio

import android.content.Context

/**
 * The sticker engine scripts, bundled in assets/engine (see SOURCE.md and LICENSE there).
 * Order is the same as the MAIN-world content_scripts of the original extension, then the app bridge.
 */
object EngineScripts {
    private val FILES = listOf("logger.js", "convert.js", "wa.js", "main.js", "app_api.js")

    fun load(ctx: Context): String = FILES.joinToString("\n") { name ->
        val code = ctx.assets.open("engine/$name").bufferedReader().use { it.readText() }
        "// ---- $name\ntry {\n$code\n} catch (e) { console.error('[engine] $name', e); }\n"
    }

    /** Runs the engine once per page; safe if both document-start and page-finished injection fire. */
    fun wrap(js: String) = "(function(){ if (window.__app) return;\n$js\n})();"
}
