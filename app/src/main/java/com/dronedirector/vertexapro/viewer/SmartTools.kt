package com.dronedirector.vertexapro.viewer

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Image helpers for the smart cut tools. They work on a render of the current view
 * (textures visible, nothing drawn on top), so painted panel lines can be followed.
 */
class ViewImage(val w: Int, val h: Int, val px: IntArray, val step: Int) {
    fun alpha(i: Int) = px[i] ushr 24
    fun r(i: Int) = (px[i] shr 16) and 255
    fun g(i: Int) = (px[i] shr 8) and 255
    fun b(i: Int) = px[i] and 255

    /** Screen pixel -> image pixel. */
    fun toImg(x: Float) = (x / step).toInt()
    fun toScreen(i: Float) = i * step + step / 2f
}

object SmartTools {

    /** RGBA from glReadPixels (bottom row first) to a top-down image, downsampled by [step]. */
    fun fromGl(buf: ByteBuffer, w: Int, h: Int, step: Int, screenScale: Int = 1): ViewImage {
        val ow = w / step
        val oh = h / step
        val px = IntArray(ow * oh)
        for (y in 0 until oh) {
            val sy = h - 1 - y * step
            for (x in 0 until ow) {
                val o = (sy * w + x * step) * 4
                val r = buf.get(o).toInt() and 255
                val g = buf.get(o + 1).toInt() and 255
                val b = buf.get(o + 2).toInt() and 255
                val a = buf.get(o + 3).toInt() and 255
                px[y * ow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ViewImage(ow, oh, px, step * screenScale)
    }

    /** Edge strength 0..1 (Sobel on brightness); background counts as a strong edge. */
    fun edges(img: ViewImage): FloatArray {
        val w = img.w
        val h = img.h
        val l = FloatArray(w * h) { i ->
            if (img.alpha(i) < 128) -1f else (0.299f * img.r(i) + 0.587f * img.g(i) + 0.114f * img.b(i)) / 255f
        }
        val e = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (l[i] < 0) { e[i] = 1f; continue }
            fun v(dx: Int, dy: Int): Float { val q = l[(y + dy) * w + x + dx]; return if (q < 0) l[i] else q }
            val gx = -v(-1, -1) - 2 * v(-1, 0) - v(-1, 1) + v(1, -1) + 2 * v(1, 0) + v(1, 1)
            val gy = -v(-1, -1) - 2 * v(0, -1) - v(1, -1) + v(-1, 1) + 2 * v(0, 1) + v(1, 1)
            e[i] = (sqrt(gx * gx + gy * gy) / 2f).coerceAtMost(1f)
        }
        return e
    }

    /** Strongest painted line or edge near (x, y) in screen pixels, or null. */
    fun nearestEdge(img: ViewImage, e: FloatArray, x: Float, y: Float, radiusPx: Float, threshold: Float = 0.18f): Pair<Float, Float>? {
        val cx = img.toImg(x)
        val cy = img.toImg(y)
        val r = (radiusPx / img.step).toInt().coerceAtLeast(1)
        var best = -1f
        var bx = -1
        var by = -1
        for (yy in (cy - r).coerceAtLeast(1)..(cy + r).coerceAtMost(img.h - 2)) {
            for (xx in (cx - r).coerceAtLeast(1)..(cx + r).coerceAtMost(img.w - 2)) {
                val i = yy * img.w + xx
                if (img.alpha(i) < 128) continue
                val d = hypot((xx - cx).toFloat(), (yy - cy).toFloat())
                if (d > r) continue
                val s = e[i] - 0.25f * d / r
                if (e[i] >= threshold && s > best) { best = s; bx = xx; by = yy }
            }
        }
        return if (bx < 0) null else img.toScreen(bx.toFloat()) to img.toScreen(by.toFloat())
    }

    /** Magic wand: grows from the seed over similar colour and stops at painted lines and the model's edge. */
    fun wand(img: ViewImage, e: FloatArray, sx: Float, sy: Float, tolerance: Float): BooleanArray? {
        val w = img.w
        val h = img.h
        val x0 = img.toImg(sx).coerceIn(0, w - 1)
        val y0 = img.toImg(sy).coerceIn(0, h - 1)
        val s = y0 * w + x0
        if (img.alpha(s) < 128) return null
        val sr = img.r(s); val sg = img.g(s); val sb = img.b(s)
        val tol = 18f + tolerance * 140f
        val edgeStop = 0.55f - tolerance * 0.35f
        val mask = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = s
        mask[s] = true
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            for (k in 0 until 4) {
                val nx = x + (if (k == 0) 1 else if (k == 1) -1 else 0)
                val ny = y + (if (k == 2) 1 else if (k == 3) -1 else 0)
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (mask[j] || img.alpha(j) < 128 || e[j] > edgeStop) continue
                val d = abs(img.r(j) - sr) + abs(img.g(j) - sg) + abs(img.b(j) - sb)
                if (d > tol) continue
                mask[j] = true
                queue[tail++] = j
            }
        }
        return mask
    }

    /** Keeps only the connected region of [mask] that contains (sx, sy) in image pixels. */
    fun component(mask: BooleanArray, w: Int, h: Int, sx: Int, sy: Int): BooleanArray {
        val out = BooleanArray(w * h)
        val s = sy.coerceIn(0, h - 1) * w + sx.coerceIn(0, w - 1)
        if (!mask[s]) return out
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = s
        out[s] = true
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            for (k in 0 until 4) {
                val nx = x + (if (k == 0) 1 else if (k == 1) -1 else 0)
                val ny = y + (if (k == 2) 1 else if (k == 3) -1 else 0)
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (out[j] || !mask[j]) continue
                out[j] = true
                queue[tail++] = j
            }
        }
        return out
    }

    /** Outer boundary of a mask (Moore neighbour tracing) as x, y pairs in image pixels. */
    fun contour(mask: BooleanArray, w: Int, h: Int): FloatArray? {
        var start = -1
        for (i in mask.indices) if (mask[i]) { start = i; break }
        if (start < 0) return null
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        fun m(x: Int, y: Int) = x in 0 until w && y in 0 until h && mask[y * w + x]
        val out = ArrayList<Float>()
        var x = start % w
        var y = start / w
        val sx = x
        val sy = y
        var d = 0
        var guard = 0
        do {
            out += x.toFloat(); out += y.toFloat()
            var moved = false
            for (k in 0 until 8) {
                val nd = (d + 6 + k) % 8
                val nx = x + dx[nd]
                val ny = y + dy[nd]
                if (m(nx, ny)) { x = nx; y = ny; d = nd; moved = true; break }
            }
            if (!moved) break
        } while ((x != sx || y != sy) && ++guard < 400_000)
        return if (out.size >= 6) out.toFloatArray() else null
    }

    /** Mask -> outline in screen pixels with a handful of key points. */
    fun maskToOutline(mask: BooleanArray, w: Int, h: Int, toScreen: (Float, Float) -> Pair<Float, Float>, tolPx: Float): FloatArray? {
        val c = contour(mask, w, h) ?: return null
        val scr = FloatArray(c.size)
        for (i in 0 until c.size / 2) {
            val (a, b) = toScreen(c[i * 2], c[i * 2 + 1])
            scr[i * 2] = a; scr[i * 2 + 1] = b
        }
        val simple = CutGeometry.keyPoints(scr, tolPx)
        return if (simple.size >= 6) simple else null
    }
}
