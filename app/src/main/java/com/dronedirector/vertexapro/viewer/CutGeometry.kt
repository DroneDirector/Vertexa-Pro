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
