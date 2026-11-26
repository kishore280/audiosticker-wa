package com.audiostickerwa

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
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
import com.audiostickerwa.ui.PackContent
import com.audiostickerwa.ui.ClipEditorDialog
import com.audiostickerwa.ui.WhatsAppChoiceDialog
import com.audiostickerwa.ui.status
import com.audiostickerwa.ui.HomeContent
import com.audiostickerwa.audio.EngineState
import com.audiostickerwa.audio.SavedSound
import com.audiostickerwa.audio.SendSheet
import com.audiostickerwa.audio.SoundActions
import com.audiostickerwa.audio.SoundLibrary
import com.audiostickerwa.audio.SoundSheet
import com.audiostickerwa.ui.PacksViewModel

class MainActivity : ComponentActivity() {
    /** A file shared to the app from another app (ACTION_SEND), until the UI takes it. */
    private val shared = MutableStateFlow<Uri?>(null)

    private fun takeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri != null) shared.value = uri
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeShared(intent)
        enableEdgeToEdge()
        setContent {
            val green = Color(0xFF00A884)
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = green) else lightColorScheme(primary = green)) {
                Surface(Modifier.fillMaxSize()) {
                    val sharedUri by shared.collectAsStateWithLifecycle()
                    App(sharedUri = sharedUri, onSharedTaken = { shared.value = null })
                }
            }
        }
    }
}

/** Screen keys: "home", "pack:<id>", "audio". */
@Composable
private fun App(sharedUri: Uri?, onSharedTaken: () -> Unit, vm: PacksViewModel = viewModel()) {
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

    // A shared file opens the maker with it selected (a sound goes to the sound; then the user picks a picture).
    LaunchedEffect(sharedUri) {
        val uri = sharedUri ?: return@LaunchedEffect
        val mime = context.contentResolver.getType(uri) ?: ""
        maker.reset()
        if (mime.startsWith("audio/")) {
            maker.selectTab(com.audiostickerwa.audio.VisualKind.Image)
            maker.setAudio(uri)
        } else {
            maker.setVisual(uri)
        }
        screen = "audio"
        onSharedTaken()
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
        var clipUri by remember { mutableStateOf<Uri?>(null) }
        var chooseApp by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
        // Still pack: many images at once. Animated pack: one file; a video or GIF opens the clip editor.
        val pickImages = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(StickerPack.MAX_STICKERS),
        ) { uris -> vm.addImages(id, uris) }
        val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val mime = context.contentResolver.getType(uri) ?: ""
            if (mime.startsWith("video/") || mime == "image/gif") clipUri = uri else vm.addImages(id, listOf(uri))
        }
        val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            vm.onWhatsAppResult(
                id,
                ok = res.resultCode == Activity.RESULT_OK,
                alreadyAdded = res.data?.getBooleanExtra("already_added", false) == true,
                validationError = res.data?.getStringExtra("validation_error"),
            )
        }
        fun launchAdd(pkg: String) {
            try {
                addLauncher.launch(WhatsApp.addPackIntent(pack, pkg))
            } catch (e: ActivityNotFoundException) {
                vm.showMessage("This WhatsApp cannot add sticker packs. Update WhatsApp.")
            }
        }
        PackContent(
            pack = pack,
            status = pack.status(state.added[id] == true),
            busy = state.busy,
            fileOf = { vm.fileOf(id, it) },
            snackbar = snackbar,
            onBack = { onNavigate("home") },
            onAdd = {
                if (pack.animated) pickOne.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                else pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onSetEmojis = { s, e -> vm.setEmojis(id, s.file, e) },
            onRemoveSticker = { vm.removeSticker(id, it.file) },
            onAddToWhatsApp = {
                val apps = WhatsApp.installedApps(context)
                when (apps.size) {
                    0 -> vm.showMessage("WhatsApp is not installed.")
                    1 -> launchAdd(apps[0].first)
                    else -> chooseApp = apps
                }
            },
            onDeletePack = { vm.deletePack(id); onNavigate("home") },
        )
        clipUri?.let { uri ->
            ClipEditorDialog(
                uri = uri,
                onAdd = { start, dur, framing -> clipUri = null; vm.addClip(id, uri, start, dur, framing) },
                onDismiss = { clipUri = null },
            )
        }
        chooseApp?.let { apps ->
            WhatsAppChoiceDialog(apps, onPick = { pkg -> chooseApp = null; launchAdd(pkg) }, onDismiss = { chooseApp = null })
        }
    } else {
        HomeContent(
            sounds = sounds,
            packs = state.packs,
            added = state.added,
            fileOf = { packId, name -> vm.fileOf(packId, name) },
            linkedPhone = linkedPhone,
            snackbar = snackbar,
            onNewAudioSticker = onNewAudioSticker,
            onOpenSound = onOpenSound,
            onOpenPack = { onNavigate("pack:$it") },
            onCreatePack = { n, p, a -> vm.createPack(n, p, a) { id -> onNavigate("pack:$id") } },
        )
    }
}
