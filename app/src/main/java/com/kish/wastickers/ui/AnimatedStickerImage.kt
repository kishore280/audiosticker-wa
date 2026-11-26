package com.kish.wastickers.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** Plays animated WebP on Android 9+ (ImageDecoder). Older Android shows the still first frame. */
@Composable
fun AnimatedStickerImage(file: File, contentDescription: String?, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < 28) {
        StickerImage(file, contentDescription, modifier)
        return
    }
    val stamp = file.lastModified()
    AndroidView(
        modifier = modifier,
        factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { v ->
            if (v.tag != "${file.path}@$stamp") {
                v.tag = "${file.path}@$stamp"
                v.contentDescription = contentDescription
                val d = try { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) } catch (e: Exception) { null }
                v.setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.start()
            }
        },
    )
}
