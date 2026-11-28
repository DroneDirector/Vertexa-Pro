package com.dronedirector.vertexapro.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLUtils
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

    // ---------------------------------------------------------------- cut tool
    fun onTap(x: Float, y: Float) {
        when {
            hingePart >= 0 -> addHingePoint(x, y)
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
        outlineUndo.add(cutPoints.map { it.copyOf() })
        if (outlineUndo.size > 200) outlineUndo.removeAt(0)
        outlineRedo.clear()
        syncHistory()
    }

    private fun clearOutlineHistory() {
        outlineUndo.clear()
        outlineRedo.clear()
        syncHistory()
    }

    fun undoCutPoint() {
        if (outlineUndo.isEmpty()) return
        outlineRedo.add(cutPoints.map { it.copyOf() })
        val prev = outlineUndo.removeAt(outlineUndo.size - 1)
        cutPoints.clear()
        cutPoints.addAll(prev)
        askConnect = false
        selectedCutPoint = -1
        syncHistory()
        schedulePreview()
    }

    fun redoCutPoint() {
        if (outlineRedo.isEmpty()) return
        outlineUndo.add(cutPoints.map { it.copyOf() })
        val next = outlineRedo.removeAt(outlineRedo.size - 1)
        cutPoints.clear()
        cutPoints.addAll(next)
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
        if (!cutActive || cutPoints.isEmpty() || askConnect || createPartOpen || cutBusy) return -1
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
        scope.launch {
            val hit = withContext(Dispatchers.Default) {
                safe { NativeBridge.nativeSnap(handle, x, y, if (snapOn) 12 * density else 0f, snapOn, snapOn) }
                    ?: safe { NativeBridge.nativeUnproject(handle, floatArrayOf(x, y)) }
            }
            if (hit != null && i < cutPoints.size) cutPoints[i] = hit
            dragBusy = false
            schedulePreview()
        }
    }

    fun endDragPoint() {
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
        if (!cutActive || cutPoints.size < 3) { clearPreview(); return }
        val flat = FloatArray(cutPoints.size * 3)
        cutPoints.forEachIndexed { i, p -> System.arraycopy(p, 0, flat, i * 3, 3) }
        val lo = depthLo
        val hi = depthHi
        previewJob = scope.launch {
            delay(120)
            withContext(Dispatchers.Default) { safe { NativeBridge.nativePreviewCut(handle, flat, lo, hi) } }
            view?.requestRender()
        }
    }

    private fun clearPreview() {
        previewJob?.cancel()
        safe { NativeBridge.nativePreviewCut(handle, null, 0f, 1f) }
        view?.requestRender()
    }

    /** True while one-finger drags draw a shape instead of rotating the view. */
    fun isDrawMode() = cutActive && cutShape in CutGeometry.FREEHAND..CutGeometry.ELLIPSE && !askConnect && !createPartOpen && !cutBusy

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
        if (cutPoints.size < 3) return
        cutBusy = true
        val flat = cutOutline()
        val mirror = if (mirrorCut) mirrorAxis else -1
        // gap: cut a slightly bigger outline, then the real part from inside it, and drop the thin band between
        val gapUnits = gapMm / 1000.0 / unitMeters()
        val dLo = depthLo
        val dHi = depthHi
        clearPreview()
        val outlines: Pair<FloatArray, FloatArray>? = if (gapUnits > 0) gapOutlines(flat, gapUnits) else null
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                fun one(pName: String, mirrorAx: Int, keepUndo: Boolean): String {
                    if (outlines == null) return safe { NativeBridge.nativeCut(handle, flat, mode, pName, mirrorAx, keepUndo, -1, dLo, dHi) } ?: "ERR|Cancelled"
                    val outer = safe { NativeBridge.nativeCut(handle, outlines.first, mode, "$pName (gap)", mirrorAx, keepUndo, -1, dLo, dHi) } ?: "ERR|Cancelled"
                    if (outer.startsWith("ERR|")) return outer
                    val tmp = outer.substringBefore('|').toIntOrNull() ?: return outer
                    val inner = safe { NativeBridge.nativeCut(handle, outlines.second, mode, pName, mirrorAx, false, tmp, dLo, dHi) } ?: "ERR|Cancelled"
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
}
