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
import com.audiostickerwa.ui.HomeContent
import com.audiostickerwa.audio.EngineState
import com.audiostickerwa.audio.SavedSound
import com.audiostickerwa.audio.SendSheet
import com.audiostickerwa.audio.SoundActions
import com.audiostickerwa.audio.SoundLibrary
import com.audiostickerwa.audio.SoundSheet
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

/** Screen keys: "home", "pack:<id>", "audio". */
@Composable
private fun App(vm: PacksViewModel = viewModel()) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf("home") }
    val snackbar = remember { SnackbarHostState() }

    // App-level owners: the WhatsApp engine (stays connected between screens), the library of made
    // stickers, and the shared actions. Their work runs in this composition's scope, so changing screens
    // does not cancel a create or a send.
    val appScope = rememberCoroutineScope()
    val engine = remember { StickerEngine(context.applicationContext) }
    val library = remember { SoundLibrary(context.applicationContext) }
    val actions = remember(engine, library) { SoundActions(context.applicationContext, engine, library, appScope) }
    val maker = remember(library) { AudioMakerState(context.applicationContext, library, appScope) }
    DisposableEffect(engine) { onDispose { engine.close() } }
    LaunchedEffect(engine) { engine.start() }
    LaunchedEffect(library) { library.cleanUnfinished(); library.refresh() }

    LaunchedEffect(actions.message) {
        val m = actions.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        actions.messageShown()
    }

    val engineState by engine.state.collectAsStateWithLifecycle()
    val sounds by library.items.collectAsStateWithLifecycle()
    var openSound by remember { mutableStateOf<String?>(null) }
    var sendFor by remember { mutableStateOf<SavedSound?>(null) }

    when {
        screen == "audio" -> {
            AudioMakerScreen(engine, maker, actions, snackbar, onBack = { screen = "home" })
            BackHandler { screen = "home" }
        }
        else -> PackScreens(
            vm, screen, onNavigate = { screen = it }, snackbar = snackbar,
            sounds = sounds,
            linkedPhone = (engineState as? EngineState.Linked)?.phone,
            onNewAudioSticker = { maker.reset(); screen = "audio" },
            onOpenSound = { openSound = it.id },
        )
    }

    sounds.firstOrNull { it.id == openSound }?.let { item ->
        SoundSheet(
            item = item,
            sending = actions.sendingId == item.id,
            linked = engineState is EngineState.Linked,
            onSend = { sendFor = item },
            onExport = { actions.export(item) },
            onDelete = { openSound = null; actions.delete(item) },
            onDismiss = { openSound = null },
        )
    }
    sendFor?.let { item ->
        SendSheet(engine, onPick = { c -> sendFor = null; actions.send(item, c) }, onDismiss = { sendFor = null })
    }
}

@Composable
private fun PackScreens(
    vm: PacksViewModel,
    screen: String,
    onNavigate: (String) -> Unit,
    snackbar: SnackbarHostState,
    sounds: List<SavedSound>,
    linkedPhone: String?,
    onNewAudioSticker: () -> Unit,
    onOpenSound: (SavedSound) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.messageShown()
    }

    if (screen.startsWith("pack:")) {
        val id = screen.removePrefix("pack:")
        val pack = state.packs.firstOrNull { it.id == id }
        BackHandler { onNavigate("home") }
        if (pack == null) {
            // Pack was deleted (or not loaded yet).
            LaunchedEffect(state.packs) { if (state.packs.none { it.id == id }) onNavigate("home") }
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
            onBack = { onNavigate("home") },
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
            onDeletePack = { vm.deletePack(id); onNavigate("home") },
        )
    } else {
        HomeContent(
            sounds = sounds,
            packs = state.packs,
            added = state.added,
            trayFile = { vm.fileOf(it.id, it.trayFile) },
            linkedPhone = linkedPhone,
            snackbar = snackbar,
            onNewAudioSticker = onNewAudioSticker,
            onOpenSound = onOpenSound,
            onOpenPack = { onNavigate("pack:$it") },
            onCreatePack = { n, p, a -> vm.createPack(n, p, a) { id -> onNavigate("pack:$id") } },
        )
    }
}
