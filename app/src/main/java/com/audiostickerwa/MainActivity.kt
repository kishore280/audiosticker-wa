package com.audiostickerwa

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiostickerwa.audio.AudioStudioScreen
import com.audiostickerwa.sticker.StickerPack
import com.audiostickerwa.sticker.WhatsApp
import com.audiostickerwa.ui.PackDetailContent
import com.audiostickerwa.ui.PackListContent
import com.audiostickerwa.ui.PacksViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val green = Color(0xFF00A884)
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = green) else lightColorScheme(primary = green)) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

/** Screen keys: "list", "pack:<id>", "audio". A saveable string keeps it simple and survives rotation. */
@Composable
private fun App(vm: PacksViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("list") }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.messageShown()
    }

    when {
        screen == "audio" -> AudioStudioScreen(onExit = { screen = "list" }, modifier = Modifier.safeDrawingPadding())

        screen.startsWith("pack:") -> {
            val id = screen.removePrefix("pack:")
            val pack = state.packs.firstOrNull { it.id == id }
            BackHandler { screen = "list" }
            if (pack == null) {
                // Pack was deleted (or not loaded yet).
                LaunchedEffect(state.packs) { if (state.packs.none { it.id == id }) screen = "list" }
                return
            }
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia(StickerPack.MAX_STICKERS),
            ) { uris -> vm.addImages(id, uris) }
            val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                if (res.resultCode == Activity.RESULT_CANCELED) {
                    val err = res.data?.getStringExtra("validation_error")
                    if (err != null) vm.showMessage("WhatsApp error: $err")
                }
                vm.refresh()
            }
            PackDetailContent(
                pack = pack,
                isAdded = state.added[id] == true,
                busy = state.busy,
                stickerFile = { vm.fileOf(id, it) },
                snackbar = snackbar,
                onBack = { screen = "list" },
                onAddImages = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveSticker = { vm.removeSticker(id, it) },
                onAddToWhatsApp = {
                    if (!WhatsApp.isInstalled(context, WhatsApp.CONSUMER) && !WhatsApp.isInstalled(context, WhatsApp.BUSINESS)) {
                        vm.showMessage("WhatsApp is not installed.")
                    } else {
                        try {
                            addLauncher.launch(WhatsApp.addPackIntent(pack))
                        } catch (e: ActivityNotFoundException) {
                            vm.showMessage("WhatsApp cannot add sticker packs (update WhatsApp).")
                        }
                    }
                },
                onDeletePack = { vm.deletePack(id); screen = "list" },
            )
        }

        else -> PackListContent(
            packs = state.packs,
            added = state.added,
            trayFile = { vm.fileOf(it.id, it.trayFile) },
            snackbar = snackbar,
            onOpenPack = { screen = "pack:$it" },
            onCreatePack = { n, p -> vm.createPack(n, p) { id -> screen = "pack:$id" } },
            onOpenAudioStudio = { screen = "audio" },
        )
    }
}
