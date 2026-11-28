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
import com.dronedirector.vertexapro.ui.BrowserScreen
import com.dronedirector.vertexapro.data.BrowseItem
import androidx.documentfile.provider.DocumentFile
import com.dronedirector.vertexapro.ui.HomeScreen
import com.dronedirector.vertexapro.ui.GeneratorScreen
import com.dronedirector.vertexapro.ui.StartScreen
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
    data object Start : Screen
    data object Generator : Screen
    data object Settings : Screen
    data class Browser(val title: String, val tree: Uri?, val id: Long = System.nanoTime()) : Screen
    data class Viewer(val source: ModelSource, val back: Screen = Home, val id: Long = System.nanoTime()) : Screen
}

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
    var folders by remember { mutableStateOf(prefs.folders()) }
    var browserItems by remember { mutableStateOf<List<BrowseItem>?>(null) }
    var sortKey by remember { mutableStateOf(prefs.sortKey) }
    var sortAsc by remember { mutableStateOf(prefs.sortAsc) }
    var browserGrid by remember { mutableStateOf(prefs.browserGrid) }
    val pendingUri by incoming.collectAsState()
    val installStatus by UpdateManager.installStatus.collectAsState()
    val palette = paletteFor(theme)

    LaunchedEffect(theme) { activity.applySystemBars(palette.isLight && screen != Screen.Splash) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun open(src: ModelSource) {
        prefs.addRecent(src)
        recents = prefs.recents()
        val cur = screen
        screen = Screen.Viewer(src, back = if (cur is Screen.Browser || cur is Screen.Generator) cur else Screen.Home)
    }

    fun browseFolder(tree: Uri, name: String) {
        browserItems = null
        screen = Screen.Browser(name, tree)
        scope.launch {
            val found = withContext(Dispatchers.IO) { scanTree(ctx, tree) }
            if ((screen as? Screen.Browser)?.tree == tree) browserItems = found
        }
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
                else -> {
                    browserItems = models.map { m ->
                        BrowseItem(m.first, m.second, m.second, m.third, SiblingResolver.modified(ctx, m.first),
                            extras = uris.filter { it != m.first })
                    }
                    screen = Screen.Browser("Selected models", null)
                }
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        persist(tree)
        val name = DocumentFile.fromTreeUri(ctx, tree)?.name ?: "Folder"
        prefs.addFolder(tree.toString(), name)
        folders = prefs.folders()
        browseFolder(tree, name)
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
    fun goBack() {
        val cur = screen
        screen = when (cur) {
            is Screen.Viewer -> cur.back
            Screen.Home, Screen.Generator -> Screen.Start
            else -> Screen.Home
        }
    }

    BackHandler(enabled = screen != Screen.Start && screen != Screen.Splash || updatePhase.isOverlay()) {
        when {
            updatePhase is UpdatePhase.Downloading || updatePhase is UpdatePhase.Installing -> {}
            updatePhase.isOverlay() -> updatePhase = UpdatePhase.Idle
            else -> goBack()
        }
    }

    VertexaTheme(theme) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(350)) },
                contentKey = { s -> when (s) { is Screen.Viewer -> "viewer${s.id}"; is Screen.Browser -> "browser${s.id}"; else -> s.toString() } },
                label = "screens",
            ) { s ->
                when (s) {
                    Screen.Splash -> SplashScreen {
                        screen = Screen.Start
                        activity.applySystemBars(palette.isLight)
                        if (autoCheck) checkUpdates(silent = true)
                    }
                    Screen.Start -> StartScreen(
                        onStudio = { screen = Screen.Home },
                        onGenerator = { screen = Screen.Generator },
                    )
                    Screen.Generator -> GeneratorScreen(
                        onBack = { screen = Screen.Start },
                        onOpen = { f -> open(ModelSource(android.net.Uri.fromFile(f), f.name, f.length())) },
                    )
                    Screen.Home -> HomeScreen(
                        recents = recents,
                        folders = folders,
                        onBrowseFolder = { f -> browseFolder(Uri.parse(f.uri), f.name) },
                        onRemoveFolder = { f -> prefs.removeFolder(f.uri); folders = prefs.folders() },
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
                    is Screen.Browser -> BrowserScreen(
                        title = s.title,
                        items = browserItems,
                        sortKey = sortKey,
                        sortAsc = sortAsc,
                        grid = browserGrid,
                        onSort = { k, asc -> sortKey = k; sortAsc = asc; prefs.sortKey = k; prefs.sortAsc = asc },
                        onGrid = { browserGrid = it; prefs.browserGrid = it },
                        onOpen = { open(it.toSource()) },
                        onRefresh = s.tree?.let { t -> { browseFolder(t, s.title) } },
                        onBack = { screen = Screen.Home },
                    )
                    is Screen.Viewer -> {
                        if (controller != null && controller.source == s.source) {
                            ViewerScreen(
                                c = controller,
                                onBack = { goBack() },
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

private fun scanTree(ctx: android.content.Context, tree: Uri): List<BrowseItem> {
    val out = mutableListOf<BrowseItem>()
    val cr = ctx.contentResolver
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val proj = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
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
                        val mod = if (c.isNull(4)) 0L else c.getLong(4)
                        out += BrowseItem(DocumentsContract.buildDocumentUriUsingTree(tree, cid), name, rel, size, mod, tree = tree)
                    }
                }
            }
        }
    }
    return out
}
