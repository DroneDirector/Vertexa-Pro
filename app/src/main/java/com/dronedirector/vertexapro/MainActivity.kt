package com.dronedirector.vertexapro

import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dronedirector.vertexapro.data.Formats
import com.dronedirector.vertexapro.data.ModelSource
import com.dronedirector.vertexapro.data.Prefs
import com.dronedirector.vertexapro.ui.FolderPicker
import com.dronedirector.vertexapro.ui.HomeScreen
import com.dronedirector.vertexapro.ui.SettingsScreen
import com.dronedirector.vertexapro.ui.SplashScreen
import com.dronedirector.vertexapro.ui.ThemeMode
import com.dronedirector.vertexapro.ui.UpdateOverlay
import com.dronedirector.vertexapro.ui.UpdatePhase
import com.dronedirector.vertexapro.ui.VertexaTheme
import com.dronedirector.vertexapro.ui.ViewerScreen
import com.dronedirector.vertexapro.ui.isOverlay
import com.dronedirector.vertexapro.ui.paletteFor
import com.dronedirector.vertexapro.update.Release
import com.dronedirector.vertexapro.update.UpdateManager
import com.dronedirector.vertexapro.viewer.SiblingResolver
import com.dronedirector.vertexapro.viewer.ViewerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = viewUri(intent)
        setContent { VertexaApp(this, incoming) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewUri(intent)?.let { incoming.value = it }
    }

    private fun viewUri(i: Intent?): Uri? = if (i?.action == Intent.ACTION_VIEW) i.data else null

    fun applySystemBars(light: Boolean) {
        val t = AColor.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (light) SystemBarStyle.light(t, t) else SystemBarStyle.dark(t),
            navigationBarStyle = if (light) SystemBarStyle.light(t, t) else SystemBarStyle.dark(t),
        )
    }
}

private sealed interface Screen {
    data object Splash : Screen
    data object Home : Screen
    data object Settings : Screen
    data class Viewer(val source: ModelSource, val id: Long = System.nanoTime()) : Screen
}

private data class Choice(val label: String, val size: Long, val open: () -> Unit)

@Composable
private fun VertexaApp(activity: MainActivity, incoming: MutableStateFlow<Uri?>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(prefs.theme) }
    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }
    var recents by remember { mutableStateOf(prefs.recents()) }
    var autoCheck by remember { mutableStateOf(prefs.autoCheckUpdates) }
    var updatePhase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Idle) }
    var availableRelease by remember { mutableStateOf<Release?>(null) }
    var choices by remember { mutableStateOf<List<Choice>?>(null) }
    val pendingUri by incoming.collectAsState()
    val installStatus by UpdateManager.installStatus.collectAsState()
    val palette = paletteFor(theme)

    LaunchedEffect(theme) { activity.applySystemBars(palette.isLight && screen != Screen.Splash) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun open(src: ModelSource) {
        prefs.addRecent(src)
        recents = prefs.recents()
        screen = Screen.Viewer(src)
    }

    fun persist(uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // ---------------------------------------------------------------- pickers
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { persist(it) }
        scope.launch {
            val named = withContext(Dispatchers.IO) {
                uris.map { u -> Triple(u, SiblingResolver.displayName(ctx, u) ?: "model", SiblingResolver.size(ctx, u)) }
            }
            val models = named.filter { Formats.isModel(it.second) }
            when {
                models.isEmpty() -> toast("No supported 3D model was selected.")
                models.size == 1 -> {
                    val m = models[0]
                    open(ModelSource(m.first, m.second, m.third, extras = uris.filter { it != m.first }))
                }
                else -> choices = models.map { m ->
                    Choice(m.second, m.third) {
                        open(ModelSource(m.first, m.second, m.third, extras = uris.filter { it != m.first }))
                    }
                }
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        persist(tree)
        scope.launch {
            val found = withContext(Dispatchers.IO) { scanTree(ctx, tree) }
            when {
                found.isEmpty() -> toast("No supported 3D models in that folder.")
                found.size == 1 -> {
                    val f = found[0]
                    open(ModelSource(f.uri, f.path.substringAfterLast('/'), f.size, tree = tree, relPath = f.path))
                }
                else -> choices = found.map { f ->
                    Choice(f.path, f.size) {
                        open(ModelSource(f.uri, f.path.substringAfterLast('/'), f.size, tree = tree, relPath = f.path))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- viewer controller
    val viewer = screen as? Screen.Viewer
    val controller = remember(viewer?.id) {
        viewer?.let { ViewerController(ctx, it.source, palette, prefs.viewMode, prefs.showGrid) }
    }
    DisposableEffect(controller) {
        controller?.start()
        onDispose { controller?.dispose() }
    }
    LaunchedEffect(theme, controller) { controller?.applyPalette(palette) }

    val linkFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            persist(tree)
            controller?.linkFolder(tree)
        }
    }

    // ---------------------------------------------------------------- updates
    fun checkUpdates(silent: Boolean) {
        if (!silent) updatePhase = UpdatePhase.Checking
        scope.launch {
            val r = runCatching { UpdateManager.check() }
            r.onSuccess { rel ->
                availableRelease = rel
                updatePhase = when {
                    rel != null -> UpdatePhase.Available(rel)
                    silent -> UpdatePhase.Idle
                    else -> UpdatePhase.UpToDate
                }
            }.onFailure { e ->
                if (!silent) updatePhase = UpdatePhase.Failed("Couldn't reach the update server (${e.message ?: "offline"}).")
            }
        }
    }

    fun startUpdate(rel: Release) {
        if (!UpdateManager.canInstall(ctx)) {
            updatePhase = UpdatePhase.NeedPermission(rel)
            return
        }
        UpdateManager.installStatus.value = null
        updatePhase = UpdatePhase.Downloading(rel, 0f, 0, 0)
        scope.launch {
            runCatching {
                val apk = UpdateManager.download(ctx, rel) { f, done, speed ->
                    scope.launch { updatePhase = UpdatePhase.Downloading(rel, f, done, speed) }
                }
                delay(250)
                updatePhase = UpdatePhase.Installing(rel)
                delay(1500) // let the warp burst play
                UpdateManager.install(ctx, apk)
            }.onFailure { e -> updatePhase = UpdatePhase.Failed(e.message ?: "Download failed.") }
        }
    }

    LaunchedEffect(installStatus) {
        when (val s = installStatus) {
            null, "ok" -> {}
            "aborted" -> availableRelease?.let { updatePhase = UpdatePhase.Available(it) }
            else -> updatePhase = UpdatePhase.Failed(s)
        }
    }

    // Return from the "install unknown apps" settings page
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val ph = updatePhase
                if (ph is UpdatePhase.NeedPermission && UpdateManager.canInstall(ctx)) startUpdate(ph.release)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // ---------------------------------------------------------------- incoming "open with"
    LaunchedEffect(pendingUri, screen == Screen.Splash) {
        val u = pendingUri ?: return@LaunchedEffect
        if (screen == Screen.Splash) return@LaunchedEffect
        incoming.value = null
        val (name, size) = withContext(Dispatchers.IO) {
            (SiblingResolver.displayName(ctx, u) ?: "model") to SiblingResolver.size(ctx, u)
        }
        persist(u)
        if (Formats.isModel(name)) open(ModelSource(u, name, size)) else toast("$name is not a supported 3D model.")
    }

    // ---------------------------------------------------------------- back
    BackHandler(enabled = screen != Screen.Home && screen != Screen.Splash || choices != null || updatePhase.isOverlay()) {
        when {
            choices != null -> choices = null
            updatePhase is UpdatePhase.Downloading || updatePhase is UpdatePhase.Installing -> {}
            updatePhase.isOverlay() -> updatePhase = UpdatePhase.Idle
            else -> screen = Screen.Home
        }
    }

    VertexaTheme(theme) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(350)) },
                contentKey = { s -> if (s is Screen.Viewer) "viewer${s.id}" else s.toString() },
                label = "screens",
            ) { s ->
                when (s) {
                    Screen.Splash -> SplashScreen {
                        screen = Screen.Home
                        activity.applySystemBars(palette.isLight)
                        if (autoCheck) checkUpdates(silent = true)
                    }
                    Screen.Home -> HomeScreen(
                        recents = recents,
                        updateAvailable = availableRelease?.version,
                        onOpenFiles = { pickFiles.launch(arrayOf("*/*")) },
                        onOpenFolder = { pickFolder.launch(null) },
                        onOpenRecent = { open(it.toSource()) },
                        onRemoveRecent = { prefs.removeRecent(it.uri); recents = prefs.recents() },
                        onSettings = { screen = Screen.Settings },
                        onUpdate = { availableRelease?.let { updatePhase = UpdatePhase.Available(it) } },
                    )
                    Screen.Settings -> SettingsScreen(
                        theme = theme,
                        autoCheck = autoCheck,
                        updatePhase = updatePhase,
                        onTheme = { theme = it; prefs.theme = it },
                        onAutoCheck = { autoCheck = it; prefs.autoCheckUpdates = it },
                        onCheckUpdates = { checkUpdates(silent = false) },
                        onBack = { screen = Screen.Home },
                    )
                    is Screen.Viewer -> {
                        if (controller != null && controller.source == s.source) {
                            ViewerScreen(
                                c = controller,
                                onBack = { screen = Screen.Home },
                                onLinkFolder = { linkFolder.launch(null) },
                                onCycleTheme = {
                                    val next = ThemeMode.entries[(theme.ordinal + 1) % ThemeMode.entries.size]
                                    theme = next; prefs.theme = next
                                },
                                onModeChanged = { prefs.viewMode = it },
                                onGridChanged = { prefs.showGrid = it },
                            )
                        }
                    }
                }
            }

            choices?.let { list ->
                FolderPicker(
                    models = list.map { it.label to it.size },
                    onPick = { label -> list.firstOrNull { it.label == label }?.open?.invoke(); choices = null },
                    onDismiss = { choices = null },
                )
            }

            AnimatedVisibility(updatePhase.isOverlay(), enter = fadeIn(tween(400)), exit = fadeOut(tween(400))) {
                UpdateOverlay(
                    phase = updatePhase,
                    onUpdate = {
                        val rel = when (val ph = updatePhase) {
                            is UpdatePhase.Available -> ph.release
                            is UpdatePhase.NeedPermission -> ph.release
                            else -> availableRelease
                        }
                        if (rel != null) startUpdate(rel) else checkUpdates(silent = false)
                    },
                    onLater = { updatePhase = UpdatePhase.Idle },
                    onGrantPermission = { UpdateManager.openInstallPermissionSettings(ctx) },
                )
            }
        }
    }
}

private data class FoundModel(val uri: Uri, val path: String, val size: Long)

private fun scanTree(ctx: android.content.Context, tree: Uri): List<FoundModel> {
    val out = mutableListOf<FoundModel>()
    val cr = ctx.contentResolver
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val proj = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    val queue = ArrayDeque<Triple<String, String, Int>>()
    queue.add(Triple(rootId, "", 0))
    var seen = 0
    while (queue.isNotEmpty() && seen < 30_000) {
        val (id, path, depth) = queue.removeFirst()
        runCatching {
            cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    seen++
                    val cid = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val rel = if (path.isEmpty()) name else "$path/$name"
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 5) queue.add(Triple(cid, rel, depth + 1))
                    } else if (Formats.isModel(name)) {
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        out += FoundModel(DocumentsContract.buildDocumentUriUsingTree(tree, cid), rel, size)
                    }
                }
            }
        }
    }
    return out.sortedBy { it.path.lowercase() }
}
