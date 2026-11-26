package com.audiostickerwa.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shows an image file. It decodes on the IO dispatcher; it restarts when the file or its time stamp changes. */
@Composable
fun StickerImage(file: File, contentDescription: String?, modifier: Modifier = Modifier) {
    val stamp = file.lastModified()
    val bmp by produceState<ImageBitmap?>(null, file, stamp) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    val b = bmp
    if (b != null) Image(b, contentDescription, modifier) else Box(modifier)
}
