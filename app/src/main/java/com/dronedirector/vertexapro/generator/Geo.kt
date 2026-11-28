package com.dronedirector.vertexapro.generator

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

data class V(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V(x * s, y * s, z * s)
    fun dot(o: V) = x * o.x + y * o.y + z * o.z
    fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(dot(this))
    fun norm(): V { val l = len(); return if (l < 1e-12) V(0.0, 1.0, 0.0) else this * (1.0 / l) }
}

fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
fun lerpV(a: V, b: V, t: Double) = a + (b - a) * t
fun rad(deg: Double) = deg * PI / 180.0

class Rgb(val r: Double, val g: Double, val b: Double)

class FloatList(cap: Int = 1024) {
    var a = FloatArray(cap); var n = 0
    fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
}
class IntList(cap: Int = 1024) {
    var a = IntArray(cap); var n = 0
    fun add(v: Int) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
}

/** One named part: positions with a colour each, and triangles. Built in closed pieces that are turned outward. */
class Part(val name: String, var color: Rgb) {
    val pos = FloatList(4096)
    val col = FloatList(4096)
    val idx = IntList(8192)
    private var pieceStart = 0
    val vertCount get() = pos.n / 3
    val triCount get() = idx.n / 3

    fun v(p: V): Int {
        pos.add(p.x.toFloat()); pos.add(p.y.toFloat()); pos.add(p.z.toFloat())
        col.add(color.r.toFloat()); col.add(color.g.toFloat()); col.add(color.b.toFloat())
        return vertCount - 1
    }
    fun tri(a: Int, b: Int, c: Int) { idx.add(a); idx.add(b); idx.add(c) }
    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    fun begin() { pieceStart = idx.n }
    /** Closed piece: flips it if it faces inward (negative volume). */
    fun end() {
        var vol = 0.0
        var cx = 0.0; var cy = 0.0; var cz = 0.0; var cnt = 0
        for (i in pieceStart until idx.n) { val k = idx.a[i] * 3; cx += pos.a[k]; cy += pos.a[k + 1]; cz += pos.a[k + 2]; cnt++ }
        if (cnt == 0) return
        cx /= cnt; cy /= cnt; cz /= cnt
        var i = pieceStart
        while (i + 2 < idx.n) {
            val a = idx.a[i] * 3; val b = idx.a[i + 1] * 3; val c = idx.a[i + 2] * 3
            val ax = pos.a[a] - cx; val ay = pos.a[a + 1] - cy; val az = pos.a[a + 2] - cz
            val bx = pos.a[b] - cx; val by = pos.a[b + 1] - cy; val bz = pos.a[b + 2] - cz
            val qx = pos.a[c] - cx; val qy = pos.a[c + 1] - cy; val qz = pos.a[c + 2] - cz
            vol += ax * (by * qz - bz * qy) - ay * (bx * qz - bz * qx) + az * (bx * qy - by * qx)
            i += 3
        }
        if (vol < 0) {
            var j = pieceStart
            while (j + 2 < idx.n) { val t = idx.a[j + 1]; idx.a[j + 1] = idx.a[j + 2]; idx.a[j + 2] = t; j += 3 }
        }
    }

    /** Closed rings joined in order; open ends get flat caps (own vertices, so the edge stays sharp). */
    fun loft(rings: List<List<V>>, capStart: Boolean = true, capEnd: Boolean = true, wrap: Boolean = false) {
        if (rings.size < 2) return
        begin()
        val n = rings[0].size
        val ids = rings.map { r -> r.map { v(it) } }
        val last = if (wrap) rings.size else rings.size - 1
        for (i in 0 until last) {
            val a = ids[i]; val b = ids[(i + 1) % rings.size]
            for (j in 0 until n) { val j2 = (j + 1) % n; quad(a[j], a[j2], b[j2], b[j]) }
        }
        if (!wrap) {
            if (capStart) cap(rings.first(), reverse = true)
            if (capEnd) cap(rings.last())
        }
        end()
    }

    fun cap(ring: List<V>, reverse: Boolean = false) {
        var c = V(0.0, 0.0, 0.0)
        ring.forEach { c = c + it }
        c = c * (1.0 / ring.size)
        val ci = v(c)
        val ids = ring.map { v(it) }
        for (j in ids.indices) if (reverse) tri(ci, ids[(j + 1) % ids.size], ids[j]) else tri(ci, ids[j], ids[(j + 1) % ids.size])
    }

    /** Solid of revolution of a closed (axial, radial) profile around an axis. */
    fun revolve(center: V, axis: V, profile: List<Pair<Double, Double>>, seg: Int, squashBelow: Double = 1.0) {
        val ax = axis.norm()
        val ref = if (abs(ax.y) < 0.9) V(0.0, 1.0, 0.0) else V(1.0, 0.0, 0.0)
        val u = ax.cross(ref).norm()
        val w = ax.cross(u).norm()
        val rings = profile.map { (a, r) ->
            (0 until seg).map { k ->
                val t = 2 * PI * k / seg
                var du = u * (cos(t) * r)
                var dw = w * (sin(t) * r)
                var p = center + ax * a + du + dw
                if (squashBelow != 1.0 && p.y < center.y) p = V(p.x, center.y + (p.y - center.y) * squashBelow, p.z)
                p
            }
        }
        loft(rings, wrap = true)
    }

    fun cylinder(a: V, b: V, r: Double, seg: Int = 16) {
        val ax = b - a
        val l = ax.len()
        revolveOpen(a, ax, listOf(0.0 to r, l to r), seg)
    }

    /** Open (axial, radial) profile revolved and capped at both ends. */
    fun revolveOpen(center: V, axis: V, profile: List<Pair<Double, Double>>, seg: Int) {
        val ax = axis.norm()
        val ref = if (abs(ax.y) < 0.9) V(0.0, 1.0, 0.0) else V(1.0, 0.0, 0.0)
        val u = ax.cross(ref).norm()
        val w = ax.cross(u).norm()
        val rings = profile.map { (a, r) ->
            (0 until seg).map { k -> val t = 2 * PI * k / seg; center + ax * a + u * (cos(t) * maxOf(r, 1e-4)) + w * (sin(t) * maxOf(r, 1e-4)) }
        }
        loft(rings)
    }

    fun sphere(c: V, r: Double, seg: Int = 12) {
        val prof = (0..seg / 2).map { k -> val t = PI * k / (seg / 2); (-cos(t) * r) to (sin(t) * r) }
        revolveOpen(c, V(0.0, 1.0, 0.0), prof, seg)
    }

    /** Box from a centre and three half-axes. */
    fun box(c: V, ex: V, ey: V, ez: V) {
        begin()
        val p = Array(8) { i -> c + ex * (if (i and 1 != 0) 1.0 else -1.0) + ey * (if (i and 2 != 0) 1.0 else -1.0) + ez * (if (i and 4 != 0) 1.0 else -1.0) }
        val faces = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5), intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3))
        for (f in faces) { val ids = f.map { v(p[it]) }; quad(ids[0], ids[1], ids[2], ids[3]) }
        end()
    }
}

/** NACA-style airfoil: thickness t, camber m at 40 %. Returns the closed outline of the chord span [c0, c1]. */
object Airfoil {
    fun thick(x: Double, t: Double) = 5 * t * (0.2969 * sqrt(maxOf(x, 0.0)) - 0.1260 * x - 0.3516 * x * x + 0.2843 * x * x * x - 0.1036 * x * x * x * x)
    fun camber(x: Double, m: Double) = if (x < 0.4) m / 0.16 * (0.8 * x - x * x) else m / 0.36 * (0.2 + 0.8 * x - x * x)
    fun upper(x: Double, t: Double, m: Double) = camber(x, m) + thick(x, t)
    fun lower(x: Double, t: Double, m: Double) = camber(x, m) - thick(x, t)

    /** (x/c, y/c) loop: upper surface c0→c1, then lower c1→c0. Cosine spacing for round leading edges. */
    fun section(c0: Double, c1: Double, n: Int, t: Double, m: Double): List<Pair<Double, Double>> {
        val xs = (0 until n).map { i -> val s = i.toDouble() / (n - 1); c0 + (c1 - c0) * (if (c0 < 0.02) (1 - cos(s * PI / 2)) else s) }
        val up = xs.map { it to upper(it, t, m) }
        val lo = xs.reversed().map { it to lower(it, t, m) }
        // a leading edge at 0 shares one point; elsewhere the front face is flat
        return if (c0 < 1e-6) up + lo.dropLast(1) else up + lo
    }
}

/** Rounded rectangle outline (superellipse), unit-free: half sizes a, b. */
fun roundedRect(a: Double, b: Double, n: Double, m: Int): List<Pair<Double, Double>> = (0 until m).map { k ->
    val t = 2 * PI * k / m
    val c = cos(t); val s = sin(t)
    (a * sign(c) * abs(c).pow(2 / n)) to (b * sign(s) * abs(s).pow(2 / n))
}

/** Streams parts into an OBJ file with per-vertex colours (v x y z r g b). */
class ObjWriter(file: File) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
    private val buf = ByteArray(96)
    private var vBase = 0
    var triangles = 0L
        private set

    fun line(s: String) { out.write(s.toByteArray(Charsets.UTF_8)); out.write('\n'.code) }

    private var bp = 0
    private fun put(c: Char) { buf[bp++] = c.code.toByte() }
    private fun num(v: Float, decimals: Int) {
        var x = v.toDouble()
        if (x.isNaN() || x.isInfinite()) x = 0.0
        if (x < 0) { put('-'); x = -x }
        val scale = if (decimals == 4) 10000L else 1000L
        val q = Math.round(x * scale)
        val ip = q / scale
        var fp = q % scale
        val s = ip.toString(); for (ch in s) put(ch)
        put('.')
        var div = scale / 10
        while (div > 0) { put(('0' + (fp / div).toInt())); fp %= div; div /= 10 }
    }

    fun part(p: Part) {
        if (p.triCount == 0) return
        line("o ${p.name}")
        for (i in 0 until p.vertCount) {
            bp = 0
            put('v'); put(' ')
            num(p.pos.a[i * 3], 4); put(' '); num(p.pos.a[i * 3 + 1], 4); put(' '); num(p.pos.a[i * 3 + 2], 4); put(' ')
            num(p.col.a[i * 3], 3); put(' '); num(p.col.a[i * 3 + 1], 3); put(' '); num(p.col.a[i * 3 + 2], 3)
            put('\n')
            out.write(buf, 0, bp)
        }
        val sb = StringBuilder(48)
        for (t in 0 until p.triCount) {
            sb.setLength(0)
            sb.append("f ").append(p.idx.a[t * 3] + 1 + vBase).append(' ').append(p.idx.a[t * 3 + 1] + 1 + vBase).append(' ')
                .append(p.idx.a[t * 3 + 2] + 1 + vBase).append('\n')
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        }
        vBase += p.vertCount
        triangles += p.triCount
    }

    override fun close() { out.flush(); out.close() }
}
