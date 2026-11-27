package com.dronedirector.vertexapro.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.LruCache
import com.dronedirector.vertexapro.data.Formats
import com.dronedirector.vertexapro.ui.DarkPalette
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque

/** Request for a thumbnail of a model that hasn't been opened yet. */
data class ThumbJob(
    val uri: Uri,
    val name: String,
    val size: Long,
    val extras: List<Uri> = emptyList(),
    val tree: Uri? = null,
    val relPath: String? = null,
)

object ThumbnailStore {
    const val SIZE = 384
    /** Models larger than this get a thumbnail only once they have been opened in the viewer. */
    const val AUTO_LIMIT = 250L * 1024 * 1024

    /** Bumped whenever a thumbnail is written, so lists can refresh. */
    val version = MutableStateFlow(0)
    private val mem = LruCache<String, Bitmap>(120)

    fun key(uri: Uri): String {
        val d = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "thumbs").apply { mkdirs() }
    private fun file(ctx: Context, uri: Uri) = File(dir(ctx), key(uri) + ".png")
    private fun failFile(ctx: Context, uri: Uri) = File(dir(ctx), key(uri) + ".fail")

    fun cached(uri: Uri): Bitmap? = mem.get(key(uri))

    fun load(ctx: Context, uri: Uri): Bitmap? {
        mem.get(key(uri))?.let { return it }
        val f = file(ctx, uri)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { mem.put(key(uri), it) }
    }

    fun has(ctx: Context, uri: Uri) = file(ctx, uri).exists()
    fun failed(ctx: Context, uri: Uri) = failFile(ctx, uri).exists()

    fun save(ctx: Context, uri: Uri, bmp: Bitmap) {
        runCatching {
            file(ctx, uri).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            failFile(ctx, uri).delete()
            mem.put(key(uri), bmp)
            version.value++
        }
    }

    fun markFailed(ctx: Context, uri: Uri) {
        runCatching { failFile(ctx, uri).writeText("1") }
        version.value++
    }

    /** Turns a bottom-up RGBA buffer from glReadPixels into a centred square thumbnail. */
    fun fromPixels(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        buf.rewind()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        val side = minOf(w, h)
        val m = Matrix().apply {
            preScale(1f, -1f)
            postScale(SIZE.toFloat() / side, SIZE.toFloat() / side)
        }
        val out = Bitmap.createBitmap(raw, (w - side) / 2, (h - side) / 2, side, side, m, true)
        if (out != raw) raw.recycle()
        return out
    }
}

/** Renders thumbnails off-screen on its own GL thread, one model at a time. */
object ThumbnailGenerator {
    private val queue = LinkedBlockingDeque<ThumbJob>()
    private val queued = HashSet<String>()
    private var thread: Thread? = null
    private lateinit var appContext: Context

    @Synchronized
    fun request(ctx: Context, job: ThumbJob) {
        appContext = ctx.applicationContext
        if (!Formats.isModel(job.name)) return
        if (job.size > ThumbnailStore.AUTO_LIMIT || job.size < 0 && Formats.ext(job.name) !in Formats.native) return
        if (ThumbnailStore.has(appContext, job.uri) || ThumbnailStore.failed(appContext, job.uri)) return
        val k = job.uri.toString()
        if (!queued.add(k)) return
        queue.addFirst(job) // newest request first: what is on screen now
        if (thread == null) {
            thread = Thread(::run, "vertexa-thumbs").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
                start()
            }
        }
    }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private fun initEgl(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false
        val es3 = 0x40
        val attempts = listOf(
            intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_RENDERABLE_TYPE, es3, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, 4, EGL14.EGL_NONE),
            intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_RENDERABLE_TYPE, es3, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE),
        )
        var cfg: EGLConfig? = null
        for (a in attempts) {
            val out = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (EGL14.eglChooseConfig(display, a, 0, out, 0, 1, n, 0) && n[0] > 0) { cfg = out[0]; break }
        }
        if (cfg == null) return false
        context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        val s = ThumbnailStore.SIZE
        surface = EGL14.eglCreatePbufferSurface(display, cfg, intArrayOf(EGL14.EGL_WIDTH, s, EGL14.EGL_HEIGHT, s, EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    private fun run() {
        if (!initEgl()) {
            thread = null
            return
        }
        while (true) {
            val job = queue.takeFirst()
            synchronized(this) { queued.remove(job.uri.toString()) }
            if (ThumbnailStore.has(appContext, job.uri)) continue
            val ok = runCatching { render(job) }.getOrDefault(false)
            if (!ok) ThumbnailStore.markFailed(appContext, job.uri)
        }
    }

    private fun render(original: ThumbJob): Boolean {
        val ctx = appContext
        val prep = ModelPrep.prepare(ctx, com.dronedirector.vertexapro.data.ModelSource(
            original.uri, original.name, original.size, original.extras, original.tree, original.relPath))
        val unpacked = prep.source.uri != original.uri
        val job = if (unpacked) ThumbJob(prep.source.uri, prep.source.name, prep.source.size, prep.source.extras) else original
        val needsSeek = Formats.ext(job.name) !in Formats.native
        val fd = SiblingResolver.openSeekableFd(ctx, job.uri, needsSeek)
        if (fd < 0) return false
        val h = NativeBridge.nativeCreate()
        try {
            val s = ThumbnailStore.SIZE
            NativeBridge.nativeSurfaceCreated(h)
            NativeBridge.nativeSurfaceChanged(h, s, s)
            NativeBridge.nativeSetMode(h, ViewModes.TEXTURED)
            NativeBridge.nativeSetGrid(h, false)
            NativeBridge.nativeSetUpAxisZ(h, Formats.defaultZUp(job.name))
            NativeBridge.nativeSetPointScale(h, 2f)
            NativeBridge.nativeSetTheme(h, DarkPalette.nativeTheme())
            val resolver = SiblingResolver(ctx, job.extras, job.tree, job.relPath?.substringBeforeLast('/', "")?.ifEmpty { null })
            val err = NativeBridge.nativeLoad(h, fd, job.size, job.name, resolver, false)
            if (err.isNotEmpty()) return false
            var frames = 0
            while (frames < 2000) {
                NativeBridge.nativeDraw(h, System.nanoTime())
                frames++
                if (NativeBridge.nativeStats(h)[5] == 0L) break
            }
            NativeBridge.nativeDraw(h, System.nanoTime())
            val buf = ByteBuffer.allocateDirect(s * s * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, s, s, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            ThumbnailStore.save(ctx, original.uri, ThumbnailStore.fromPixels(buf, s, s))
            return true
        } finally {
            NativeBridge.nativeReleaseGl(h)
            NativeBridge.nativeDestroy(h)
        }
    }
}
