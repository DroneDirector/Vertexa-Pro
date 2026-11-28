package com.dronedirector.vertexapro.viewer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer

/**
 * On-device "tap to select" with MobileSAM (MIT licence, models by Acly on Hugging Face).
 * The two ONNX models (~45 MB) are downloaded once on first use; everything then runs offline.
 */
class Sam private constructor(private val env: OrtEnvironment, private val enc: OrtSession, private val dec: OrtSession) {

    class Embedding(val data: FloatArray, val w: Int, val h: Int, val scale: Float)

    /** Encodes an ARGB image (top-down) once; reuse the result for every tap on the same view. */
    fun encode(pixels: IntArray, w: Int, h: Int): Embedding {
        val scale = 1024f / maxOf(w, h)
        val rw = (w * scale).toInt().coerceIn(1, 1024)
        val rh = (h * scale).toInt().coerceIn(1, 1024)
        val src = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val bmp = Bitmap.createScaledBitmap(src, rw, rh, true)
        val px = IntArray(rw * rh)
        bmp.getPixels(px, 0, rw, 0, 0, rw, rh)
        if (bmp != src) bmp.recycle()
        src.recycle()
        val input = FloatBuffer.allocate(rw * rh * 3)
        for (p in px) {
            // background (transparent) becomes mid grey so the model sees a neutral backdrop
            if ((p ushr 24) < 128) { input.put(128f); input.put(128f); input.put(128f); continue }
            input.put(((p shr 16) and 255).toFloat())
            input.put(((p shr 8) and 255).toFloat())
            input.put((p and 255).toFloat())
        }
        input.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(rh.toLong(), rw.toLong(), 3)).use { t ->
            enc.run(mapOf("input_image" to t)).use { r ->
                val out = (r[0] as OnnxTensor).floatBuffer
                val arr = FloatArray(out.remaining())
                out.get(arr)
                return Embedding(arr, rw, rh, scale)
            }
        }
    }

    /**
     * Mask for the given points (x, y in the original image pixels; label 1 = include, 0 = exclude).
     * Returns a mask at the encoded (resized) size.
     */
    fun decode(e: Embedding, points: List<Triple<Float, Float, Int>>): BooleanArray {
        val n = points.size + 1
        val coords = FloatBuffer.allocate(n * 2)
        val labels = FloatBuffer.allocate(n)
        for ((x, y, l) in points) {
            coords.put(x * e.scale); coords.put(y * e.scale)
            labels.put(if (l == 1) 1f else 0f)
        }
        coords.put(0f); coords.put(0f); labels.put(-1f)  // padding point (no box)
        coords.rewind(); labels.rewind()
        val inputs = mapOf(
            "image_embeddings" to OnnxTensor.createTensor(env, FloatBuffer.wrap(e.data), longArrayOf(1, 256, 64, 64)),
            "point_coords" to OnnxTensor.createTensor(env, coords, longArrayOf(1, n.toLong(), 2)),
            "point_labels" to OnnxTensor.createTensor(env, labels, longArrayOf(1, n.toLong())),
            "mask_input" to OnnxTensor.createTensor(env, FloatBuffer.allocate(256 * 256), longArrayOf(1, 1, 256, 256)),
            "has_mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
            "orig_im_size" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(e.h.toFloat(), e.w.toFloat())), longArrayOf(2)),
        )
        try {
            dec.run(inputs).use { r ->
                val m = (r[0] as OnnxTensor).floatBuffer
                val mask = BooleanArray(e.w * e.h)
                for (i in mask.indices) if (m.hasRemaining()) mask[i] = m.get() > 0f
                return mask
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    companion object {
        private const val BASE = "https://huggingface.co/Acly/MobileSAM/resolve/main/"
        private val files = listOf("mobile_sam_image_encoder.onnx", "sam_mask_decoder_single.onnx")
        @Volatile private var instance: Sam? = null

        private fun dir(ctx: Context) = File(ctx.filesDir, "ai").apply { mkdirs() }
        fun isDownloaded(ctx: Context) = files.all { File(dir(ctx), it).let { f -> f.exists() && f.length() > 1_000_000 } }

        /** Downloads both models; [onProgress] gets 0..1. */
        fun download(ctx: Context, onProgress: (Float) -> Unit) {
            val total = 45_000_000f
            var done = 0L
            for (name in files) {
                val target = File(dir(ctx), name)
                if (target.exists() && target.length() > 1_000_000) { done += target.length(); continue }
                val tmp = File(dir(ctx), "$name.part")
                var url = URL(BASE + name)
                var conn: HttpURLConnection
                var hops = 0
                while (true) {
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "VertexaPro")
                    }
                    val code = conn.responseCode
                    if (code in 300..399 && hops < 8) {
                        url = URL(url, conn.getHeaderField("Location"))
                        conn.disconnect()
                        hops++
                        continue
                    }
                    if (code !in 200..299) error("Download failed ($code)")
                    break
                }
                conn.inputStream.use { inp ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            onProgress((done / total).coerceAtMost(0.99f))
                        }
                    }
                }
                conn.disconnect()
                if (!tmp.renameTo(target)) error("Couldn't save the AI model.")
            }
            onProgress(1f)
        }

        fun get(ctx: Context): Sam {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
                val enc = env.createSession(File(dir(ctx), files[0]).path, opts)
                val dec = env.createSession(File(dir(ctx), files[1]).path, opts)
                return Sam(env, enc, dec).also { instance = it }
            }
        }
    }
}
