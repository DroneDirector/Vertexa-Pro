package com.dronedirector.vertexapro.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLUtils
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dronedirector.vertexapro.data.Formats
import com.dronedirector.vertexapro.data.ModelSource
import com.dronedirector.vertexapro.ui.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

enum class Phase { LOADING, READY, ERROR }

/** One line of the load report. level: 0 info, 1 warning, 2 error. */
data class ReportLine(val level: Int, val text: String)

data class ModelStats(
    val vertices: Long = 0,
    val triangles: Long = 0,
    val points: Long = 0,
    val chunks: Long = 0,
    val materials: Long = 0,
    val pending: Long = 0,
    val size: DoubleArray = DoubleArray(3),
)

object ViewModes {
    const val TEXTURED = 0
    const val SOLID = 1
    const val WIRE = 2
    const val SOLID_WIRE = 3
    const val TEX_WIRE = 4
    const val WIRE_CLEAN = 5
}

data class PartUi(val index: Int, val name: String, val triangles: Long, val points: Long)

/** Progress of a snapshot / video / export job shown in the viewer. */
data class JobState(val title: String, val progress: Float, val done: Boolean = false, val message: String? = null,
                    val result: Uri? = null, val mime: String? = null, val failed: Boolean = false)

class ViewerController(
    private val context: Context,
    val source: ModelSource,
    palette: Palette,
    initialMode: Int,
    initialGrid: Boolean,
) {
    val handle: Long = NativeBridge.nativeCreate()
    private val lock = Any()
    @Volatile private var destroyed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var textureJob: Job? = null
    var view: ModelSurfaceView? = null
    private var maxTex = 4096

    private var resolver = SiblingResolver(
        context, source.extras, source.tree,
        source.relPath?.substringBeforeLast('/', "")?.ifEmpty { null },
    )
    /** What is actually loaded: the picked file, or the model inside a picked archive. */
    private var loadSource = source
    var stage by mutableStateOf("Opening model")
        private set
    var stageSize by mutableLongStateOf(source.size)
        private set

    var phase by mutableStateOf(Phase.LOADING)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var stats by mutableStateOf(ModelStats())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var missing by mutableStateOf<List<String>>(emptyList())
        private set
    var texturesTotal by mutableIntStateOf(0)
        private set
    var texturesLoaded by mutableIntStateOf(0)
        private set
    var loadMillis by mutableLongStateOf(0L)
        private set
    var report by mutableStateOf<List<ReportLine>>(emptyList())
        private set
    private val appLines = mutableListOf<ReportLine>()
    private val texLines = mutableListOf<ReportLine>()
    @Volatile private var captureRequested = false
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0

    var mode by mutableIntStateOf(initialMode)
        private set
    var grid by mutableStateOf(initialGrid)
        private set
    var autoRotate by mutableStateOf(false)
        private set
    var upZ by mutableStateOf(Formats.defaultZUp(source.name))
        private set

    // parts
    var parts by mutableStateOf<List<PartUi>>(emptyList())
        private set
    var hiddenParts by mutableStateOf<Set<Int>>(emptySet())
        private set
    var selectedPart by mutableIntStateOf(-1)
        private set
    var splitLoose by mutableStateOf(false)
        private set
    val canSplit get() = Formats.ext(loadSource.name) == "obj"

    // look
    var lighting by mutableIntStateOf(0)
        private set
    var pointSize by mutableFloatStateOf(2f)
        private set
    var pointColorMode by mutableIntStateOf(0)
        private set

    // cut tool
    var canEdit by mutableStateOf(false)
        private set
    var cutActive by mutableStateOf(false)
        private set
    var cutMode by mutableIntStateOf(3)
    val cutPoints = androidx.compose.runtime.mutableStateListOf<FloatArray>()
    /** Points, Curve, Freehand, Rectangle or Ellipse (see CutGeometry). */
    var cutShape by mutableIntStateOf(CutGeometry.POINTS)
    var snapOn by mutableStateOf(true)
    var straightOn by mutableStateOf(false)
    var mirrorCut by mutableStateOf(false)
    var mirrorAxis by mutableIntStateOf(0)
    var mirrorAuto by mutableStateOf(true)
    /** Gap between a cut part and the model, in millimetres (0 = none). */
    var gapMm by mutableFloatStateOf(0f)
    /** How deep the cut may reach, as a share of the model's depth seen from the camera (front 0 → back 1). */
    var depthLo by mutableFloatStateOf(0f)
    var depthHi by mutableFloatStateOf(1f)
    /** Point of the outline that is selected / being dragged (-1 none). */
    var selectedCutPoint by mutableIntStateOf(-1)
    private var dragIndex = -1
    private var dragMoved = false
    private var dragBusy = false
    private var previewJob: Job? = null
    // flap template (trailing-edge strip)
    var flapDepth by mutableFloatStateOf(0.25f)
    var flapPreview by mutableStateOf(FloatArray(0))
        private set
    private var flapJob: Job? = null
    // camera
    var ortho by mutableStateOf(false)
        private set
    // hinges: part -> hinge line and deflection
    data class Hinge(val a: FloatArray, val b: FloatArray, val deg: Float)
    var hinges by mutableStateOf<Map<Int, Hinge>>(emptyMap())
        private set
    var hingePart by mutableIntStateOf(-1)
        private set
    val hingePoints = androidx.compose.runtime.mutableStateListOf<FloatArray>()
    /** Live stroke while drawing a freehand / rectangle / ellipse shape (screen pixels). */
    var drawPath by mutableStateOf(FloatArray(0))
        private set
    private var drawAnchorX = 0f
    private var drawAnchorY = 0f
    private val strokePts = ArrayList<Float>()
    var askConnect by mutableStateOf(false)
    var createPartOpen by mutableStateOf(false)
    var cutBusy by mutableStateOf(false)
        private set
    var cutHint by mutableStateOf<String?>(null)
    var lastCut by mutableStateOf<String?>(null)
    var hasUndo by mutableStateOf(false)
        private set
    var hasRedo by mutableStateOf(false)
        private set
    // outline history while drawing: each entry is the list of points at that step
    private val outlineUndo = ArrayList<List<FloatArray>>()
    private val outlineRedo = ArrayList<List<FloatArray>>()
    var canUndoPoint by mutableStateOf(false)
        private set
    var canRedoPoint by mutableStateOf(false)
        private set
    private var cutCount = 0

    // saved views
    private val viewStore = com.dronedirector.vertexapro.data.ViewStore(context)
    var views by mutableStateOf(viewStore.views(source.uri.toString()))
        private set

    // animation
    data class Clip(val name: String, val seconds: Double)
    var clips by mutableStateOf<List<Clip>>(emptyList())
        private set
    var clip by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var animTime by mutableStateOf(0.0)
        private set
    var animSpeed by mutableFloatStateOf(1f)
        private set
    private var animPoll: Job? = null

    // measure tool
    var measureActive by mutableStateOf(false)
        private set
    val measurePoints = androidx.compose.runtime.mutableStateListOf<FloatArray>()
    /** 0 m, 1 cm, 2 mm, 3 in, 4 ft — what one model unit means. */
    var unit by mutableIntStateOf(0)

    // snapshot / video / export
    var job by mutableStateOf<JobState?>(null)
    val isArchive get() = loadSource.uri != source.uri
    val loadedName get() = loadSource.name

    init {
        NativeBridge.nativeSetMode(handle, mode)
        NativeBridge.nativeSetGrid(handle, grid)
        NativeBridge.nativeSetUpAxisZ(handle, upZ)
        pointSize = (context.resources.displayMetrics.density * 1.6f).coerceIn(1.5f, 6f)
        NativeBridge.nativeSetPointScale(handle, pointSize)
        NativeBridge.nativeSetTheme(handle, palette.nativeTheme())
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        NativeBridge.nativeSetCpuBudget(handle, (mi.totalMem / 4).coerceIn(256L shl 20, 4096L shl 20))
    }

    private inline fun <T> safe(block: () -> T): T? = synchronized(lock) { if (destroyed) null else block() }

    // ---------------------------------------------------------------- loading
    fun start() {
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    private fun info(level: Int, text: String) { synchronized(appLines) { appLines += ReportLine(level, text) } }

    private fun rebuildReport() {
        val native = (safe { NativeBridge.nativeReport(handle) } ?: emptyArray()).map {
            val lvl = it.substringBefore('|').toIntOrNull() ?: 0
            ReportLine(lvl, it.substringAfter('|'))
        }
        report = synchronized(appLines) { appLines.toList() } + native + synchronized(texLines) { texLines.toList() }
    }

    val warnings: Int get() = report.count { it.level >= 1 }

    fun reportText(): String = buildString {
        appendLine("Vertexa Pro ${com.dronedirector.vertexapro.BuildConfig.VERSION_NAME} — load report")
        report.forEach { l -> appendLine((if (l.level == 2) "[ERROR] " else if (l.level == 1) "[WARN]  " else "        ") + l.text) }
    }

    private suspend fun load() {
        phase = Phase.LOADING
        error = null
        progress = 0f
        synchronized(appLines) { appLines.clear() }
        synchronized(texLines) { texLines.clear() }
        info(0, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}, " +
            "max texture ${maxTex}px")
        info(0, "Opened from: " + when {
            source.tree != null -> "folder (${source.relPath ?: source.name})"
            source.extras.isNotEmpty() -> "file picker with ${source.extras.size} extra file(s)"
            else -> "file picker (model only)"
        })
        val t0 = System.nanoTime()
        stageSize = source.size
        val isZip = withContext(Dispatchers.IO) { ModelPrep.isZip(context, source.uri) }
        if (isZip) {
            stage = "Unpacking archive"
            val prep = withContext(Dispatchers.IO) {
                runCatching { ModelPrep.prepare(context, source) { f -> progress = f } }
            }
            val p = prep.getOrElse { e ->
                error = e.message ?: "The archive could not be unpacked."
                info(2, "Unpacking failed: ${e.message}")
                rebuildReport()
                phase = Phase.ERROR
                return
            }
            p.notes.forEach { info(it.level, it.text) }
            loadSource = p.source
            resolver = SiblingResolver(context, loadSource.extras, null, null)
            if (Formats.defaultZUp(loadSource.name) != upZ) toggleUpAxis()
            progress = 0f
        }
        stage = "Opening model"
        stageSize = loadSource.size
        val needsSeek = Formats.ext(loadSource.name) !in Formats.native
        val fd = withContext(Dispatchers.IO) { SiblingResolver.openSeekableFd(context, loadSource.uri, needsSeek) }
        if (fd < 0) {
            error = "The file could not be opened. It may have been moved, deleted, or access has expired. Open it again from your files."
            info(2, "Android refused to open the file (permission expired, file moved, or cloud file unavailable).")
            rebuildReport()
            phase = Phase.ERROR
            return
        }
        val poll = scope.launch {
            while (isActive) {
                refreshStats()
                view?.requestRender()
                delay(80)
            }
        }
        val err = withContext(Dispatchers.IO) {
            NativeBridge.nativeLoad(handle, fd, loadSource.size, loadSource.name, resolver, splitLoose)
        }
        poll.cancel()
        refreshStats()
        progress = 1f
        view?.requestRender()
        if (err == "cancelled") return
        loadMillis = (System.nanoTime() - t0) / 1_000_000
        info(0, String.format(java.util.Locale.US, "Load time: %.1f s", loadMillis / 1000.0))
        rebuildReport()
        if (err.isNotEmpty()) {
            error = err
            phase = Phase.ERROR
            return
        }
        phase = Phase.READY
        for (attempt in 0 until 4) {  // the GL thread picks up animation data with the first frames
            delay(150L * (attempt + 1))
            clips = (safe { NativeBridge.nativeAnimClips(handle) } ?: emptyArray()).map {
                Clip(it.substringBeforeLast('|'), it.substringAfterLast('|').toDoubleOrNull() ?: 0.0)
            }
            if (clips.isNotEmpty()) break
            view?.requestRender()
        }
        if (clips.isNotEmpty()) playAnimation(true)
        canEdit = safe { NativeBridge.nativeCanEdit(handle) } ?: false
        viewStore.last(source.uri.toString())?.let { cam ->
            safe { NativeBridge.nativeSetCamera(handle, cam, true) }
            view?.requestRender()
        }
        if (!canEdit) info(1, "The model is too large to keep a second copy in memory, so the cut tool and saving edits are off for it.")
        // keep pumping frames until every chunk has reached the GPU
        scope.launch {
            while (isActive && (safe { NativeBridge.nativeStats(handle)[5] } ?: 0L) > 0L) {
                view?.requestRender(); delay(50)
            }
            refreshStats()
        }
        textureJob = scope.launch {
            loadTextures(null)
            rebuildReport()
            // grab a thumbnail of the finished view for the browser
            delay(900)
            captureRequested = true
            view?.requestRender()
        }
    }

    private fun refreshParts() {
        val names = safe { NativeBridge.nativeParts(handle) } ?: return
        val tris = safe { NativeBridge.nativePartTriangles(handle) } ?: return
        parts = names.indices.mapNotNull { i ->
            val t = tris.getOrElse(i) { 0L }
            if (t == 0L) null else PartUi(i, names[i], if (t > 0) t else 0, if (t < 0) -t else 0)
        }
    }

    private fun refreshStats() {
        refreshParts()
        val s = safe { NativeBridge.nativeStats(handle) } ?: return
        val b = safe { NativeBridge.nativeBounds(handle) } ?: DoubleArray(6)
        progress = safe { NativeBridge.nativeProgress(handle) } ?: progress
        stats = ModelStats(s[0], s[1], s[2], s[3], s[4], s[5], doubleArrayOf(b[0], b[1], b[2]))
    }

    // ---------------------------------------------------------------- textures
    private suspend fun loadTextures(only: Set<String>?) {
        val names = safe { NativeBridge.nativeMaterialTextures(handle) } ?: return
        val groups = names.withIndex()
            .filter { it.value.isNotEmpty() && (only == null || it.value in only) }
            .groupBy({ it.value }, { it.index })
        if (groups.isEmpty()) return
        if (only == null) {
            synchronized(texLines) { texLines += ReportLine(0, "Textures referenced: ${groups.size}") }
            texturesTotal = groups.size
            texturesLoaded = 0
        }
        val cap = when {
            groups.size > 48 -> 2048
            groups.size > 12 -> 4096
            else -> 8192
        }.coerceAtMost(maxTex)
        val miss = mutableListOf<String>()
        for ((name, mats) in groups) {
            var reason = ""
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                runCatching {
                    if (name.startsWith("*")) {
                        val idx = name.substring(1).toIntOrNull() ?: -1
                        val sz = safe { NativeBridge.nativeEmbeddedSize(handle, idx) } ?: intArrayOf(-1, -1)
                        val data = safe { NativeBridge.nativeEmbeddedData(handle, idx) }
                        when {
                            data == null -> null
                            sz[0] > 0 && sz[1] > 0 -> TextureDecoder.fromRgba(sz[0], sz[1], data, cap)
                            else -> TextureDecoder.decodeBytes(data, cap)
                        }
                    } else {
                        val u = resolver.findUri(name)
                        if (u == null) { reason = "not found"; null }
                        else TextureDecoder.decodeUri(context, u, name, cap).also { if (it == null) reason = "found, but the image format can't be read" }
                    }
                }.getOrElse { e -> reason = "error: ${e.message}"; null }
            }
            scope.coroutineContext.ensureActive()
            if (bmp == null) {
                miss += name
                synchronized(texLines) { texLines += ReportLine(1, "Texture \"$name\": ${reason.ifEmpty { "could not be decoded" }}") }
                continue
            }
            synchronized(texLines) { texLines += ReportLine(0, "Texture \"$name\": loaded ${bmp.width}×${bmp.height}") }
            upload(bmp, mats)
            texturesLoaded++
        }
        missing = if (only == null) miss else missing.filter { it in miss }
        rebuildReport()
        view?.requestRender()
    }

    private suspend fun upload(bmp: Bitmap, mats: List<Int>) {
        val v = view ?: return bmp.recycle()
        suspendCancellableCoroutine { cont ->
            v.queueEvent {
                synchronized(lock) {
                    if (!destroyed) {
                        val ids = IntArray(1)
                        GLES30.glGenTextures(1, ids, 0)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
                        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                        mats.forEach { m -> NativeBridge.nativeSetTexture(handle, m, ids[0]) }
                    }
                }
                bmp.recycle()
                if (cont.isActive) cont.resume(Unit)
            }
            v.requestRender()
        }
    }

    /** User linked a folder containing the missing textures. */
    fun linkFolder(tree: Uri) {
        resolver.addTree(tree)
        val want = missing.toSet()
        if (want.isEmpty()) return
        scope.launch { loadTextures(want) }
    }

    fun dismissMissing() { missing = emptyList() }

    // ---------------------------------------------------------------- GL callbacks (GL thread)
    fun onSurfaceCreated() {
        val lost = safe { NativeBridge.nativeSurfaceCreated(handle) } ?: return
        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
        if (v[0] > 0) maxTex = v[0]
        if (lost) {
            safe { NativeBridge.nativeClearModel(handle) }
            scope.launch { start() }
        }
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        surfaceW = w
        surfaceH = h
        safe { NativeBridge.nativeSurfaceChanged(handle, w, h) }
    }

    fun onDrawFrame(): Boolean {
        val more = safe { NativeBridge.nativeDraw(handle, System.nanoTime()) } ?: return false
        loupeReq?.let { r -> captureLoupe(r) }
        if (captureRequested && surfaceW > 0 && surfaceH > 0) {
            captureRequested = false
            val side = minOf(surfaceW, surfaceH)
            val x = (surfaceW - side) / 2
            val y = (surfaceH - side) / 2
            val buf = java.nio.ByteBuffer.allocateDirect(side * side * 4).order(java.nio.ByteOrder.nativeOrder())
            GLES30.glReadPixels(x, y, side, side, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val src = source
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { ThumbnailStore.save(context, src.uri, ThumbnailStore.fromPixels(buf, side, side)) }
            }
        }
        return more
    }

    // ---------------------------------------------------------------- input & settings
    fun touchDown() { safe { NativeBridge.nativeTouchDown(handle) } }
    fun orbit(dx: Float, dy: Float) { safe { NativeBridge.nativeOrbit(handle, dx, dy) } }
    fun pan(dx: Float, dy: Float) { safe { NativeBridge.nativePan(handle, dx, dy) } }
    fun zoom(f: Float) { safe { NativeBridge.nativeZoom(handle, f) } }
    fun fling(vx: Float, vy: Float) { safe { NativeBridge.nativeFling(handle, vx, vy) } }

    fun resetView() {
        safe { NativeBridge.nativeResetView(handle, true) }
        view?.requestRender()
    }

    fun updateMode(m: Int) {
        mode = m
        safe { NativeBridge.nativeSetMode(handle, m) }
        view?.requestRender()
    }

    fun toggleGrid() {
        grid = !grid
        safe { NativeBridge.nativeSetGrid(handle, grid) }
        view?.requestRender()
    }

    fun toggleAutoRotate() {
        autoRotate = !autoRotate
        safe { NativeBridge.nativeSetAutoRotate(handle, autoRotate) }
        view?.requestRender()
    }

    fun toggleUpAxis() {
        upZ = !upZ
        safe { NativeBridge.nativeSetUpAxisZ(handle, upZ) }
        view?.requestRender()
    }

    fun applyPalette(p: Palette) {
        safe { NativeBridge.nativeSetTheme(handle, p.nativeTheme()) }
        view?.requestRender()
    }

    // ---------------------------------------------------------------- smart cut tools
    var cutTool by mutableIntStateOf(TOOL_OUTLINE)
        private set
    /** Erase brush: spheres (x, y, z, radius) that the cut never takes. */
    val eraseSpots = androidx.compose.runtime.mutableStateListOf<FloatArray>()
    private val cutPrefs = context.getSharedPreferences("cut_tools", Context.MODE_PRIVATE)
    var eraseBrushDp by mutableFloatStateOf(cutPrefs.getFloat("brush", 28f))
        private set
    var loupeZoom by mutableFloatStateOf(cutPrefs.getFloat("zoom", 3f))
        private set
    var lineSnap by mutableStateOf(false)
    var linesOnly by mutableStateOf(false)
        private set
    var wandTolerance by mutableFloatStateOf(cutPrefs.getFloat("wand", 0.35f))
        private set
    /** AI points of the current selection: x, y (screen), label 1 include / 0 exclude. */
    val aiPoints = androidx.compose.runtime.mutableStateListOf<Triple<Float, Float, Int>>()
    var aiExclude by mutableStateOf(false)
    var aiBusy by mutableStateOf(false)
        private set
    var aiDownload by mutableStateOf<Float?>(null)
        private set
    var askAiDownload by mutableStateOf(false)
    var smartBusy by mutableStateOf(false)
        private set
    private var lastOutline: FloatArray? = null
    val hasLastOutline get() = lastOutline != null

    private class Analysis(val cam: List<Double>, val lighting: Int, val img: ViewImage, val edges: FloatArray)
    private var analysis: Analysis? = null
    private var samEmbedding: Pair<List<Double>, Sam.Embedding>? = null

    // magnifier while dragging / erasing
    private class LoupeReq(val x: Float, val y: Float, val side: Int)
    @Volatile private var loupeReq: LoupeReq? = null
    var loupeImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set
    var loupeAt by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
        private set
    /** Side of the magnifier window in pixels: about 3 cm on the screen. */
    val loupeSizePx: Int get() = (context.resources.displayMetrics.xdpi * 3f / 2.54f).toInt().coerceIn(160, 700)

    // brush selection: whole polygons or vertices, painted on and erased off
    /** 1 = polygon by polygon, 2 = vertex by vertex. */
    var brushKind by mutableIntStateOf(1)
    var brushAdd by mutableStateOf(true)
    var brushXray by mutableStateOf(false)
    var brushSelCount by mutableLongStateOf(0L)
        private set
    private var brushBusy = false

    fun chooseCutTool(t: Int) {
        cutTool = if (cutTool == t && t != TOOL_OUTLINE) TOOL_OUTLINE else t
        cutHint = when (cutTool) {
            TOOL_ERASE -> "Paint over areas the cut must leave alone. Two fingers move the view."
            TOOL_WAND -> "Tap inside a painted panel — the outline follows its lines."
            TOOL_AI -> "Tap the flap or panel you want. Tap again to add, switch to Remove to exclude."
            TOOL_BRUSH -> if (brushKind == 2) "Paint over vertices to select them — polygons whose corners are all selected are taken."
                else "Paint over polygons to select them. Switch to Erase to take them off again."
            else -> shapeHint(cutShape)
        }
        if (cutTool == TOOL_AI) aiPoints.clear()
        if (cutTool == TOOL_BRUSH && brushSelCount == 0L && cutPoints.size >= 3) {
            // the outline's orange area becomes a polygon selection that the brush can add to and erase from
            scope.launch {
                previewJob?.cancel()
                val flat = FloatArray(cutPoints.size * 3)
                cutPoints.forEachIndexed { i, pt -> System.arraycopy(pt, 0, flat, i * 3, 3) }
                val er = eraseArray()
                val lo = depthLo
                val hi = depthHi
                val n = withContext(Dispatchers.Default) {
                    safe { NativeBridge.nativePreviewCut(handle, flat, lo, hi, er, 0, false) }
                    safe { NativeBridge.nativeSelectionFromPreview(handle) }
                } ?: 0L
                if (n > 0) {
                    brushSelCount = n
                    pushOutlineHistory()
                    cutPoints.clear()
                    cutHint = "Outline turned into a selection of ${com.dronedirector.vertexapro.data.formatCount(n)} polygons — paint to add or erase."
                }
                view?.requestRender()
            }
        }
    }

    private fun brushPaint(x: Float, y: Float) {
        showLoupe(x, y)
        if (brushBusy) return
        brushBusy = true
        val r = eraseBrushDp * context.resources.displayMetrics.density
        val add = brushAdd
        val vertex = brushKind == 2
        val xray = brushXray
        scope.launch {
            val n = withContext(Dispatchers.Default) { safe { NativeBridge.nativeBrushSelect(handle, x, y, r, add, vertex, xray) } }
            brushBusy = false
            if (n != null) {
                brushSelCount = n
                cutHint = if (n > 0) "${com.dronedirector.vertexapro.data.formatCount(n)} polygons selected — tap Create part when it's right." else "Nothing selected yet."
            }
            view?.requestRender()
        }
    }

    /** Part being touched up with the brush after it was created (-1 none). */
    var editPart by mutableIntStateOf(-1)
        private set

    /** Opens the brush on a finished part: it shows in orange, erase what shouldn't belong to it, paint to add. */
    fun editPartWithBrush(part: Int) {
        if (!canEdit) return
        if (part in hiddenParts) setPartVisible(part, true)
        startCut()
        selectBrush(1)
        brushAdd = false
        scope.launch {
            val n = withContext(Dispatchers.Default) { safe { NativeBridge.nativeEditPartSelection(handle, part) } } ?: 0L
            editPart = part
            brushSelCount = n
            cutHint = "Editing \u201c${partName(part)}\u201d (orange). Erase what shouldn't belong to it, Select to add more, then tap \u2713 Apply."
            view?.requestRender()
        }
    }

    fun applyPartEdit() {
        val part = editPart
        if (part < 0) return
        cutBusy = true
        scope.launch {
            val r = withContext(Dispatchers.Default) { safe { NativeBridge.nativeApplyPartEdit(handle, part) } }
            cutBusy = false
            if (r == null || r[0] == 0L) { cutHint = "Couldn't update the part."; return@launch }
            brushSelCount = 0
            val name = partName(part)
            editPart = -1
            finishNewPart(part, "Updated \u201c$name\u201d · ${com.dronedirector.vertexapro.data.formatCount(r[1])} triangles taken off, " +
                "${com.dronedirector.vertexapro.data.formatCount(r[2])} added")
            cutCount--
        }
    }

    fun selectBrush(kind: Int) {
        brushKind = kind
        if (cutTool != TOOL_BRUSH) chooseCutTool(TOOL_BRUSH)
        else cutHint = if (kind == 2) "Paint over vertices to select them." else "Paint over polygons to select them."
    }

    fun clearBrushSelection() {
        safe { NativeBridge.nativeClearSelection(handle) }
        brushSelCount = 0
        view?.requestRender()
    }

    private fun shapeHint(s: Int) = com.dronedirector.vertexapro.ui.shapeHint(s)

    fun updateBrush(dp: Float) { eraseBrushDp = dp; cutPrefs.edit().putFloat("brush", dp).apply() }
    fun updateLoupeZoom(z: Float) { loupeZoom = z; cutPrefs.edit().putFloat("zoom", z).apply() }
    fun updateWandTolerance(t: Float) { wandTolerance = t; cutPrefs.edit().putFloat("wand", t).apply() }
    fun toggleLinesOnly() { linesOnly = !linesOnly; schedulePreview(); view?.requestRender() }

    private fun resetSmart() {
        cutTool = TOOL_OUTLINE
        editPart = -1
        if (brushSelCount > 0) safe { NativeBridge.nativeClearSelection(handle) }
        brushSelCount = 0
        eraseSpots.clear()
        aiPoints.clear()
        lineSnap = false
        linesOnly = false
        samEmbedding = null
        analysis = null
        hideLoupe()
    }

    private fun eraseArray(): FloatArray? {
        if (eraseSpots.isEmpty()) return null
        val out = FloatArray(eraseSpots.size * 4)
        eraseSpots.forEachIndexed { i, e -> System.arraycopy(e, 0, out, i * 4, 4) }
        return out
    }

    /** True while one finger paints with the erase brush. */
    fun isPaintMode() = cutActive && (cutTool == TOOL_ERASE || cutTool == TOOL_BRUSH) && !askConnect && !createPartOpen && !cutBusy

    private var eraseBusy = false
    private var eraseStroke = false

    fun erasePaint(x: Float, y: Float, tap: Boolean = false) {
        if (cutTool == TOOL_BRUSH) { brushPaint(x, y); return }
        showLoupe(x, y)
        if (eraseBusy) return
        val density = context.resources.displayMetrics.density
        // spots closer than a third of the brush are skipped so strokes stay light
        val pr = projectEraseSpots()
        for (i in 0 until pr.size / 3) if (!pr[i * 3].isNaN() && kotlin.math.hypot(pr[i * 3] - x, pr[i * 3 + 1] - y) < eraseBrushDp * density / 3) return
        eraseBusy = true
        scope.launch {
            val hit = withContext(Dispatchers.Default) { safe { NativeBridge.nativeRaycast(handle, x, y) } }
            val ppu = safe { NativeBridge.nativePixelsPerUnit(handle) } ?: 0.0
            eraseBusy = false
            if (hit == null || ppu <= 0) { if (tap) cutHint = "Paint on the model itself."; return@launch }
            if (!eraseStroke) { pushOutlineHistory(); eraseStroke = true }
            eraseSpots.add(floatArrayOf(hit[0], hit[1], hit[2], (eraseBrushDp * density / ppu).toFloat()))
            if (tap) eraseStroke = false
            schedulePreview()
            view?.requestRender()
        }
    }

    fun eraseEnd() {
        eraseStroke = false
        hideLoupe()
        schedulePreview()
    }

    fun clearErase() {
        if (eraseSpots.isEmpty()) return
        pushOutlineHistory()
        eraseSpots.clear()
        schedulePreview()
    }

    /** Erase spots on screen: x, y, radius in pixels (NaN when behind the camera). */
    fun projectEraseSpots(): FloatArray {
        if (eraseSpots.isEmpty()) return FloatArray(0)
        val flat = FloatArray(eraseSpots.size * 3)
        eraseSpots.forEachIndexed { i, e -> System.arraycopy(e, 0, flat, i * 3, 3) }
        val pr = safe { NativeBridge.nativeProject(handle, flat) } ?: return FloatArray(0)
        val ppu = (safe { NativeBridge.nativePixelsPerUnit(handle) } ?: 0.0).toFloat()
        val out = FloatArray(eraseSpots.size * 3)
        for (i in eraseSpots.indices) {
            out[i * 3] = pr.getOrElse(i * 2) { Float.NaN }
            out[i * 3 + 1] = pr.getOrElse(i * 2 + 1) { Float.NaN }
            out[i * 3 + 2] = eraseSpots[i][3] * ppu
        }
        return out
    }

    // ---- magnifier
    private fun showLoupe(x: Float, y: Float) {
        val side = (loupeSizePx / loupeZoom).toInt().coerceAtLeast(16)
        loupeReq = LoupeReq(x, y, side)
        loupeAt = androidx.compose.ui.geometry.Offset(x, y)
        view?.requestRender()
    }

    private fun hideLoupe() {
        loupeReq = null
        loupeAt = null
        loupeImage = null
    }

    /** GL thread: copies the pixels around the finger for the magnifier. */
    private fun captureLoupe(r: LoupeReq) {
        if (surfaceW <= 0 || surfaceH <= 0) return
        val s = r.side.coerceAtMost(minOf(surfaceW, surfaceH))
        val x0 = (r.x.toInt() - s / 2).coerceIn(0, surfaceW - s)
        val y0 = (surfaceH - r.y.toInt() - s / 2).coerceIn(0, surfaceH - s)
        val buf = java.nio.ByteBuffer.allocateDirect(s * s * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glReadPixels(x0, y0, s, s, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val px = IntArray(s * s)
        for (yy in 0 until s) {
            val row = (s - 1 - yy) * s * 4
            for (xx in 0 until s) {
                val o = row + xx * 4
                px[yy * s + xx] = (0xFF shl 24) or ((buf.get(o).toInt() and 255) shl 16) or
                    ((buf.get(o + 1).toInt() and 255) shl 8) or (buf.get(o + 2).toInt() and 255)
            }
        }
        val bmp = Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888).asImageBitmap()
        // screen position of the magnified area's top-left corner, for drawing the outline over it
        val left = x0.toFloat()
        val top = (surfaceH - y0 - s).toFloat()
        view?.post {
            if (loupeReq != null) { loupeImage = bmp; loupeOrigin = androidx.compose.ui.geometry.Offset(left, top); loupeSide = s }
        }
    }
    var loupeOrigin by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
        private set
    var loupeSide by mutableIntStateOf(1)
        private set

    // ---- analysis image (what the camera sees, textures on, nothing drawn over it)
    private suspend fun ensureAnalysis(lighting: Int): Analysis? {
        val cam = safe { NativeBridge.nativeGetCamera(handle) }?.toList() ?: return null
        analysis?.let { if (it.cam == cam && it.lighting == lighting) return it }
        val v = view ?: return null
        val w = surfaceW / 2
        val h = surfaceH / 2
        if (w < 8 || h < 8) return null
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        v.queueEvent { done.complete(safe { NativeBridge.nativeAnalysisImage(handle, w, h, lighting, buf) } == true) }
        if (!done.await()) return null
        return withContext(Dispatchers.Default) {
            val img = SmartTools.fromGl(buf, w, h, 1, screenScale = 2)
            Analysis(cam, lighting, img, SmartTools.edges(img))
        }.also { analysis = it }
    }

    private suspend fun lineSnapped(x: Float, y: Float): Pair<Float, Float>? {
        val a = ensureAnalysis(LIGHT_FLAT) ?: return null
        return SmartTools.nearestEdge(a.img, a.edges, x, y, 14 * context.resources.displayMetrics.density)
    }

    /** Raycasts a screen outline onto the model (points that miss land on the plane through its centre). */
    private fun outlineFromScreen(scr: FloatArray): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (i in 0 until scr.size / 2) {
            val p = safe { NativeBridge.nativeRaycast(handle, scr[i * 2], scr[i * 2 + 1]) }
                ?: safe { NativeBridge.nativeUnproject(handle, floatArrayOf(scr[i * 2], scr[i * 2 + 1])) }
            if (p != null && p.size >= 3) out += floatArrayOf(p[0], p[1], p[2])
        }
        return out
    }

    private fun useOutline(pts: List<FloatArray>, hint: String) {
        if (pts.size < 3) { cutHint = "Couldn't find a clear area there — try another spot or zoom in."; return }
        pushOutlineHistory()
        cutPoints.clear()
        cutPoints.addAll(pts)
        cutShape = CutGeometry.CURVE
        selectedCutPoint = -1
        cutHint = hint
        schedulePreview()
        view?.requestRender()
    }

    fun wandTap(x: Float, y: Float) {
        if (smartBusy) return
        smartBusy = true
        val density = context.resources.displayMetrics.density
        scope.launch {
            val a = ensureAnalysis(LIGHT_FLAT)
            val pts = if (a == null) null else withContext(Dispatchers.Default) {
                val mask = SmartTools.wand(a.img, a.edges, x, y, wandTolerance) ?: return@withContext null
                val scr = SmartTools.maskToOutline(mask, a.img.w, a.img.h, { ix, iy -> a.img.toScreen(ix) to a.img.toScreen(iy) }, 3f * density)
                    ?: return@withContext null
                outlineFromScreen(scr)
            }
            smartBusy = false
            if (pts == null) { cutHint = "Tap inside a panel on the model."; return@launch }
            useOutline(pts, "Outline follows the panel. Drag points to adjust, then close it.")
            cutTool = TOOL_OUTLINE
            askConnect = true
        }
    }

    /** Moves every outline point onto the nearest painted line or edge. */
    fun fitToEdges() {
        if (cutPoints.size < 3 || smartBusy) return
        smartBusy = true
        val density = context.resources.displayMetrics.density
        scope.launch {
            val a = ensureAnalysis(LIGHT_FLAT)
            val pr = projectCutPoints()
            val moved = if (a == null) null else withContext(Dispatchers.Default) {
                val out = ArrayList<FloatArray>()
                var n = 0
                for (i in cutPoints.indices) {
                    val sx = pr.getOrElse(i * 2) { Float.NaN }
                    val sy = pr.getOrElse(i * 2 + 1) { Float.NaN }
                    val e = if (sx.isNaN()) null else SmartTools.nearestEdge(a.img, a.edges, sx, sy, 22 * density, 0.22f)
                    val p = e?.let { safe { NativeBridge.nativeRaycast(handle, it.first, it.second) } }
                    if (p != null) { out += floatArrayOf(p[0], p[1], p[2]); n++ } else out += cutPoints[i].copyOf()
                }
                n to out
            }
            smartBusy = false
            if (moved == null || moved.first == 0) { cutHint = "No clear lines near the outline."; return@launch }
            pushOutlineHistory()
            cutPoints.clear()
            cutPoints.addAll(moved.second)
            cutHint = "Moved ${moved.first} point${if (moved.first == 1) "" else "s"} onto the nearest lines."
            schedulePreview()
        }
    }

    /** Copies the current (or last cut) outline to the other side of the model. */
    fun copyToOtherSide() {
        val src: FloatArray = if (cutPoints.size >= 3) {
            FloatArray(cutPoints.size * 3).also { f -> cutPoints.forEachIndexed { i, p -> System.arraycopy(p, 0, f, i * 3, 3) } }
        } else lastOutline ?: run { cutHint = "Draw or cut an outline first."; return }
        scope.launch {
            val ax = if (mirrorAuto) withContext(Dispatchers.Default) { safe { NativeBridge.nativeDetectMirrorAxis(handle) } } ?: mirrorAxis else mirrorAxis
            mirrorAxis = ax
            val m = safe { NativeBridge.nativeMirrorPoints(handle, src, ax) } ?: return@launch
            val pts = (0 until m.size / 3).map { i -> floatArrayOf(m[i * 3], m[i * 3 + 1], m[i * 3 + 2]) }
            // a mirrored outline turns the other way round; keep it in the same direction
            useOutline(pts.reversed(), "Copied to the other side (${"XYZ"[ax]} mirror). Rotate to check it, then close it.")
            if (eraseSpots.isNotEmpty()) {
                val e = eraseArray()!!
                val c = FloatArray(eraseSpots.size * 3) { k -> e[(k / 3) * 4 + k % 3] }
                val mc = safe { NativeBridge.nativeMirrorPoints(handle, c, ax) }
                if (mc != null) {
                    val r = eraseSpots.map { it[3] }
                    eraseSpots.clear()
                    r.forEachIndexed { i, rad -> eraseSpots.add(floatArrayOf(mc[i * 3], mc[i * 3 + 1], mc[i * 3 + 2], rad)) }
                }
            }
        }
    }

    // ---- AI tap-to-select (MobileSAM, on device)
    fun aiTap(x: Float, y: Float) {
        if (aiBusy) return
        if (!Sam.isDownloaded(context)) { askAiDownload = true; return }
        aiPoints.add(Triple(x, y, if (aiExclude) 0 else 1))
        runAi()
    }

    fun undoAiPoint() {
        if (aiPoints.isEmpty()) return
        aiPoints.removeAt(aiPoints.size - 1)
        if (aiPoints.any { it.third == 1 }) runAi()
    }

    fun downloadAi() {
        askAiDownload = false
        aiDownload = 0f
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { Sam.download(context) { f -> scope.launch { aiDownload = f } } }.exceptionOrNull()
            }
            aiDownload = null
            cutHint = if (err != null) "AI download failed: ${err.message ?: "no connection"}" else "AI ready — tap the part you want."
        }
    }

    private fun runAi() {
        val pts = aiPoints.toList()
        if (pts.none { it.third == 1 }) { cutHint = "Tap on the part first (Add)."; return }
        aiBusy = true
        cutHint = "Finding the part…"
        val density = context.resources.displayMetrics.density
        scope.launch {
            val a = ensureAnalysis(LIGHT_STUDIO)
            val result = if (a == null) null else withContext(Dispatchers.Default) {
                runCatching {
                    val sam = Sam.get(context)
                    val emb = samEmbedding?.takeIf { it.first == a.cam }?.second
                        ?: sam.encode(a.img.px, a.img.w, a.img.h).also { samEmbedding = a.cam to it }
                    val imgPts = pts.map { (sx, sy, l) -> Triple(sx / a.img.step, sy / a.img.step, l) }
                    val mask = sam.decode(emb, imgPts)
                    val first = pts.first { it.third == 1 }
                    val seedX = (first.first / a.img.step * emb.scale).toInt()
                    val seedY = (first.second / a.img.step * emb.scale).toInt()
                    var region = SmartTools.component(mask, emb.w, emb.h, seedX, seedY)
                    if (region.none { it }) region = mask
                    val k = a.img.step / emb.scale
                    val scr = SmartTools.maskToOutline(region, emb.w, emb.h, { mx, my -> mx * k to my * k }, 3f * density)
                        ?: return@runCatching null
                    outlineFromScreen(scr)
                }
            }
            aiBusy = false
            val pts3 = result?.getOrNull()
            when {
                result == null -> cutHint = "Couldn't read the view — try again."
                result.isFailure -> cutHint = "AI error: ${result.exceptionOrNull()?.message ?: "unknown"}"
                pts3 == null -> cutHint = "Nothing found there — tap right on the part."
                else -> useOutline(pts3, "AI outline ready. Tap more to refine, or drag points. Close it when it fits.")
            }
        }
    }

    // ---------------------------------------------------------------- cut tool
    fun onTap(x: Float, y: Float) {
        when {
            hingePart >= 0 -> addHingePoint(x, y)
            cutActive && cutTool == TOOL_ERASE -> { erasePaint(x, y, true); eraseEnd() }
            cutActive && cutTool == TOOL_WAND -> wandTap(x, y)
            cutActive && cutTool == TOOL_AI -> aiTap(x, y)
            cutActive && cutShape == CutGeometry.FLAP -> addFlapPoint(x, y)
            cutActive -> addCutPoint(x, y)
            measureActive -> addMeasurePoint(x, y)
            else -> pick(x, y)
        }
    }

    // ---------------------------------------------------------------- saved views
    fun saveView(name: String? = null) {
        val cam = safe { NativeBridge.nativeGetCamera(handle) } ?: return
        val n = name?.takeIf { it.isNotBlank() } ?: "View ${views.size + 1}"
        views = views + com.dronedirector.vertexapro.data.SavedView(n, cam)
        viewStore.saveViews(source.uri.toString(), views)
    }

    fun goToView(i: Int) {
        val v = views.getOrNull(i) ?: return
        safe { NativeBridge.nativeSetCamera(handle, v.cam, true) }
        view?.requestRender()
    }

    fun deleteView(i: Int) {
        views = views.filterIndexed { k, _ -> k != i }
        viewStore.saveViews(source.uri.toString(), views)
    }

    private fun rememberLastView() {
        if (phase != Phase.READY) return
        safe { NativeBridge.nativeGetCamera(handle) }?.let { viewStore.saveLast(source.uri.toString(), it) }
    }

    // ---------------------------------------------------------------- animation
    fun playAnimation(on: Boolean) {
        playing = on
        safe { NativeBridge.nativeAnimPlay(handle, clip, on) }
        view?.requestRender()
        animPoll?.cancel()
        if (on) animPoll = scope.launch {
            while (isActive) {
                animTime = safe { NativeBridge.nativeAnimTime(handle) } ?: 0.0
                delay(50)
            }
        }
    }

    fun selectClip(i: Int) {
        clip = i
        seek(0.0)
        safe { NativeBridge.nativeAnimPlay(handle, i, playing) }
    }

    fun seek(seconds: Double) {
        animTime = seconds
        safe { NativeBridge.nativeAnimSeek(handle, seconds) }
        view?.requestRender()
    }

    fun updateAnimSpeed(s: Float) {
        animSpeed = s
        safe { NativeBridge.nativeAnimSpeed(handle, s.toDouble()) }
    }

    // ---------------------------------------------------------------- measure tool
    fun toggleMeasure() {
        if (measureActive) {
            measureActive = false
            measurePoints.clear()
        } else {
            if (cutActive) cancelCut()
            if (!canEdit) { cutHint = "This model is too large to measure on this device."; return }
            measureActive = true
            measurePoints.clear()
            selectPart(-1)
        }
        view?.requestRender()
    }

    private fun addMeasurePoint(x: Float, y: Float) {
        scope.launch {
            val hit = withContext(Dispatchers.Default) { safe { NativeBridge.nativeRaycast(handle, x, y) } } ?: return@launch
            if (measurePoints.size >= 2) measurePoints.clear()
            measurePoints.add(hit)
        }
    }

    fun projectMeasurePoints(): FloatArray {
        if (measurePoints.isEmpty()) return FloatArray(0)
        val flat = FloatArray(measurePoints.size * 3)
        measurePoints.forEachIndexed { i, p -> System.arraycopy(p, 0, flat, i * 3, 3) }
        return safe { NativeBridge.nativeProject(handle, flat) } ?: FloatArray(0)
    }

    /** Distance between the two measure points and per-axis differences, in model units. */
    fun measureDelta(): DoubleArray? {
        if (measurePoints.size < 2) return null
        val a = measurePoints[0]
        val b = measurePoints[1]
        val dx = (b[0] - a[0]).toDouble()
        val dy = (b[1] - a[1]).toDouble()
        val dz = (b[2] - a[2]).toDouble()
        return doubleArrayOf(kotlin.math.sqrt(dx * dx + dy * dy + dz * dz), kotlin.math.abs(dx), kotlin.math.abs(dy), kotlin.math.abs(dz))
    }

    fun formatLength(units: Double): String {
        val meters = units * when (unit) { 0 -> 1.0; 1 -> 0.01; 2 -> 0.001; 3 -> 0.0254; else -> 0.3048 }
        return when {
            meters >= 1000 -> String.format(java.util.Locale.US, "%.2f km", meters / 1000)
            meters >= 1 -> String.format(java.util.Locale.US, "%.3f m", meters)
            meters >= 0.01 -> String.format(java.util.Locale.US, "%.2f cm", meters * 100)
            else -> String.format(java.util.Locale.US, "%.2f mm", meters * 1000)
        } + if (unit >= 3) String.format(java.util.Locale.US, " (%.2f in)", meters / 0.0254) else ""
    }

    fun startCut() {
        if (!canEdit) { cutHint = "This model is too large to edit on this device."; return }
        if (measureActive) toggleMeasure()
        cutActive = true
        cutPoints.clear()
        clearOutlineHistory()
        askConnect = false
        createPartOpen = false
        lastCut = null
        selectPart(-1)
        cutHint = com.dronedirector.vertexapro.ui.shapeHint(cutShape)
        if (mirrorAuto) scope.launch {
            val ax = withContext(Dispatchers.Default) { safe { NativeBridge.nativeDetectMirrorAxis(handle) } }
            if (ax != null) mirrorAxis = ax
        }
        safe { NativeBridge.nativeSetAutoRotate(handle, false) }
        autoRotate = false
    }

    fun cancelCut() {
        clearPreview()
        resetSmart()
        selectedCutPoint = -1
        depthLo = 0f
        depthHi = 1f
        cutActive = false
        flapPreview = FloatArray(0)
        cutPoints.clear()
        clearOutlineHistory()
        askConnect = false
        createPartOpen = false
        cutHint = null
        view?.requestRender()
    }

    private fun syncHistory() {
        canUndoPoint = outlineUndo.isNotEmpty()
        canRedoPoint = outlineRedo.isNotEmpty()
    }

    /** Remembers the outline before a change so it can be undone. */
    private fun pushOutlineHistory() {
        outlineUndo.add(snapshotOutline())
        if (outlineUndo.size > 200) outlineUndo.removeAt(0)
        outlineRedo.clear()
        syncHistory()
    }

    /** Outline points (3 floats) and erase spots (4 floats) in one history entry. */
    private fun snapshotOutline(): List<FloatArray> = cutPoints.map { it.copyOf() } + eraseSpots.map { it.copyOf() }

    private fun restoreOutline(e: List<FloatArray>) {
        cutPoints.clear()
        cutPoints.addAll(e.filter { it.size == 3 })
        eraseSpots.clear()
        eraseSpots.addAll(e.filter { it.size == 4 })
    }

    private fun clearOutlineHistory() {
        outlineUndo.clear()
        outlineRedo.clear()
        syncHistory()
    }

    fun undoCutPoint() {
        if (outlineUndo.isEmpty()) return
        outlineRedo.add(snapshotOutline())
        restoreOutline(outlineUndo.removeAt(outlineUndo.size - 1))
        askConnect = false
        selectedCutPoint = -1
        syncHistory()
        schedulePreview()
    }

    fun redoCutPoint() {
        if (outlineRedo.isEmpty()) return
        outlineUndo.add(snapshotOutline())
        restoreOutline(outlineRedo.removeAt(outlineRedo.size - 1))
        askConnect = false
        selectedCutPoint = -1
        syncHistory()
        schedulePreview()
    }

    /** Screen positions of the cut points for the overlay (NaN when behind the camera). */
    fun projectCutPoints(): FloatArray {
        if (cutPoints.isEmpty()) return FloatArray(0)
        val flat = FloatArray(cutPoints.size * 3)
        cutPoints.forEachIndexed { i, p -> System.arraycopy(p, 0, flat, i * 3, 3) }
        return safe { NativeBridge.nativeProject(handle, flat) } ?: FloatArray(0)
    }

    private fun addCutPoint(x: Float, y: Float) {
        if (askConnect || createPartOpen || cutBusy) return
        val density = context.resources.displayMetrics.density
        if (cutPoints.size >= 3) {
            val pr = projectCutPoints()
            if (pr.size >= 2 && !pr[0].isNaN() && kotlin.math.hypot(pr[0] - x, pr[1] - y) < 34 * density) {
                askConnect = true
                return
            }
            if (insertOnSegment(x, y)) return
        }
        selectedCutPoint = -1
        var tx = x
        var ty = y
        if (straightOn && cutPoints.isNotEmpty()) {
            val pr = projectCutPoints()
            val li = cutPoints.size - 1
            if (pr.size >= li * 2 + 2 && !pr[li * 2].isNaN()) {
                val (sx, sy) = CutGeometry.straighten(pr[li * 2], pr[li * 2 + 1], x, y)
                tx = sx; ty = sy
            }
        }
        val snapPx = if (snapOn) 16 * density else 0f
        scope.launch {
            if (lineSnap) lineSnapped(tx, ty)?.let { (lx, ly) -> tx = lx; ty = ly }
            val hit = withContext(Dispatchers.Default) {
                safe { NativeBridge.nativeSnap(handle, tx, ty, snapPx, snapOn, snapOn) }
            }
            if (hit == null) {
                cutHint = "That tap missed the model — tap directly on its surface."
            } else {
                pushOutlineHistory()
                cutPoints.add(hit)
                schedulePreview()
                cutHint = when {
                    cutPoints.size < 3 -> "Keep tapping around the area (${cutPoints.size} point${if (cutPoints.size > 1) "s" else ""})."
                    else -> "Tap near the first point to close the outline."
                }
            }
            view?.requestRender()
        }
    }

    // ---- editing the outline: select, drag, insert, delete
    /** Index of the outline point under the finger, or -1. */
    fun cutHandleAt(x: Float, y: Float): Int {
        if (!cutActive || cutTool != TOOL_OUTLINE || linesOnly || cutPoints.isEmpty() || askConnect || createPartOpen || cutBusy) return -1
        val pr = projectCutPoints()
        val r = 24 * context.resources.displayMetrics.density
        var best = -1
        var bestD = r
        for (i in 0 until pr.size / 2) {
            if (pr[i * 2].isNaN()) continue
            val d = kotlin.math.hypot(pr[i * 2] - x, pr[i * 2 + 1] - y)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun beginDragPoint(i: Int) {
        if (lineSnap && analysis == null) scope.launch { ensureAnalysis(LIGHT_FLAT) }
        dragIndex = i
        dragMoved = false
        selectedCutPoint = i
        pushOutlineHistory()
    }

    fun dragPoint(x: Float, y: Float) {
        val i = dragIndex
        if (i < 0 || i >= cutPoints.size || dragBusy) return
        dragMoved = true
        dragBusy = true
        val density = context.resources.displayMetrics.density
        showLoupe(x, y)
        scope.launch {
            var tx = x
            var ty = y
            if (lineSnap) analysis?.let { a -> SmartTools.nearestEdge(a.img, a.edges, x, y, 14 * density)?.let { tx = it.first; ty = it.second } }
            val hit = withContext(Dispatchers.Default) {
                safe { NativeBridge.nativeSnap(handle, tx, ty, if (snapOn) 12 * density else 0f, snapOn, snapOn) }
                    ?: safe { NativeBridge.nativeUnproject(handle, floatArrayOf(tx, ty)) }
            }
            if (hit != null && i < cutPoints.size) cutPoints[i] = hit
            dragBusy = false
            schedulePreview()
        }
    }

    fun endDragPoint() {
        hideLoupe()
        if (!dragMoved) {
            // a tap on a point: undo the history entry made for the drag, keep it selected
            if (outlineUndo.isNotEmpty()) { outlineUndo.removeAt(outlineUndo.size - 1); syncHistory() }
        }
        dragIndex = -1
    }

    fun deleteSelectedPoint() {
        val i = selectedCutPoint
        if (i < 0 || i >= cutPoints.size) return
        pushOutlineHistory()
        cutPoints.removeAt(i)
        selectedCutPoint = -1
        schedulePreview()
    }

    /** Tap near a line of a closed / 3+ point outline: adds a point there instead of at the end. */
    private fun insertOnSegment(x: Float, y: Float): Boolean {
        if (cutPoints.size < 3) return false
        val pr = projectCutPoints()
        if (pr.any { it.isNaN() }) return false
        val r = 14 * context.resources.displayMetrics.density
        val n = pr.size / 2
        var best = -1
        var bestD = r
        for (i in 0 until n) {
            val j = (i + 1) % n
            val (d, t) = CutGeometry.segDist(x, y, pr[i * 2], pr[i * 2 + 1], pr[j * 2], pr[j * 2 + 1])
            if (d < bestD && t > 0.05f && t < 0.95f) { bestD = d; best = i }
        }
        if (best < 0) return false
        scope.launch {
            val hit = withContext(Dispatchers.Default) {
                safe { NativeBridge.nativeRaycast(handle, x, y) } ?: safe { NativeBridge.nativeUnproject(handle, floatArrayOf(x, y)) }
            } ?: return@launch
            pushOutlineHistory()
            cutPoints.add(best + 1, hit)
            selectedCutPoint = best + 1
            schedulePreview()
        }
        return true
    }

    fun updateDepth(lo: Float, hi: Float) {
        depthLo = lo
        depthHi = hi
        schedulePreview()
    }

    /** Shows in orange what the cut would take, a moment after the outline or depth changes. */
    fun schedulePreview() {
        previewJob?.cancel()
        if (brushSelCount > 0) return
        if (!cutActive || cutPoints.size < 3) { clearPreview(); return }
        val flat = cutOutline()  // the same smoothed outline the cut will use
        val lo = depthLo
        val hi = depthHi
        val er = eraseArray()
        if (brushSelCount > 0) return
        if (linesOnly) { clearPreview(); return }
        val mode = cutMode
        previewJob = scope.launch {
            delay(150)
            // exact preview: the real cut as a dry run, split right along the drawn line
            withContext(Dispatchers.Default) { safe { NativeBridge.nativePreviewCut(handle, flat, lo, hi, er, mode, true) } }
            view?.requestRender()
        }
    }

    private fun clearPreview() {
        previewJob?.cancel()
        safe { NativeBridge.nativePreviewCut(handle, null, 0f, 1f, null, 0, false) }
        view?.requestRender()
    }

    /** True while one-finger drags draw a shape instead of rotating the view. */
    fun isDrawMode() = cutActive && cutTool == TOOL_OUTLINE && cutShape in CutGeometry.FREEHAND..CutGeometry.ELLIPSE && !askConnect && !createPartOpen && !cutBusy

    fun drawStart(x: Float, y: Float) {
        drawAnchorX = x; drawAnchorY = y
        strokePts.clear()
        strokePts += x; strokePts += y
        drawPath = floatArrayOf(x, y)
    }

    fun drawMove(x: Float, y: Float) {
        drawPath = when (cutShape) {
            CutGeometry.RECTANGLE -> CutGeometry.rectangle(drawAnchorX, drawAnchorY, x, y)
            CutGeometry.ELLIPSE -> CutGeometry.ellipse(drawAnchorX, drawAnchorY, x, y)
            else -> { strokePts += x; strokePts += y; strokePts.toFloatArray() }
        }
    }

    fun drawCancel() { drawPath = FloatArray(0); strokePts.clear() }

    fun drawEnd() {
        val shape = when (cutShape) {
            CutGeometry.FREEHAND -> CutGeometry.simplify(drawPath, 4f)
            else -> drawPath
        }
        drawPath = FloatArray(0)
        strokePts.clear()
        if (shape.size < 6) { cutHint = "Draw a larger shape around the area."; return }
        val density = context.resources.displayMetrics.density
        // turn the drawn shape into a few editable points: rectangles keep their corners,
        // ovals and freehand strokes become smooth curves through key points
        val editable = when (cutShape) {
            CutGeometry.RECTANGLE -> shape
            CutGeometry.ELLIPSE -> FloatArray(16) { k -> shape[((k / 2) * (shape.size / 2) / 8) * 2 + (k % 2)] }
            else -> CutGeometry.keyPoints(shape, 3f * density).let { if (it.size >= 6) it else shape }
        }
        val drawnShape = cutShape
        val pts3 = safe { NativeBridge.nativeUnproject(handle, editable) } ?: return
        pushOutlineHistory()
        cutPoints.clear()
        for (i in 0 until pts3.size / 3) cutPoints.add(floatArrayOf(pts3[i * 3], pts3[i * 3 + 1], pts3[i * 3 + 2]))
        cutShape = if (drawnShape == CutGeometry.RECTANGLE) CutGeometry.POINTS else CutGeometry.CURVE
        cutHint = "Drag the points to fine-tune, tap a line to add a point, then close the outline."
        if (drawnShape == CutGeometry.FREEHAND) askConnect = true  // "connect your last point with the start?"
        schedulePreview()
    }

    /** The outline that will actually be cut: curves are smoothed into many small segments. */
    private fun cutOutline(): FloatArray {
        val flat = FloatArray(cutPoints.size * 3)
        cutPoints.forEachIndexed { i, p -> System.arraycopy(p, 0, flat, i * 3, 3) }
        if (cutShape != CutGeometry.CURVE || cutPoints.size < 3) return flat
        val scr = projectCutPoints()
        if (scr.any { it.isNaN() }) return flat
        val smooth = CutGeometry.smoothClosed(scr, 4f * context.resources.displayMetrics.density)
        return safe { NativeBridge.nativeUnproject(handle, smooth) } ?: flat
    }

    /** Screen outline for the overlay (smoothed when using the curve tool). */
    fun outlineForOverlay(projected: FloatArray): FloatArray =
        if (cutShape == CutGeometry.CURVE && projected.size >= 6 && projected.none { it.isNaN() })
            CutGeometry.smoothClosed(projected, 4f * context.resources.displayMetrics.density)
        else projected

    private fun unitMeters() = when (unit) { 0 -> 1.0; 1 -> 0.01; 2 -> 0.001; 3 -> 0.0254; else -> 0.3048 }

    /** Outer and inner outlines (model space) offset by half the gap each way, built on screen. */
    private fun gapOutlines(flat: FloatArray, gapUnits: Double): Pair<FloatArray, FloatArray>? {
        val scr = safe { NativeBridge.nativeProject(handle, flat) } ?: return null
        if (scr.any { it.isNaN() }) return null
        val ppu = safe { NativeBridge.nativePixelsPerUnit(handle) } ?: return null
        val half = (gapUnits * ppu / 2).toFloat()
        if (half < 0.05f) return null
        val outer = CutGeometry.offset(scr, half)
        val inner = CutGeometry.offset(scr, -half)
        val o = safe { NativeBridge.nativeUnproject(handle, outer) } ?: return null
        val i = safe { NativeBridge.nativeUnproject(handle, inner) } ?: return null
        return o to i
    }

    // ---------------------------------------------------------------- flap template
    private fun addFlapPoint(x: Float, y: Float) {
        if (askConnect || createPartOpen || cutBusy) return
        if (cutPoints.size >= 2) return
        val density = context.resources.displayMetrics.density
        scope.launch {
            val hit = withContext(Dispatchers.Default) { safe { NativeBridge.nativeSnap(handle, x, y, 16 * density, false, true) } }
            if (hit == null) { cutHint = "Tap right on the wing's trailing edge."; return@launch }
            pushOutlineHistory()
            cutPoints.add(hit)
            cutHint = if (cutPoints.size < 2) "Now tap the trailing edge where the flap ends." else "Set the chord depth, then Use outline."
            if (cutPoints.size == 2) updateFlapPreview()
        }
    }

    fun setFlapDepthValue(d: Float) {
        flapDepth = d
        updateFlapPreview()
    }

    /** Builds the flap outline on screen: from the trailing edge towards the leading edge by the chord depth. */
    fun updateFlapPreview() {
        if (cutPoints.size < 2) { flapPreview = FloatArray(0); return }
        flapJob?.cancel()
        flapJob = scope.launch {
            delay(60)
            val poly = withContext(Dispatchers.Default) { buildFlapOutline() }
            flapPreview = poly ?: FloatArray(0)
            if (poly == null) cutHint = "Couldn't find the wing behind those points — try the Top view."
        }
    }

    private fun buildFlapOutline(): FloatArray? {
        val pr = projectCutPoints()
        if (pr.size < 4 || pr.any { it.isNaN() }) return null
        val ax = pr[0]; val ay = pr[1]; val bx = pr[2]; val by = pr[3]
        val len = kotlin.math.hypot(bx - ax, by - ay)
        if (len < 4f) return null
        var nx = -(by - ay) / len
        var ny = (bx - ax) / len
        fun hits(x: Float, y: Float) = safe { NativeBridge.nativeRaycast(handle, x, y) } != null
        val mx = (ax + bx) / 2
        val my = (ay + by) / 2
        if (!hits(mx + nx * 12, my + ny * 12)) { nx = -nx; ny = -ny }
        if (!hits(mx + nx * 12, my + ny * 12)) return null
        // chord length on screen: grow until we leave the wing, then binary search the edge
        fun chord(px: Float, py: Float): Float {
            var lo = 0f
            var hi = 8f
            while (hi < 6000f && hits(px + nx * hi, py + ny * hi)) { lo = hi; hi *= 2 }
            repeat(14) {
                val mid = (lo + hi) / 2
                if (hits(px + nx * mid, py + ny * mid)) lo = mid else hi = mid
            }
            return lo
        }
        val inset = 0.03f * len
        val ca = chord(ax + (bx - ax) / len * inset + nx * 4, ay + (by - ay) / len * inset + ny * 4)
        val cb = chord(bx - (bx - ax) / len * inset + nx * 4, by - (by - ay) / len * inset + ny * 4)
        if (ca < 4f || cb < 4f) return null
        val da = ca * flapDepth
        val db = cb * flapDepth
        val ext = 14f * context.resources.displayMetrics.density  // reach past the trailing edge so the cut runs clean
        return floatArrayOf(
            ax - nx * ext, ay - ny * ext,
            bx - nx * ext, by - ny * ext,
            bx + nx * db, by + ny * db,
            ax + nx * da, ay + ny * da,
        )
    }

    fun useFlapOutline() {
        val poly = flapPreview
        if (poly.size < 8) return
        val pts3 = safe { NativeBridge.nativeUnproject(handle, poly) } ?: return
        pushOutlineHistory()
        cutPoints.clear()
        for (i in 0 until pts3.size / 3) cutPoints.add(floatArrayOf(pts3[i * 3], pts3[i * 3 + 1], pts3[i * 3 + 2]))
        flapPreview = FloatArray(0)
        schedulePreview()
        cutShape = CutGeometry.POINTS  // the outline is now a normal polygon
        cutMode = 3
        createPartOpen = true
    }

    // ---------------------------------------------------------------- camera presets
    fun viewPreset(i: Int) {
        safe { NativeBridge.nativeViewPreset(handle, i) }
        view?.requestRender()
    }

    fun toggleOrtho() {
        ortho = !ortho
        safe { NativeBridge.nativeSetOrtho(handle, ortho) }
        view?.requestRender()
    }

    // ---------------------------------------------------------------- hinges
    fun startHinge(part: Int) {
        if (cutActive) cancelCut()
        if (measureActive) toggleMeasure()
        hingePart = part
        hingePoints.clear()
        hinges[part]?.let { hingePoints.add(it.a); hingePoints.add(it.b) }
    }

    fun endHinge() {
        hingePart = -1
        hingePoints.clear()
        view?.requestRender()
    }

    private fun addHingePoint(x: Float, y: Float) {
        val part = hingePart
        val density = context.resources.displayMetrics.density
        scope.launch {
            val hit = withContext(Dispatchers.Default) { safe { NativeBridge.nativeSnap(handle, x, y, 16 * density, true, true) } } ?: return@launch
            if (hingePoints.size >= 2) hingePoints.clear()
            hingePoints.add(hit)
            if (hingePoints.size == 2) {
                hinges = hinges + (part to Hinge(hingePoints[0], hingePoints[1], 0f))
                applyHinge(part)
            }
        }
    }

    fun setDeflection(part: Int, deg: Float) {
        val h = hinges[part] ?: return
        hinges = hinges + (part to h.copy(deg = deg))
        applyHinge(part)
    }

    fun removeHinge(part: Int) {
        hinges = hinges - part
        safe { NativeBridge.nativeSetPartHinge(handle, part, FloatArray(3), FloatArray(3), Float.NaN) }
        hingePoints.clear()
        view?.requestRender()
    }

    private fun applyHinge(part: Int) {
        val h = hinges[part] ?: return
        safe { NativeBridge.nativeSetPartHinge(handle, part, h.a, h.b, h.deg) }
        view?.requestRender()
    }

    fun projectHingePoints(): FloatArray {
        if (hingePoints.isEmpty()) return FloatArray(0)
        val flat = FloatArray(hingePoints.size * 3)
        hingePoints.forEachIndexed { i, p -> System.arraycopy(p, 0, flat, i * 3, 3) }
        return safe { NativeBridge.nativeProject(handle, flat) } ?: FloatArray(0)
    }

    fun connectOutline() {
        askConnect = false
        if (cutPoints.size >= 3) createPartOpen = true
    }

    fun nextCutName(): String = "Cut part ${cutCount + 1}"

    fun createPart(name: String, mode: Int) {
        if (editPart >= 0) { applyPartEdit(); return }
        if (brushSelCount > 0) { separateSelection(name); return }
        if (cutPoints.size < 3) return
        cutBusy = true
        val flat = cutOutline()
        val mirror = if (mirrorCut) mirrorAxis else -1
        // gap: cut a slightly bigger outline, then the real part from inside it, and drop the thin band between
        val gapUnits = gapMm / 1000.0 / unitMeters()
        val dLo = depthLo
        val dHi = depthHi
        val er = eraseArray()
        lastOutline = flat.copyOf()
        clearPreview()
        val outlines: Pair<FloatArray, FloatArray>? = if (gapUnits > 0) gapOutlines(flat, gapUnits) else null
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                fun one(pName: String, mirrorAx: Int, keepUndo: Boolean): String {
                    if (outlines == null) return safe { NativeBridge.nativeCut(handle, flat, mode, pName, mirrorAx, keepUndo, -1, dLo, dHi, er) } ?: "ERR|Cancelled"
                    val outer = safe { NativeBridge.nativeCut(handle, outlines.first, mode, "$pName (gap)", mirrorAx, keepUndo, -1, dLo, dHi, er) } ?: "ERR|Cancelled"
                    if (outer.startsWith("ERR|")) return outer
                    val tmp = outer.substringBefore('|').toIntOrNull() ?: return outer
                    val inner = safe { NativeBridge.nativeCut(handle, outlines.second, mode, pName, mirrorAx, false, tmp, dLo, dHi, er) } ?: "ERR|Cancelled"
                    safe { NativeBridge.nativeDeletePart(handle, tmp) }
                    return inner
                }
                val first = one(name, -1, true)
                if (mirror >= 0 && !first.startsWith("ERR|")) {
                    val second = one("$name (mirror)", mirror, false)
                    if (second.startsWith("ERR|")) cutHint = "Mirror side: " + second.removePrefix("ERR|")
                }
                first
            }
            cutBusy = false
            createPartOpen = false
            if (r.startsWith("ERR|")) {
                cutHint = r.removePrefix("ERR|")
                return@launch
            }
            val parts = r.split('|')
            val idx = parts.getOrNull(0)?.toIntOrNull() ?: -1
            val tris = parts.getOrNull(1)?.toLongOrNull() ?: 0
            val caps = parts.getOrNull(2)?.toLongOrNull() ?: 0
            cutCount++
            resetSmart()
            cutActive = false
            cutPoints.clear()
            clearOutlineHistory()
            hasRedo = false
            cutHint = null
            hasUndo = true
            refreshStats()
            rebuildReport()
            selectPart(idx)
            lastCut = "Created \"$name\"${if (mirror >= 0) " + mirror" else ""} · ${com.dronedirector.vertexapro.data.formatCount(tris)} triangles" +
                if (caps > 0) " · sealed with ${com.dronedirector.vertexapro.data.formatCount(caps)}" else ""
            view?.requestRender()
            delay(250)
            refreshStats()
        }
    }

    private fun separateSelection(name: String) {
        cutBusy = true
        scope.launch {
            val r = withContext(Dispatchers.Default) { safe { NativeBridge.nativeSeparateSelection(handle, name) } } ?: "ERR|Cancelled"
            cutBusy = false
            createPartOpen = false
            if (r.startsWith("ERR|")) { cutHint = r.removePrefix("ERR|"); return@launch }
            val idx = r.substringBefore('|').toIntOrNull() ?: -1
            val tris = r.split('|').getOrNull(1)?.toLongOrNull() ?: 0
            brushSelCount = 0
            finishNewPart(idx, "Created \"$name\" · ${com.dronedirector.vertexapro.data.formatCount(tris)} triangles")
        }
    }

    private suspend fun finishNewPart(idx: Int, message: String) {
        cutCount++
        resetSmart()
        cutActive = false
        cutPoints.clear()
        clearOutlineHistory()
        hasRedo = false
        cutHint = null
        hasUndo = true
        refreshStats()
        rebuildReport()
        selectPart(idx)
        lastCut = message
        view?.requestRender()
        delay(250)
        refreshStats()
    }

    // ---------------------------------------------------------------- combine / connect parts
    var combineFrom by mutableIntStateOf(-1)
        private set
    /** Two parts waiting for "combine or connect?" and how many points they share. */
    var combineAsk by mutableStateOf<Triple<Int, Int, Long>?>(null)
        private set

    fun startCombine(part: Int) {
        combineFrom = part
        lastCut = null
        cutHint = null
    }

    fun cancelCombine() {
        combineFrom = -1
        combineAsk = null
    }

    private fun askCombine(a: Int, b: Int) {
        combineFrom = -1
        scope.launch {
            val touch = withContext(Dispatchers.Default) { safe { NativeBridge.nativePartsTouch(handle, a, b) } } ?: 0L
            combineAsk = Triple(a, b, touch)
        }
    }

    fun partName(i: Int) = parts.firstOrNull { it.index == i }?.name ?: "Part"

    fun combineParts(connect: Boolean) {
        val (a, b, _) = combineAsk ?: return
        combineAsk = null
        cutBusy = true
        scope.launch {
            val r = withContext(Dispatchers.Default) { safe { NativeBridge.nativeMergeParts(handle, a, b, connect) } }
            cutBusy = false
            if (r == null || r[0] == 0L) { lastCut = "Couldn't combine those parts."; return@launch }
            hasUndo = true
            hasRedo = false
            refreshStats()
            rebuildReport()
            selectPart(a)
            lastCut = if (connect) "Connected into \"${partName(a)}\" · ${com.dronedirector.vertexapro.data.formatCount(r[1])} seal triangles removed, " +
                "${com.dronedirector.vertexapro.data.formatCount(r[2])} seam points welded"
            else "Combined into \"${partName(a)}\""
            view?.requestRender()
        }
    }

    fun undoLastCut() {
        scope.launch {
            val ok = withContext(Dispatchers.Default) { safe { NativeBridge.nativeUndoCut(handle) } } ?: false
            if (ok) {
                hasUndo = false
                hasRedo = true
                lastCut = "Cut undone"
                selectPart(-1)
                delay(100)
                refreshStats()
            }
            view?.requestRender()
        }
    }

    fun redoLastCut() {
        scope.launch {
            val ok = withContext(Dispatchers.Default) { safe { NativeBridge.nativeRedoCut(handle) } } ?: false
            if (ok) {
                hasRedo = false
                hasUndo = true
                lastCut = "Cut redone"
                delay(100)
                refreshStats()
            }
            view?.requestRender()
        }
    }

    fun renamePart(i: Int, name: String) {
        if (name.isBlank()) return
        safe { NativeBridge.nativeRenamePart(handle, i, name.trim()) }
        refreshParts()
    }

    // ---------------------------------------------------------------- parts
    fun pick(x: Float, y: Float) {
        safe { NativeBridge.nativeRequestPick(handle, x, y) }
        view?.requestRender()
        scope.launch {
            repeat(40) {
                delay(16)
                val r = safe { NativeBridge.nativePickResult(handle) } ?: return@launch
                if (r != -2) {
                    selectPart(if (r >= 0 && parts.any { it.index == r }) r else -1)
                    return@launch
                }
            }
        }
    }

    fun selectPart(i: Int) {
        if (combineFrom >= 0 && i >= 0) {
            if (i != combineFrom) { askCombine(combineFrom, i); return }
        }
        selectedPart = i
        safe { NativeBridge.nativeSelectPart(handle, i) }
        view?.requestRender()
    }

    fun setPartVisible(i: Int, visible: Boolean) {
        hiddenParts = if (visible) hiddenParts - i else hiddenParts + i
        safe { NativeBridge.nativeSetPartHidden(handle, i, !visible) }
        if (!visible && selectedPart == i) selectPart(-1)
        view?.requestRender()
    }

    fun showAllParts() {
        hiddenParts = emptySet()
        safe { NativeBridge.nativeSetAllPartsHidden(handle, false) }
        view?.requestRender()
    }

    fun hideAllParts() {
        hiddenParts = parts.map { it.index }.toSet()
        safe { NativeBridge.nativeSetAllPartsHidden(handle, true) }
        selectPart(-1)
    }

    fun isolatePart(i: Int) {
        hiddenParts = parts.map { it.index }.filter { it != i }.toSet()
        safe { NativeBridge.nativeIsolatePart(handle, i) }
        view?.requestRender()
    }

    fun focusPart(i: Int) {
        safe { NativeBridge.nativeFocusPart(handle, i) }
        view?.requestRender()
    }

    /** Reloads the model with every unconnected piece as its own part (OBJ). */
    fun updateSplit(on: Boolean) {
        if (on == splitLoose) return
        splitLoose = on
        hiddenParts = emptySet()
        selectedPart = -1
        parts = emptyList()
        textureJob?.cancel()
        val v = view
        if (v != null) v.queueEvent { safe { NativeBridge.nativeClearModel(handle) }; scope.launch { start() } }
        else start()
    }

    // ---------------------------------------------------------------- look
    fun updateLighting(p: Int) {
        lighting = p
        safe { NativeBridge.nativeSetLighting(handle, p) }
        view?.requestRender()
    }

    fun updatePointSize(s: Float) {
        pointSize = s
        safe { NativeBridge.nativeSetPointScale(handle, s) }
        view?.requestRender()
    }

    fun updatePointColorMode(m: Int) {
        pointColorMode = m
        safe { NativeBridge.nativeSetPointColorMode(handle, m) }
        view?.requestRender()
    }

    // ---------------------------------------------------------------- snapshot & turntable
    fun snapshot(transparent: Boolean) {
        val v = view ?: return
        val w0 = surfaceW
        val h0 = surfaceH
        if (w0 <= 0 || h0 <= 0) return
        job = JobState("Saving snapshot", 0.3f)
        val scale = minOf(2f, 4096f / maxOf(w0, h0))
        val w = (w0 * scale).toInt()
        val h = (h0 * scale).toInt()
        v.queueEvent {
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            val ok = safe { NativeBridge.nativeSnapshot(handle, w, h, transparent, buf) } ?: false
            scope.launch {
                if (!ok) { job = JobState("Snapshot", 1f, true, "The snapshot could not be rendered.", failed = true); return@launch }
                val uri = withContext(Dispatchers.IO) {
                    runCatching {
                        val bmp = Capture.fromGl(buf, w, h, w0, h0)
                        Capture.savePng(context, bmp, Capture.baseName(source.name))
                    }.getOrNull()
                }
                job = if (uri != null) JobState("Snapshot saved", 1f, true, "Saved to Pictures/Vertexa Pro", uri, "image/png")
                else JobState("Snapshot", 1f, true, "The snapshot could not be saved.", failed = true)
            }
        }
        v.requestRender()
    }

    fun recordTurntable() {
        val v = view ?: return
        job = JobState("Recording turntable", 0f)
        val out = java.io.File(context.cacheDir, "turntable.mp4")
        v.queueEvent {
            val ok = safe {
                Capture.recordTurntable(handle, out) { f -> scope.launch { job = JobState("Recording turntable", f) } }
            } ?: false
            scope.launch {
                if (!ok) {
                    job = JobState("Turntable", 1f, true, "This device couldn't record the video.", failed = true)
                    return@launch
                }
                val uri = withContext(Dispatchers.IO) { runCatching { Capture.saveVideo(context, out, Capture.baseName(source.name)) }.getOrNull() }
                job = if (uri != null) JobState("Video saved", 1f, true, "Saved to Movies/Vertexa Pro", uri, "video/mp4")
                else JobState("Turntable", 1f, true, "The video could not be saved.", failed = true)
            }
            view?.requestRender()
        }
        v.requestRender()
    }

    // ---------------------------------------------------------------- export
    fun export(tree: Uri, format: Int, visibleOnly: Boolean) {
        job = JobState("Saving ${when (format) { 0 -> "OBJ"; 3 -> "GLB"; else -> "STL" }}", if (canEdit) 0.5f else 0f)
        val namesByMat = safe { NativeBridge.nativeMaterialTextures(handle) } ?: emptyArray()
        val hidden = BooleanArray((parts.maxOfOrNull { it.index } ?: -1) + 1) { visibleOnly && it in hiddenParts }
        val poll = scope.launch {
            while (true) {
                delay(120)
                val p = safe { NativeBridge.nativeExportProgress(handle) } ?: break
                job = job?.copy(progress = p)
            }
        }
        val partNames = parts.associate { it.index to it.name }
        val pivotCount = (parts.maxOfOrNull { it.index } ?: -1) + 1
        val pivots = FloatArray(pivotCount * 3) { Float.NaN }
        hinges.forEach { (part, h) -> if (part < pivotCount) System.arraycopy(h.a, 0, pivots, part * 3, 3) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Exporter.export(context, handle, tree, loadSource, resolver, format, hidden, namesByMat, splitLoose,
                        fromMemory = canEdit, partNames = partNames, pivots = pivots)
                }
            }
            poll.cancel()
            job = result.fold(
                { msg -> JobState("Saved", 1f, true, msg) },
                { e -> JobState("Export", 1f, true, e.message ?: "The export failed.", failed = true) },
            )
        }
    }

    fun extractArchive(tree: Uri) {
        job = JobState("Extracting files", 0f)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Exporter.extract(context, loadSource, tree) { f -> scope.launch { job = job?.copy(progress = f) } } }
            }
            job = result.fold(
                { n -> JobState("Extracted", 1f, true, "$n files saved to the chosen folder") },
                { e -> JobState("Extract", 1f, true, e.message ?: "Extracting failed.", failed = true) },
            )
        }
    }

    fun cancelExport() { safe { NativeBridge.nativeExportCancel(handle) } }
    fun dismissJob() { job = null }

    fun cancel() {
        safe { NativeBridge.nativeCancel(handle) }
    }

    fun dispose() {
        rememberLastView()
        safe { NativeBridge.nativeCancel(handle) }
        val jobs = listOfNotNull(loadJob, textureJob)
        scope.cancel()
        view = null
        CoroutineScope(Dispatchers.IO).launch {
            jobs.forEach { it.join() }
            delay(300)
            synchronized(lock) {
                if (!destroyed) {
                    destroyed = true
                    NativeBridge.nativeDestroy(handle)
                }
            }
        }
    }

    companion object {
        const val TOOL_OUTLINE = 0
        const val TOOL_ERASE = 1
        const val TOOL_WAND = 2
        const val TOOL_AI = 3
        const val TOOL_BRUSH = 4
        const val LIGHT_STUDIO = 0
        const val LIGHT_FLAT = 5
    }
}
