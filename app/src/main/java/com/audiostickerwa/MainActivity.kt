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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiostickerwa.audio.AudioMakerScreen
import com.audiostickerwa.audio.AudioMakerState
import com.audiostickerwa.audio.StickerEngine
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

/** Screen keys: "list", "pack:<id>", "audio". */
@Composable
private fun App(vm: PacksViewModel = viewModel()) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf("list") }
    // The WhatsApp engine lives as long as the app UI, so it stays connected between screens.
    val engine = remember { StickerEngine(context.applicationContext) }
    // Create/send belong to the same owner as the engine, so leaving the audio screen does not cancel a send.
    val appScope = rememberCoroutineScope()
    val maker = remember(engine) { AudioMakerState(context.applicationContext, engine, appScope) }
    DisposableEffect(engine) { onDispose { engine.close() } }
    LaunchedEffect(engine) { engine.start() }

    Box(Modifier.fillMaxSize()) {
        if (screen == "audio") {
            AudioMakerScreen(
                engine = engine,
                maker = maker,
                onBack = { screen = "list" },
            )
            BackHandler { screen = "list" }
        } else {
            PackScreens(vm, screen, onNavigate = { screen = it })
        }
    }
}

@Composable
private fun PackScreens(vm: PacksViewModel, screen: String, onNavigate: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.messageShown()
    }

    if (screen.startsWith("pack:")) {
        val id = screen.removePrefix("pack:")
        val pack = state.packs.firstOrNull { it.id == id }
        BackHandler { onNavigate("list") }
        if (pack == null) {
            // Pack was deleted (or not loaded yet).
            LaunchedEffect(state.packs) { if (state.packs.none { it.id == id }) onNavigate("list") }
            return
        }
        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(StickerPack.MAX_STICKERS),
        ) { uris -> vm.addImages(id, uris) }
        val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_CANCELED) {
                res.data?.getStringExtra("validation_error")?.let { vm.showMessage("WhatsApp error: $it") }
            }
            vm.refresh()
        }
        PackDetailContent(
            pack = pack,
            isAdded = state.added[id] == true,
            busy = state.busy,
            stickerFile = { vm.fileOf(id, it) },
            snackbar = snackbar,
            onBack = { onNavigate("list") },
            onAddImages = {
                val type = if (pack.animated) ActivityResultContracts.PickVisualMedia.ImageAndVideo
                else ActivityResultContracts.PickVisualMedia.ImageOnly
                picker.launch(PickVisualMediaRequest(type))
            },
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
            onDeletePack = { vm.deletePack(id); onNavigate("list") },
        )
    } else {
        PackListContent(
            packs = state.packs,
            added = state.added,
            trayFile = { vm.fileOf(it.id, it.trayFile) },
            snackbar = snackbar,
            onOpenPack = { onNavigate("pack:$it") },
            onCreatePack = { n, p, a -> vm.createPack(n, p, a) { id -> onNavigate("pack:$id") } },
            onOpenAudioStudio = { onNavigate("audio") },
        )
    }
}
