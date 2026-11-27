package com.dronedirector.vertexapro.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Snapshots and turntable videos. */
object Capture {

    fun baseName(modelName: String): String {
        val b = modelName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).trim('_')
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "${b.ifEmpty { "model" }}-$stamp"
    }

    /** RGBA from glReadPixels (bottom row first, supersampled) → upright bitmap at the on-screen size. */
    fun fromGl(buf: ByteBuffer, w: Int, h: Int, outW: Int, outH: Int): Bitmap {
        buf.rewind()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        val m = Matrix().apply {
            preScale(1f, -1f)
            postScale(outW.toFloat() / w, outH.toFloat() / h)
        }
        val out = Bitmap.createBitmap(raw, 0, 0, w, h, m, true)
        if (out != raw) raw.recycle()
        return out
    }

    fun savePng(ctx: Context, bmp: Bitmap, base: String): Uri {
        val name = "$base.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Vertexa Pro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No storage")
            cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            return uri
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Vertexa Pro").apply { mkdirs() }
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    fun saveVideo(ctx: Context, src: File, base: String): Uri {
        val name = "$base.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Vertexa Pro")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("No storage")
            cr.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            src.delete()
            return uri
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Vertexa Pro").apply { mkdirs() }
        val f = File(dir, name)
        src.copyTo(f, overwrite = true)
        src.delete()
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }

    /**
     * Renders one full turn of the model straight into a hardware H.264 encoder.
     * Must run on the viewer's GL thread (it borrows the viewer's GL context).
     */
    fun recordTurntable(
        handle: Long,
        out: File,
        size: Int = 1080,
        seconds: Int = 6,
        fps: Int = 30,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val dpy = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val cfgId = IntArray(1)
        EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, cfgId, 0)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_CONFIG_ID, cfgId[0], EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0) || n[0] == 0) {
            return false
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size, size).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 14_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var surface = EGL14.EGL_NO_SURFACE
        var began = false
        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            codec.start()
            surface = EGL14.eglCreateWindowSurface(dpy, cfgs[0], input, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false
            out.delete()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var started = false
            val info = MediaCodec.BufferInfo()
            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!end || ++idle > 300) return
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        idx >= 0 -> {
                            val buf = codec.getOutputBuffer(idx)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && started && buf != null) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }
            NativeBridge.nativeBeginTurntable(handle)
            began = true
            val frames = seconds * fps
            for (i in 0 until frames) {
                EGL14.eglMakeCurrent(dpy, surface, surface, ctx)
                NativeBridge.nativeTurntableFrame(handle, Math.PI * 2 * i / frames, size, size)
                EGLExt.eglPresentationTimeANDROID(dpy, surface, i * 1_000_000_000L / fps)
                EGL14.eglSwapBuffers(dpy, surface)
                drain(false)
                if (i % 6 == 0) onProgress(i.toFloat() / frames)
            }
            codec.signalEndOfInputStream()
            drain(true)
            onProgress(1f)
            return started
        } catch (e: Exception) {
            return false
        } finally {
            if (began) NativeBridge.nativeEndTurntable(handle)
            EGL14.eglMakeCurrent(dpy, draw, read, ctx)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surface)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
