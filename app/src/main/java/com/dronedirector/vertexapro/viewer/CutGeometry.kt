package com.dronedirector.vertexapro.viewer

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Screen-space helpers for the cut tool's shapes. All arrays are flat x, y pairs. */
object CutGeometry {
    const val POINTS = 0
    const val CURVE = 1
    const val FREEHAND = 2
    const val RECTANGLE = 3
    const val ELLIPSE = 4
    const val FLAP = 5

    /** Closed Catmull-Rom spline through the points, sampled about every [stepPx] pixels. */
    fun smoothClosed(xy: FloatArray, stepPx: Float = 5f): FloatArray {
        val n = xy.size / 2
        if (n < 3) return xy
        val out = ArrayList<Float>()
        for (i in 0 until n) {
            val p0 = (i - 1 + n) % n
            val p1 = i
            val p2 = (i + 1) % n
            val p3 = (i + 2) % n
            val len = hypot(xy[p2 * 2] - xy[p1 * 2], xy[p2 * 2 + 1] - xy[p1 * 2 + 1])
            val steps = (len / stepPx).roundToInt().coerceIn(2, 200)
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                val t2 = t * t
                val t3 = t2 * t
                for (c in 0..1) {
                    val a = xy[p0 * 2 + c]
                    val b = xy[p1 * 2 + c]
                    val d = xy[p2 * 2 + c]
                    val e = xy[p3 * 2 + c]
                    out += 0.5f * ((2 * b) + (-a + d) * t + (2 * a - 5 * b + 4 * d - e) * t2 + (-a + 3 * b - 3 * d + e) * t3)
                }
            }
        }
        return out.toFloatArray()
    }

    /** Drops points closer than [minPx] to the previous one (keeps freehand strokes light). */
    fun simplify(xy: FloatArray, minPx: Float = 4f): FloatArray {
        if (xy.size < 4) return xy
        val out = arrayListOf(xy[0], xy[1])
        for (i in 1 until xy.size / 2) {
            if (hypot(xy[i * 2] - out[out.size - 2], xy[i * 2 + 1] - out[out.size - 1]) >= minPx) {
                out += xy[i * 2]; out += xy[i * 2 + 1]
            }
        }
        return out.toFloatArray()
    }

    fun rectangle(x0: Float, y0: Float, x1: Float, y1: Float) = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    fun ellipse(x0: Float, y0: Float, x1: Float, y1: Float, segments: Int = 96): FloatArray {
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        val rx = kotlin.math.abs(x1 - x0) / 2
        val ry = kotlin.math.abs(y1 - y0) / 2
        return FloatArray(segments * 2) { k ->
            val a = 2 * PI * (k / 2) / segments
            if (k % 2 == 0) (cx + rx * cos(a)).toFloat() else (cy + ry * sin(a)).toFloat()
        }
    }

    /** Offsets a closed polygon outward (d > 0) or inward (d < 0) by d pixels. */
    fun offset(xy: FloatArray, d: Float): FloatArray {
        val n = xy.size / 2
        if (n < 3) return xy
        var area = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1]
        }
        val s = if (area > 0) -1f else 1f  // screen y points down
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val p = (i - 1 + n) % n
            val q = (i + 1) % n
            var e1x = xy[i * 2] - xy[p * 2]; var e1y = xy[i * 2 + 1] - xy[p * 2 + 1]
            var e2x = xy[q * 2] - xy[i * 2]; var e2y = xy[q * 2 + 1] - xy[i * 2 + 1]
            val l1 = hypot(e1x, e1y).coerceAtLeast(1e-6f); val l2 = hypot(e2x, e2y).coerceAtLeast(1e-6f)
            e1x /= l1; e1y /= l1; e2x /= l2; e2y /= l2
            // outward normals of both edges, averaged (miter, limited)
            val n1x = e1y * s; val n1y = -e1x * s
            val n2x = e2y * s; val n2y = -e2x * s
            var mx = n1x + n2x; var my = n1y + n2y
            val ml = hypot(mx, my)
            if (ml < 1e-4f) { mx = n1x; my = n1y } else { mx /= ml; my /= ml }
            val cos = (mx * n1x + my * n1y).coerceAtLeast(0.35f)
            out[i * 2] = xy[i * 2] + mx * d / cos
            out[i * 2 + 1] = xy[i * 2 + 1] + my * d / cos
        }
        return out
    }

    /** Moves (x, y) so the line from (px, py) is horizontal, vertical or diagonal. */
    fun straighten(px: Float, py: Float, x: Float, y: Float): Pair<Float, Float> {
        val dx = x - px
        val dy = y - py
        val len = hypot(dx, dy)
        if (len < 1f) return x to y
        val ang = atan2(dy, dx)
        val snapped = (ang / (PI / 4)).roundToInt() * (PI / 4)
        return (px + len * cos(snapped)).toFloat() to (py + len * sin(snapped)).toFloat()
    }
}
