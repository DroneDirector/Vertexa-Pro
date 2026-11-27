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
}

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

    val resolver = SiblingResolver(
        context, source.extras, source.tree,
        source.relPath?.substringBeforeLast('/', "")?.ifEmpty { null },
    )

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

    var mode by mutableIntStateOf(initialMode)
        private set
    var grid by mutableStateOf(initialGrid)
        private set
    var autoRotate by mutableStateOf(false)
        private set
    var upZ by mutableStateOf(Formats.defaultZUp(source.name))
        private set

    init {
        NativeBridge.nativeSetMode(handle, mode)
        NativeBridge.nativeSetGrid(handle, grid)
        NativeBridge.nativeSetUpAxisZ(handle, upZ)
        NativeBridge.nativeSetPointScale(handle, (context.resources.displayMetrics.density * 1.6f).coerceIn(1.5f, 6f))
        NativeBridge.nativeSetTheme(handle, palette.nativeTheme())
    }

    private inline fun <T> safe(block: () -> T): T? = synchronized(lock) { if (destroyed) null else block() }

    // ---------------------------------------------------------------- loading
    fun start() {
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    private suspend fun load() {
        phase = Phase.LOADING
        error = null
        progress = 0f
        val t0 = System.nanoTime()
        val needsSeek = Formats.ext(source.name) !in Formats.native
        val fd = withContext(Dispatchers.IO) { SiblingResolver.openSeekableFd(context, source.uri, needsSeek) }
        if (fd < 0) {
            error = "The file could not be opened. It may have been moved, deleted, or access has expired. Open it again from your files."
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
            NativeBridge.nativeLoad(handle, fd, source.size, source.name, resolver)
        }
        poll.cancel()
        refreshStats()
        progress = 1f
        view?.requestRender()
        if (err == "cancelled") return
        if (err.isNotEmpty()) {
            error = err
            phase = Phase.ERROR
            return
        }
        loadMillis = (System.nanoTime() - t0) / 1_000_000
        phase = Phase.READY
        // keep pumping frames until every chunk has reached the GPU
        scope.launch {
            while (isActive && (safe { NativeBridge.nativeStats(handle)[5] } ?: 0L) > 0L) {
                view?.requestRender(); delay(50)
            }
            refreshStats()
        }
        textureJob = scope.launch { loadTextures(null) }
    }

    private fun refreshStats() {
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
                        resolver.findUri(name)?.let { TextureDecoder.decodeUri(context, it, name, cap) }
                    }
                }.getOrNull()
            }
            scope.coroutineContext.ensureActive()
            if (bmp == null) {
                miss += name
                continue
            }
            upload(bmp, mats)
            texturesLoaded++
        }
        missing = if (only == null) miss else missing.filter { it in miss }
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
        safe { NativeBridge.nativeSurfaceChanged(handle, w, h) }
    }

    fun onDrawFrame(): Boolean = safe { NativeBridge.nativeDraw(handle, System.nanoTime()) } ?: false

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

    fun cancel() {
        safe { NativeBridge.nativeCancel(handle) }
    }

    fun dispose() {
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
