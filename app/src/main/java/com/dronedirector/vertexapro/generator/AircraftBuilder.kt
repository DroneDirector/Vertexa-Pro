package com.dronedirector.vertexapro.generator

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

data class GenOptions(val detail: Int = 1, val gearDown: Boolean = true, val tail: Rgb? = null)

/**
 * Builds an aircraft from its real dimensions, every moving or separate piece its own part:
 * fuselage, wings, slats, flaps, ailerons, spoilers, stabilisers, elevators, fin, rudder, engines,
 * pylons, fans, landing gear, wheels, gear doors, passenger and cargo doors, windows, lights, probes.
 */
class AircraftBuilder(private val s: AircraftSpec, private val o: GenOptions) {
    private val white = Rgb(0.93, 0.94, 0.95)
    private val wingGrey = Rgb(0.80, 0.81, 0.83)
    private val surfGrey = Rgb(0.73, 0.74, 0.76)
    private val walkway = Rgb(0.62, 0.63, 0.65)
    private val glass = Rgb(0.06, 0.08, 0.12)
    private val metal = Rgb(0.60, 0.62, 0.65)
    private val darkMetal = Rgb(0.22, 0.23, 0.25)
    private val tyre = Rgb(0.06, 0.06, 0.07)
    private val gearWhite = Rgb(0.86, 0.87, 0.88)
    private val tail = o.tail ?: s.defaultTail
    private val d = o.detail.coerceIn(1, 3)

    private val parts = ArrayList<Part>()
    private fun part(name: String, c: Rgb) = Part(name, c).also { parts += it }

    // ------------------------------------------------------------------ fuselage shape
    /** Crown, keel and half-width of the fuselage at x. */
    private fun fus(x: Double): Triple<Double, Double, Double> {
        val h = s.height; val w = s.width
        return when {
            x < s.noseLen -> {
                val t = (x / s.noseLen).coerceIn(0.0, 1.0)
                val sTop = (1 - (1 - t).pow(2.6)).pow(0.55)
                val sBot = (1 - (1 - t).pow(2.0)).pow(0.5)
                val sW = (1 - (1 - t).pow(2.2)).pow(0.5)
                Triple(lerp(s.noseDroop, h / 2, sTop), lerp(s.noseDroop, -h / 2, sBot), w / 2 * sW)
            }
            x > s.length - s.tailLen -> {
                val t = ((x - (s.length - s.tailLen)) / s.tailLen).coerceIn(0.0, 1.0)
                Triple(h / 2 - h * 0.30 * t.pow(1.6), -h / 2 + h * 0.62 * t.pow(1.15), w / 2 * (1 - 0.86 * t.pow(1.25)))
            }
            else -> Triple(h / 2, -h / 2, w / 2)
        }
    }

    private fun fusPoint(x: Double, th: Double): V {
        val (top, bot, hw) = fus(x)
        val cy = (top + bot) / 2; val hy = (top - bot) / 2
        val c = cos(th); val sn = sin(th)
        val e = 2.0 / 2.15
        return V(x, cy + hy * sign(sn) * abs(sn).pow(e), hw * sign(c) * abs(c).pow(e))
    }

    /** Angle on the fuselage where the side is at height y (left side; mirror z for right). */
    private fun thetaAt(x: Double, y: Double): Double {
        val (top, bot, _) = fus(x)
        val cy = (top + bot) / 2; val hy = (top - bot) / 2
        val q = ((y - cy) / hy).coerceIn(-0.999, 0.999)
        // invert the superellipse: |sin|^e = q
        val e = 2.0 / 2.15
        return asin(sign(q) * abs(q).pow(1 / e))
    }

    private fun buildFuselage() {
        val p = part("Fuselage", white)
        val seg = 96 * d
        val n = 170 * d
        val rings = ArrayList<List<V>>()
        for (i in 0..n) {
            val t = i.toDouble() / n
            // more rings at the nose and the tail
            val x = 0.02 + (s.length - 0.05) * (t - 0.5 * sin(2 * PI * t) / (2 * PI) * 0.8)
            rings += (0 until seg).map { k -> fusPoint(x, 2 * PI * k / seg) }
        }
        p.loft(rings)
        // radome tint and belly fairing
        val belly = part("Wing-body fairing", Rgb(0.88, 0.89, 0.90))
        val x0 = s.wingX - 1.2
        val x1 = s.wingX + s.rootChord + 2.0
        if (s.wingY < 0) {
            val br = (0..24 * d).map { i ->
                val t = i.toDouble() / (24 * d)
                val x = lerp(x0, x1, t)
                val f = sin(PI * t).pow(0.45)
                val (_, bot, hw) = fus(x)
                (0 until 48 * d).map { k ->
                    val a = 2 * PI * k / (48 * d)
                    V(x, bot + 0.55 + (0.62 * f + 0.02) * sin(a), (hw * 1.13 * f + 0.02) * cos(a))
                }
            }
            belly.loft(br)
        }
        // APU exhaust
        val apu = part("APU exhaust", darkMetal)
        val (t, b, _) = fus(s.length - 0.05)
        apu.cylinder(V(s.length - 0.25, (t + b) / 2, 0.0), V(s.length + 0.12, (t + b) / 2 + 0.03, 0.0), 0.16, 20)
    }

    /** A thin rounded panel laid on the fuselage surface (window, door), as its own solid. */
    private fun surfacePanel(p: Part, xc: Double, yc: Double, left: Boolean, w: Double, h: Double, round: Double, off: Double, thick: Double) {
        val th0 = thetaAt(xc, yc)
        val (top, bot, _) = fus(xc)
        val hy = (top - bot) / 2
        fun sp(u: Double, v: Double, o: Double): V {
            val x = xc + u
            val th = th0 + v / hy
            val a = fusPoint(x, th)
            val ax = fusPoint(x + 0.01, th) - fusPoint(x - 0.01, th)
            val at = fusPoint(x, th + 0.005) - fusPoint(x, th - 0.005)
            var nrm = ax.cross(at).norm()
            val (t2, b2, _) = fus(x)
            val out = V(0.0, a.y - (t2 + b2) / 2, a.z)
            if (nrm.dot(out) < 0) nrm = nrm * -1.0
            val q = a + nrm * o
            return if (left) q else V(q.x, q.y, -q.z)
        }
        val m = 28
        val outline = roundedRect(w / 2, h / 2, round, m)
        val k = 3
        p.begin()
        for (layer in 0..1) {
            val o = if (layer == 0) off else off - thick
            val c = p.v(sp(0.0, 0.0, o))
            var prev: List<Int>? = null
            for (r in 1..k) {
                val f = r.toDouble() / k
                val ids = outline.map { (u, v) -> p.v(sp(u * f, v * f, o)) }
                for (j in 0 until m) {
                    val j2 = (j + 1) % m
                    if (layer == 0) {
                        if (prev == null) p.tri(c, ids[j], ids[j2]) else p.quad(prev[j], ids[j], ids[j2], prev[j2])
                    } else {
                        if (prev == null) p.tri(c, ids[j2], ids[j]) else p.quad(prev[j], prev[j2], ids[j2], ids[j])
                    }
                }
                prev = ids
            }
        }
        // rim
        val top0 = outline.map { (u, v) -> p.v(sp(u, v, off)) }
        val bot0 = outline.map { (u, v) -> p.v(sp(u, v, off - thick)) }
        for (j in 0 until m) { val j2 = (j + 1) % m; p.quad(top0[j2], top0[j], bot0[j], bot0[j2]) }
        p.end()
    }

    private fun buildOpenings() {
        // passenger and cargo doors, overwing exits
        for (dr in s.doors) {
            for (left in listOf(true, false)) {
                if (left && !dr.left || !left && !dr.right) continue
                val side = if (left) "L" else "R"
                val p = part("${dr.name} $side", Rgb(0.89, 0.90, 0.91))
                surfacePanel(p, dr.x, dr.yCenter, left, dr.w, dr.h, if (dr.cargo) 10.0 else 6.0, 0.012, 0.04)
                if (!dr.cargo && dr.h > 1.5) {
                    val wp = part("${dr.name} $side window", glass)
                    surfacePanel(wp, dr.x, dr.yCenter + dr.h * 0.18, left, 0.2, 0.24, 3.5, 0.02, 0.012)
                }
            }
        }
        // cabin windows: one part each, skipping doors and exits
        for (left in listOf(true, false)) {
            var x = s.windowFrom
            var n = 1
            val side = if (left) "L" else "R"
            while (x <= s.windowTo) {
                val blocked = s.doors.any { dr -> !dr.cargo && (if (left) dr.left else dr.right) && abs(dr.x - x) < dr.w / 2 + 0.25 }
                if (!blocked) {
                    val p = part("Window $side %02d".format(n), glass)
                    surfacePanel(p, x, s.windowY, left, 0.23, 0.33, 3.2, 0.01, 0.025)
                    n++
                }
                x += s.windowPitch
            }
        }
        // flight deck windows
        val nl = s.noseLen
        val cockpit = listOf(Triple(0.44, 0.78, 0.62), Triple(0.56, 0.62, 0.55), Triple(0.65, 0.50, 0.45))
        for (left in listOf(true, false)) {
            cockpit.forEachIndexed { i, (fx, w, h) ->
                val x = nl * fx
                val (top, bot, _) = fus(x)
                val y = lerp((top + bot) / 2, top, if (i == 0) 0.72 else 0.62)
                val p = part("Cockpit window ${if (left) "L" else "R"} ${i + 1}", glass)
                surfacePanel(p, x, y, left, w, h, 5.0, 0.012, 0.03)
            }
        }
    }

    // ------------------------------------------------------------------ wing
    private val semi get() = s.semiSpan
    private fun wingLE(z: Double) = s.wingX + tan(rad(s.sweepLE)) * z
    private fun chordAt(z: Double): Double {
        val eta = (z / semi).coerceIn(0.0, 1.0)
        return if (s.kinkEta > 0 && eta < s.kinkEta) lerp(s.rootChord, s.kinkChord, eta / s.kinkEta)
        else lerp(s.kinkChord, s.tipChord, (eta - s.kinkEta) / (1 - s.kinkEta))
    }
    private fun wingYAt(z: Double) = s.wingY + tan(rad(s.dihedral)) * z
    private fun tAt(z: Double) = lerp(s.tRoot, s.tTip, (z / semi).coerceIn(0.0, 1.0))
    private val camber = 0.018

    private fun wingPoint(z: Double, xc: Double, yc: Double, side: Double): V {
        val c = chordAt(z)
        return V(wingLE(z) + xc * c, wingYAt(z) + yc * c, side * z)
    }

    /** Lofted piece of the wing between two span shares and two chord shares. */
    private fun wingPiece(p: Part, eta0: Double, eta1: Double, c0: Double, c1: Double, side: Double, nChord: Int = 26 * d) {
        val gap = 0.012
        val z0 = eta0 * semi + gap; val z1 = eta1 * semi - gap
        if (z1 <= z0) return
        val steps = max(2, ((eta1 - eta0) * 40 * d).toInt())
        val rings = (0..steps).map { i ->
            val z = lerp(z0, z1, i.toDouble() / steps)
            Airfoil.section(c0, c1, nChord, tAt(z), camber).map { (x, y) -> wingPoint(z, x, y, side) }
        }
        p.loft(rings)
    }

    private fun covers(list: List<SurfaceSpec>, eta: Double) = list.firstOrNull { eta > it.eta0 && eta < it.eta1 }

    private fun buildWing(left: Boolean) {
        val side = if (left) 1.0 else -1.0
        val sn = if (left) "L" else "R"
        val te = s.flaps + s.ailerons
        val wing = part("Wing $sn", wingGrey)
        // main wing box: split wherever a slat or a trailing-edge surface starts or ends
        val cuts = (listOf(0.0, 1.0) + (s.slats + te).flatMap { listOf(it.eta0, it.eta1) }).distinct().sorted()
        for (i in 0 until cuts.size - 1) {
            val e0 = cuts[i]; val e1 = cuts[i + 1]
            val mid = (e0 + e1) / 2
            val front = covers(s.slats, mid)?.c1?.plus(0.004) ?: 0.0
            val back = covers(te, mid)?.c0?.minus(0.004) ?: 1.0
            // pieces of the same part may meet without a gap
            val z0 = e0 * semi; val z1 = e1 * semi
            val steps = max(2, ((e1 - e0) * 40 * d).toInt())
            val rings = (0..steps).map { k ->
                val z = lerp(z0, z1, k.toDouble() / steps)
                Airfoil.section(front, back, 26 * d, tAt(z), camber).map { (x, y) -> wingPoint(z, x, y, side) }
            }
            wing.loft(rings)
        }
        // slats
        for (sl in s.slats) for (k in 0 until sl.pieces) {
            val e0 = lerp(sl.eta0, sl.eta1, k.toDouble() / sl.pieces)
            val e1 = lerp(sl.eta0, sl.eta1, (k + 1.0) / sl.pieces)
            val p = part("Slat $sn ${slatNo(sl, k)}", surfGrey)
            wingPiece(p, e0, e1, 0.0, sl.c1 - 0.004, side)
        }
        // flaps and ailerons
        for (f in te) {
            val p = part("${f.name} $sn", surfGrey)
            wingPiece(p, f.eta0, f.eta1, f.c0 + 0.004, 1.0, side)
        }
        // spoilers: thin panels on the upper skin in front of the flaps
        for (sp in s.spoilers) for (k in 0 until sp.pieces) {
            val e0 = lerp(sp.eta0, sp.eta1, k.toDouble() / sp.pieces)
            val e1 = lerp(sp.eta0, sp.eta1, (k + 1.0) / sp.pieces)
            val p = part("Spoiler $sn ${k + 1}", walkway)
            val z0 = e0 * semi + 0.015; val z1 = e1 * semi - 0.015
            val rings = (0..4).map { i ->
                val z = lerp(z0, z1, i / 4.0)
                val t = tAt(z)
                val xs = (0..10).map { j -> lerp(sp.c0, sp.c1, j / 10.0) }
                val top = xs.map { x -> wingPoint(z, x, Airfoil.upper(x, t, camber) + 0.0016, side) }
                val bot = xs.reversed().map { x -> wingPoint(z, x, Airfoil.upper(x, t, camber) - 0.004, side) }
                top + bot
            }
            p.loft(rings)
        }
        // flap track fairings under the flaps
        val fair = s.flaps.flatMap { f -> if (f.eta1 - f.eta0 > 0.3) listOf(lerp(f.eta0, f.eta1, 0.3), lerp(f.eta0, f.eta1, 0.75)) else listOf((f.eta0 + f.eta1) / 2) }
        if (s.wingY < 0) fair.forEachIndexed { i, eta ->
            val z = eta * semi
            val c = chordAt(z)
            val p = part("Flap track fairing $sn ${i + 1}", wingGrey)
            val x0 = wingLE(z) + 0.45 * c
            val len = 0.55 * c + 1.1
            val y = wingYAt(z) + Airfoil.lower(0.6, tAt(z), camber) * c - 0.12
            val prof = (0..14 * d).map { k ->
                val u = k.toDouble() / (14 * d)
                (u * len) to (0.06 + 0.20 * sin(PI * u.pow(0.7)).pow(0.9))
            }
            p.revolveOpen(V(x0, y, side * z), V(1.0, -0.02, 0.0), prof, 16)
        }
        // static dischargers on the ailerons and the tip
        val sd = part("Static dischargers $sn", darkMetal)
        for (a in s.ailerons) for (k in 0..2) {
            val z = lerp(a.eta0, a.eta1, 0.2 + 0.3 * k) * semi
            val tePt = wingPoint(z, 1.0, 0.0, side)
            sd.cylinder(tePt, tePt + V(0.28, 0.0, 0.0), 0.012, 6)
        }
        buildWingtip(left)
        // navigation light
        val nav = part("Navigation light $sn", if (left) Rgb(0.9, 0.1, 0.1) else Rgb(0.1, 0.8, 0.3))
        nav.sphere(wingPoint(semi - 0.05, 0.06, 0.0, side), 0.07, 10)
    }

    private fun slatNo(sl: SurfaceSpec, k: Int): Int {
        var n = 0
        for (o in s.slats) { if (o === sl) return n + k + 1; n += o.pieces }
        return k + 1
    }

    private fun buildWingtip(left: Boolean) {
        val side = if (left) 1.0 else -1.0
        val sn = if (left) "L" else "R"
        val tipZ = semi
        val c0 = chordAt(tipZ)
        val le0 = wingLE(tipZ)
        val y0 = wingYAt(tipZ)
        fun upDevice(name: String, height: Double, radius: Double, down: Boolean) {
            val p = part(name, wingGrey)
            val steps = 22 * d
            val total = PI / 2 * radius + max(0.0, height - radius)
            val rings = (0..steps).map { i ->
                val s1 = i.toDouble() / steps
                val arc = s1 * total
                val bendLen = PI / 2 * radius
                val (dz, dy, ang) = if (arc < bendLen) {
                    val a = arc / radius
                    Triple(radius * sin(a), radius * (1 - cos(a)), a)
                } else Triple(radius, radius + (arc - bendLen), PI / 2)
                val sy = if (down) -1.0 else 1.0
                val chord = lerp(c0, c0 * 0.35, s1.pow(0.8))
                val le = le0 + tan(rad(if (down) 45.0 else 55.0)) * arc + (c0 - chord) * 0.15
                val thick = V(0.0, cos(ang) * sy, -sin(ang)).let { if (down) V(0.0, it.y * -1.0, it.z) else it }
                Airfoil.section(0.0, 1.0, 22 * d, 0.09, 0.0).map { (x, y) ->
                    val base = V(le + x * chord, y0 + sy * dy, side * (tipZ + dz))
                    base + V(0.0, thick.y * y * chord, side * thick.z * y * chord)
                }
            }
            p.loft(rings)
        }
        when (s.tip) {
            Tip.SHARKLET -> upDevice("Sharklet $sn", s.tipHeight, 0.85, false)
            Tip.WINGLET -> upDevice("Winglet $sn", s.tipHeight, 1.3, false)
            Tip.SCIMITAR -> { upDevice("Winglet $sn", s.tipHeight, 1.1, false); upDevice("Winglet lower $sn", 0.95, 0.4, true) }
            Tip.FENCE -> {
                val p = part("Wingtip fence $sn", wingGrey)
                val rings = (0..6).map { i ->
                    val h = lerp(-0.42, 0.5, i / 6.0)
                    val ch = c0 * (0.75 - 0.35 * abs(h))
                    val le = le0 + c0 * 0.15 + abs(h) * 0.5
                    Airfoil.section(0.0, 1.0, 16, 0.08, 0.0).map { (x, y) -> V(le + x * ch, y0 + h, side * (tipZ + 0.02 + y * ch)) }
                }
                p.loft(rings)
            }
            Tip.PLAIN -> {}
        }
    }

    // ------------------------------------------------------------------ tail
    private fun buildTail() {
        val finBaseX = s.finX
        val finBaseY = fus(finBaseX + s.finRoot * 0.4).first - 0.25
        val finTopY = finBaseY + s.finHeight
        fun finLE(y: Double) = finBaseX + tan(rad(s.finSweep)) * (y - finBaseY)
        fun finChord(y: Double) = lerp(s.finRoot, s.finTip, ((y - finBaseY) / s.finHeight).coerceIn(0.0, 1.0))
        fun finPiece(p: Part, c0: Double, c1: Double, y0: Double, y1: Double) {
            val steps = 18 * d
            val rings = (0..steps).map { i ->
                val y = lerp(y0, y1, i.toDouble() / steps)
                val ch = finChord(y)
                Airfoil.section(c0, c1, 22 * d, 0.11, 0.0).map { (x, t) -> V(finLE(y) + x * ch, y, t * ch) }
            }
            p.loft(rings)
        }
        finPiece(part("Vertical stabilizer", tail), 0.0, 0.69, finBaseY, finTopY)
        finPiece(part("Rudder", tail), 0.71, 1.0, finBaseY + 0.35, finTopY - 0.15)
        // horizontal stabiliser: on the tail cone, or on top of the fin (T-tail)
        val stabRootX = if (s.tTail) finLE(finTopY) + 0.1 else s.stabX
        val stabY0 = if (s.tTail) finTopY - 0.05 else s.stabY
        val stabSemi = s.stabSemi
        for (left in listOf(true, false)) {
            val side = if (left) 1.0 else -1.0
            val sn = if (left) "L" else "R"
            fun stabPiece(p: Part, c0: Double, c1: Double, e0: Double, e1: Double) {
                val steps = 16 * d
                val rings = (0..steps).map { i ->
                    val z = lerp(e0, e1, i.toDouble() / steps) * stabSemi
                    val ch = lerp(s.stabRoot, s.stabTip, z / stabSemi)
                    val le = stabRootX + tan(rad(s.stabSweep)) * z
                    val y = stabY0 + tan(rad(s.stabDihedral)) * z
                    Airfoil.section(c0, c1, 20 * d, 0.10, 0.0).map { (x, t) -> V(le + x * ch, y + t * ch, side * z) }
                }
                p.loft(rings)
            }
            stabPiece(part("Horizontal stabilizer $sn", wingGrey), 0.0, 0.69, 0.0, 1.0)
            stabPiece(part("Elevator $sn", surfGrey), 0.71, 1.0, if (s.tTail) 0.04 else 0.16, 0.97)
        }
    }

    // ------------------------------------------------------------------ engines
    private fun buildEngine(left: Boolean) {
        val side = if (left) 1.0 else -1.0
        val sn = if (left) "L" else "R"
        val z = s.engZ
        val c = chordAt(z)
        val lowY = wingYAt(z) + Airfoil.lower(0.15, tAt(z), camber) * c
        val D = s.engDia
        val L = s.engLen
        val x0 = wingLE(z) - s.engAhead
        val prop = s.engine == Engine.PW127
        val cy = if (prop) wingYAt(z) - s.engDrop else lowY - s.engDrop - D / 2
        val ctr = V(x0, cy, side * z)
        val axis = V(1.0, 0.0, 0.0)
        val seg = 48 * d
        val squash = if (s.engine == Engine.CFM56_7) 0.80 else 1.0
        if (!prop) {
            // cowl: outer skin round the lip into the intake, closed solid
            val outer = listOf(0.0 to 0.445, 0.02 to 0.475, 0.08 to 0.495, 0.25 to 0.50, 0.5 to 0.49, 0.72 to 0.455, 0.88 to 0.40, 1.0 to 0.35)
            val inner = listOf(0.98 to 0.335, 0.7 to 0.37, 0.35 to 0.405, 0.12 to 0.412, 0.03 to 0.42, 0.0 to 0.43)
            val prof = (outer + inner).map { (a, r) -> (a * L) to (r * D) }
            part("Engine nacelle $sn", white).revolve(ctr, axis, prof, seg, squash)
            part("Intake lip $sn", metal).revolve(ctr, axis, listOf(-0.02 * L to 0.44 * D, 0.012 * L to 0.452 * D, 0.012 * L to 0.425 * D, -0.005 * L to 0.425 * D), seg, squash)
            // fan: hub + blades
            val fanX = 0.24 * L
            val fan = part("Engine fan $sn", darkMetal)
            fan.revolveOpen(ctr + V(fanX - 0.05, 0.0, 0.0), axis, listOf(0.0 to 0.13 * D, 0.12 to 0.16 * D), 24)
            val blades = if (s.engine == Engine.LEAP1A || s.engine == Engine.LEAP1B) 18 else 24
            for (k in 0 until blades) blade(fan, ctr + V(fanX, 0.0, 0.0), axis, 2 * PI * k / blades, 0.14 * D, 0.40 * D, 0.16 * D, 0.11 * D, 0.95, 0.40, 0.012 * D)
            part("Spinner $sn", darkMetal).revolveOpen(ctr + V(fanX - 0.30 * D, 0.0, 0.0), axis, (0..10).map { k -> val u = k / 10.0; (u * 0.30 * D) to (0.145 * D * kotlin.math.sqrt(u)) }, 24)
            // core nozzle and plug
            val ex = part("Engine exhaust $sn", metal)
            ex.revolve(ctr, axis, listOf(0.97 * L to 0.30 * D, 1.16 * L to 0.235 * D, 1.16 * L to 0.215 * D, 0.97 * L to 0.27 * D), seg)
            ex.revolveOpen(ctr + V(1.0 * L, 0.0, 0.0), axis, (0..8).map { k -> val u = k / 8.0; (u * 0.38 * L) to (0.19 * D * (1 - u).pow(0.8) + 0.01) }, 24)
            // pylon
            val py = part("Pylon $sn", white)
            val yTop = wingYAt(z) + 0.05
            val yBot = cy + D * 0.40 * (if (squash < 1) 1.0 else 1.0)
            val rings = (0..8).map { i ->
                val y = lerp(yBot, yTop, i / 8.0)
                val xs = lerp(x0 + 0.35 * L, wingLE(z) + 0.05 * c, i / 8.0)
                val ch = lerp(L * 0.85, c * 0.78, i / 8.0)
                Airfoil.section(0.0, 1.0, 18, 0.16, 0.0).map { (x, t) -> V(xs + x * ch, y, side * z + t * ch) }
            }
            py.loft(rings)
        } else {
            // turboprop nacelle hanging under the wing, propeller in front
            val prof = listOf(0.0 to 0.30, 0.08 to 0.42, 0.25 to 0.50, 0.55 to 0.50, 0.8 to 0.40, 1.0 to 0.22, 1.12 to 0.06)
                .map { (a, r) -> (a * L) to (r * D) }
            part("Engine nacelle $sn", white).revolveOpen(ctr, axis, prof, seg)
            val pr = part("Propeller $sn", darkMetal)
            pr.revolveOpen(ctr + V(-0.62, 0.0, 0.0), axis, (0..10).map { k -> val u = k / 10.0; (u * 0.65) to (0.30 * D * kotlin.math.sqrt(u)) }, 28)
            for (k in 0 until 6) blade(pr, ctr + V(-0.25, 0.0, 0.0), axis, 2 * PI * k / 6 + 0.3, 0.25, 1.965, 0.30, 0.14, 0.9, 0.15, 0.03)
            part("Engine exhaust $sn", darkMetal).cylinder(ctr + V(0.55 * L, -0.18 * D, side * 0.32 * D), ctr + V(0.75 * L, -0.24 * D, side * 0.40 * D), 0.11, 16)
        }
    }

    /** Twisted fan / propeller blade as a thin lofted solid. */
    private fun blade(p: Part, center: V, axis: V, phi: Double, r0: Double, r1: Double, ch0: Double, ch1: Double, tw0: Double, tw1: Double, th: Double) {
        val ax = axis.norm()
        val u = V(0.0, 1.0, 0.0); val w = ax.cross(u).norm()
        val dir = u * cos(phi) + w * sin(phi)
        val tdir = ax.cross(dir).norm()
        val rings = (0..8).map { i ->
            val f = i / 8.0
            val r = lerp(r0, r1, f)
            val ch = lerp(ch0, ch1, f)
            val tw = lerp(tw0, tw1, f)
            val cv = tdir * cos(tw) + ax * sin(tw)
            val tv = ax * cos(tw) - tdir * sin(tw)
            (0 until 10).map { k ->
                val a = 2 * PI * k / 10
                center + dir * r + cv * (cos(a) * ch / 2) + tv * (sin(a) * th / 2)
            }
        }
        p.loft(rings)
    }

    // ------------------------------------------------------------------ landing gear
    private fun wheel(p: Part, hub: Part, c: V, r: Double, width: Double) {
        val prof = (0 until 16).map { k ->
            val a = 2 * PI * k / 16
            (width / 2 * cos(a)) to (r - width * 0.55 + width * 0.55 * sin(a)).coerceAtLeast(r * 0.45)
        }
        p.revolve(c, V(0.0, 0.0, 1.0), prof, 32 * d)
        hub.cylinder(c - V(0.0, 0.0, width * 0.42), c + V(0.0, 0.0, width * 0.42), r * 0.52, 24)
    }

    private fun buildGear() {
        if (!o.gearDown) return
        val g = s.groundY
        for (left in listOf(true, false)) {
            val side = if (left) 1.0 else -1.0
            val sn = if (left) "L" else "R"
            val z = s.track / 2 * side
            val x = s.mainGearX
            val rw = s.mainWheelD / 2
            val axleY = g + rw
            val highWing = s.wingY > 0
            val topY = if (highWing) -s.height / 2 + 0.35 else wingYAt(abs(z)) + Airfoil.lower(0.6, tAt(abs(z)), camber) * chordAt(abs(z))
            if (highWing) {
                val sp = part("Landing gear fairing $sn", white)
                sp.revolveOpen(V(x - 2.4, -s.height / 2 + 0.25, z * 0.80), V(1.0, 0.0, 0.0),
                    (0..12).map { k -> val u = k / 12.0; (u * 4.6) to (0.08 + 0.55 * sin(PI * u.pow(0.8)).pow(0.7)) }, 24)
            }
            val leg = part("Main gear $sn", gearWhite)
            leg.cylinder(V(x, topY, z), V(x, axleY + 0.18, z), 0.13, 20)
            leg.cylinder(V(x, axleY + 0.5, z), V(x, axleY - 0.05, z), 0.09, 16)
            leg.cylinder(V(x, axleY, z - 0.62), V(x, axleY, z + 0.62), 0.07, 14)
            leg.cylinder(V(x, (topY + axleY) / 2 + 0.3, z), V(x - 0.2, -s.height / 2 + 0.45, z * 0.45), 0.055, 12)
            leg.box(V(x + 0.16, axleY + 0.45, z), V(0.12, 0.0, 0.0), V(0.0, 0.18, 0.0), V(0.0, 0.0, 0.03))
            val wh = part("Main wheels $sn", tyre)
            val hub = part("Main wheel hubs $sn", metal)
            wheel(wh, hub, V(x, axleY, z - 0.47), rw, 0.40)
            wheel(wh, hub, V(x, axleY, z + 0.47), rw, 0.40)
            if (!highWing) {
                val door = part("Main gear door $sn", white)
                door.box(V(x - 0.05, (topY + axleY) / 2 + 0.25, z + side * 0.24), V(0.55, 0.0, 0.0), V(0.0, (topY - axleY) / 2 * 0.62, 0.0), V(0.0, 0.0, 0.025))
            }
        }
        // nose gear
        val x = s.noseGearX
        val rn = s.noseWheelD / 2
        val axleY = g + rn
        val bot = fus(x).second
        val nose = part("Nose gear", gearWhite)
        nose.cylinder(V(x, bot + 0.2, 0.0), V(x, axleY + 0.12, 0.0), 0.09, 18)
        nose.cylinder(V(x, axleY, -0.32), V(x, axleY, 0.32), 0.05, 12)
        nose.cylinder(V(x, bot + 0.1, 0.0), V(x + 0.7, (bot + axleY) / 2, 0.0), 0.04, 10)
        val wh = part("Nose wheels", tyre)
        val hub = part("Nose wheel hubs", metal)
        wheel(wh, hub, V(x, axleY, -0.23), rn, 0.24)
        wheel(wh, hub, V(x, axleY, 0.23), rn, 0.24)
        for (left in listOf(true, false)) {
            val sd = if (left) 1.0 else -1.0
            part("Nose gear door ${if (left) "L" else "R"}", white)
                .box(V(x - 0.1, bot - 0.45, sd * 0.36), V(0.75, 0.0, 0.0), V(0.0, 0.42, 0.0), V(0.0, 0.0, 0.02))
        }
    }

    // ------------------------------------------------------------------ small details
    private fun buildDetails() {
        val top = { x: Double -> fus(x).first }
        val bot = { x: Double -> fus(x).second }
        fun antenna(name: String, x: Double, up: Boolean, h: Double) {
            val p = part(name, white)
            val y0 = if (up) top(x) else bot(x)
            val sg = if (up) 1.0 else -1.0
            val rings = (0..4).map { i ->
                val f = i / 4.0
                val ch = lerp(0.42, 0.14, f)
                Airfoil.section(0.0, 1.0, 12, 0.14, 0.0).map { (u, t) -> V(x + f * 0.25 + u * ch, y0 - sg * 0.02 + sg * f * h, t * ch) }
            }
            p.loft(rings)
        }
        antenna("Antenna top 1", s.length * 0.33, true, 0.32)
        antenna("Antenna top 2", s.length * 0.55, true, 0.22)
        antenna("Antenna bottom 1", s.length * 0.22, false, 0.25)
        antenna("Antenna bottom 2", s.length * 0.62, false, 0.18)
        // beacons
        part("Beacon top", Rgb(0.9, 0.1, 0.1)).sphere(V(s.length * 0.45, top(s.length * 0.45) + 0.03, 0.0), 0.09, 10)
        part("Beacon bottom", Rgb(0.9, 0.1, 0.1)).sphere(V(s.length * 0.40, bot(s.length * 0.40) - 0.03, 0.0), 0.09, 10)
        // pitot probes and angle-of-attack vanes near the nose
        for (left in listOf(true, false)) {
            val sd = if (left) 1.0 else -1.0
            val x = s.noseLen * 0.38
            val p0 = fusPoint(x, 0.15).let { V(it.x, it.y, it.z * sd) }
            val p = part("Pitot probe ${if (left) "L" else "R"}", metal)
            p.cylinder(p0, p0 + V(0.05, 0.0, sd * 0.16), 0.018, 8)
            p.cylinder(p0 + V(0.05, 0.0, sd * 0.16), p0 + V(-0.12, 0.0, sd * 0.16), 0.012, 8)
        }
        // tail logo light / strobe
        part("Tail strobe", Rgb(0.95, 0.95, 0.95)).sphere(V(s.length + 0.05, fus(s.length - 0.05).first + 0.02, 0.0), 0.06, 8)
    }

    // ------------------------------------------------------------------ output
    fun build(file: File, progress: (Float, String) -> Unit): Long {
        val steps = listOf<Pair<String, () -> Unit>>(
            "Fuselage" to { buildFuselage() },
            "Doors and windows" to { buildOpenings() },
            "Left wing" to { buildWing(true) },
            "Right wing" to { buildWing(false) },
            "Tail" to { buildTail() },
            "Left engine" to { buildEngine(true) },
            "Right engine" to { buildEngine(false) },
            "Landing gear" to { buildGear() },
            "Details" to { buildDetails() },
        )
        ObjWriter(file).use { w ->
            w.line("# ${s.name} — generated by Vertexa Pro at real size (metres). x: nose → tail, y: up, z: left wing")
            steps.forEachIndexed { i, (label, fn) ->
                progress(i.toFloat() / (steps.size + 1), label)
                parts.clear()
                fn()
                parts.forEach { w.part(it) }
                parts.clear()
            }
            progress(1f, "Done")
            return w.triangles
        }
    }
}
