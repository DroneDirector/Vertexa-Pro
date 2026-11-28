package com.dronedirector.vertexapro.generator

/**
 * Real-world dimensions (metres) for the aircraft the generator can build.
 * Axes: x from the nose (0) to the tail, y up, z to the left wing. Fuselage centreline at y = 0.
 * Spanwise "eta" is a share of the half-span from the centreline.
 */
data class SurfaceSpec(val name: String, val eta0: Double, val eta1: Double, val c0: Double, val c1: Double, val pieces: Int = 1)

data class DoorSpec(val name: String, val x: Double, val w: Double, val h: Double, val yCenter: Double, val left: Boolean, val right: Boolean, val cargo: Boolean = false)

enum class Tip { FENCE, SHARKLET, WINGLET, SCIMITAR, PLAIN }
enum class Engine { CFM56, LEAP1A, CFM56_7, LEAP1B, PW127 }

data class AircraftSpec(
    val name: String,
    val length: Double, val width: Double, val height: Double,
    val noseLen: Double, val tailLen: Double, val noseDroop: Double,
    // wing
    val semiSpan: Double, val rootChord: Double, val kinkEta: Double, val kinkChord: Double, val tipChord: Double,
    val sweepLE: Double, val dihedral: Double, val wingX: Double, val wingY: Double, val tRoot: Double, val tTip: Double,
    val tip: Tip, val tipHeight: Double,
    val slats: List<SurfaceSpec>, val flaps: List<SurfaceSpec>, val ailerons: List<SurfaceSpec>, val spoilers: List<SurfaceSpec>,
    // tail
    val stabSemi: Double, val stabRoot: Double, val stabTip: Double, val stabSweep: Double, val stabX: Double, val stabY: Double, val stabDihedral: Double, val tTail: Boolean,
    val finHeight: Double, val finRoot: Double, val finTip: Double, val finSweep: Double, val finX: Double,
    // engines
    val engine: Engine, val engZ: Double, val engLen: Double, val engDia: Double, val engAhead: Double, val engDrop: Double,
    // gear (ground = groundY)
    val groundY: Double, val noseGearX: Double, val mainGearX: Double, val track: Double, val mainWheelD: Double, val noseWheelD: Double,
    // openings
    val doors: List<DoorSpec>, val windowFrom: Double, val windowTo: Double, val windowPitch: Double, val windowY: Double,
    val defaultTail: Rgb,
)

object Specs {
    private val airbusSlats = listOf(SurfaceSpec("Slat", 0.13, 0.27, 0.0, 0.13), SurfaceSpec("Slat", 0.33, 0.96, 0.0, 0.13, pieces = 4))
    private val airbusFlaps = listOf(SurfaceSpec("Flap inboard", 0.12, 0.36, 0.72, 1.0), SurfaceSpec("Flap outboard", 0.38, 0.77, 0.74, 1.0))
    private val airbusAil = listOf(SurfaceSpec("Aileron", 0.78, 0.94, 0.76, 1.0))
    private val airbusSpoil = listOf(SurfaceSpec("Spoiler", 0.30, 0.76, 0.56, 0.715, pieces = 5))

    private fun a320Family(name: String, length: Double, stretchFwd: Double, neo: Boolean, doors: List<DoorSpec>, wheelbase: Double): AircraftSpec {
        val wingX = 12.75 + stretchFwd
        val tailShift = length - 37.57
        return AircraftSpec(
            name, length, 3.95, 4.14, 6.4, 10.4, -0.55,
            if (neo) 17.05 else 17.05, 7.1, 0.37, 4.55, 1.55, 27.0, 5.1, wingX, -1.25, 0.150, 0.108,
            if (neo || name.contains("Sharklet")) Tip.SHARKLET else Tip.FENCE, if (neo) 2.43 else 0.9,
            airbusSlats, airbusFlaps, airbusAil, airbusSpoil,
            6.22, 3.55, 1.30, 32.0, 31.15 + tailShift, 0.55, 6.0, false,
            5.75, 6.1, 2.15, 40.0, 27.6 + tailShift,
            if (neo) Engine.LEAP1A else Engine.CFM56, 5.75, if (neo) 5.05 else 4.45, if (neo) 2.50 else 2.37, if (neo) 2.85 else 2.55, 0.15,
            -4.10, 5.07, 5.07 + wheelbase, 7.59, 1.17, 0.76,
            doors, 6.35, length - 7.2, 0.533, 0.42,
            Rgb(0.10, 0.24, 0.52),
        )
    }

    private fun d(name: String, x: Double, both: Boolean = true) = DoorSpec(name, x, 0.81, 1.85, 0.30, true, both)
    private fun ow(name: String, x: Double) = DoorSpec(name, x, 0.51, 1.01, 0.42, true, true)
    private fun cargo(name: String, x: Double, w: Double = 1.82, h: Double = 1.24) = DoorSpec(name, x, w, h, -1.15, false, true, cargo = true)

    val a319 = a320Family("Airbus A319", 33.84, -1.87, false,
        listOf(d("Door 1", 4.6), ow("Overwing exit", 14.05), d("Door 4", 28.0), cargo("Cargo door fwd", 8.4), cargo("Cargo door aft", 21.6)), 11.04)
    val a320 = a320Family("Airbus A320", 37.57, 0.0, false,
        listOf(d("Door 1", 4.6), ow("Overwing exit 1", 15.25), ow("Overwing exit 2", 15.95), d("Door 4", 31.6), cargo("Cargo door fwd", 8.9), cargo("Cargo door aft", 24.4), cargo("Bulk cargo door", 27.3, 0.95, 0.95)), 12.64)
    val a320neo = a320Family("Airbus A320neo", 37.57, 0.0, true,
        a320.doors, 12.64)
    val a321 = a320Family("Airbus A321", 44.51, 4.27, false,
        listOf(d("Door 1", 4.6), d("Door 2", 12.35), d("Door 3", 28.1), d("Door 4", 38.4), cargo("Cargo door fwd", 9.2), cargo("Cargo door aft", 30.6)), 16.9)
    val a321neo = a320Family("Airbus A321neo", 44.51, 4.27, true, a321.doors, 16.9)

    private fun b737(name: String, max: Boolean) = AircraftSpec(
        name, if (max) 39.52 else 39.47, 3.76, 4.01, 5.9, 10.8, -0.6,
        17.16, 7.4, 0.30, 4.65, 1.25, 27.0, 6.0, 15.25, -1.15, 0.150, 0.110,
        if (max) Tip.SCIMITAR else Tip.WINGLET, 2.49,
        listOf(SurfaceSpec("Slat", 0.32, 0.96, 0.0, 0.13, pieces = 4)),
        listOf(SurfaceSpec("Flap inboard", 0.12, 0.36, 0.72, 1.0), SurfaceSpec("Flap outboard", 0.39, 0.72, 0.74, 1.0)),
        listOf(SurfaceSpec("Aileron", 0.73, 0.92, 0.78, 1.0)),
        listOf(SurfaceSpec("Spoiler", 0.14, 0.70, 0.57, 0.715, pieces = 6)),
        7.17, 4.0, 1.25, 32.0, 32.35, 0.55, 7.0, false,
        7.4, 6.6, 1.9, 38.0, 29.0,
        if (max) Engine.LEAP1B else Engine.CFM56_7, 4.90, if (max) 4.9 else 4.4, if (max) 2.15 else 2.02, if (max) 2.9 else 2.4, 0.05,
        -3.28, 4.35, 4.35 + 15.6, 5.72, 1.12, 0.70,
        listOf(DoorSpec("Door 1", 3.9, 0.86, 1.83, 0.3, true, true), ow("Overwing exit 1", 16.45), ow("Overwing exit 2", 17.35),
            DoorSpec("Door 2", 34.6, 0.76, 1.83, 0.3, true, true), cargo("Cargo door fwd", 8.6, 1.22, 1.30), cargo("Cargo door aft", 26.3, 1.22, 1.22)),
        5.6, 33.6, 0.508, 0.40,
        Rgb(0.07, 0.17, 0.42),
    )
    val b737_800 = b737("Boeing 737-800", false)
    val b737max = b737("Boeing 737 MAX 8", true)

    val atr72 = AircraftSpec(
        "ATR 72-600", 27.17, 2.57, 2.62, 3.5, 8.6, -0.25,
        13.52, 2.57, 0.0, 2.57, 1.41, 3.5, 2.5, 10.85, 1.62, 0.18, 0.15,
        Tip.PLAIN, 0.0,
        emptyList(),
        listOf(SurfaceSpec("Flap inboard", 0.10, 0.33, 0.70, 1.0), SurfaceSpec("Flap outboard", 0.36, 0.62, 0.71, 1.0)),
        listOf(SurfaceSpec("Aileron", 0.64, 0.93, 0.74, 1.0)),
        listOf(SurfaceSpec("Spoiler", 0.45, 0.62, 0.55, 0.68)),
        3.65, 1.75, 1.05, 10.0, 25.1, 4.55, 0.0, true,
        4.55, 3.9, 1.95, 32.0, 21.85,
        Engine.PW127, 4.05, 5.2, 1.05, 1.9, 0.35,
        -1.95, 2.1, 2.1 + 10.77, 4.10, 0.84, 0.45,
        listOf(DoorSpec("Passenger door", 20.6, 0.72, 1.75, 0.10, true, false), DoorSpec("Service door", 20.6, 0.69, 1.38, 0.15, false, true),
            DoorSpec("Cargo door", 3.6, 1.27, 1.53, 0.05, true, false), DoorSpec("Emergency exit", 4.6, 0.51, 0.97, 0.25, false, true)),
        5.4, 19.8, 0.47, 0.25,
        Rgb(0.10, 0.30, 0.55),
    )

    val all = listOf(a319, a320, a320neo, a321, a321neo, b737_800, b737max, atr72)

    /** Picks the aircraft a sentence asks for ("make me an Airbus A320 neo"), or null. */
    fun parse(text: String): AircraftSpec? {
        val t = text.lowercase().replace("-", " ")
        val neo = "neo" in t
        return when {
            "atr" in t || "72" in t && "737" !in t -> atr72
            "max" in t || "737 8" in t && "max" in t -> b737max
            "737" in t || "boeing" in t -> b737_800
            "321" in t -> if (neo) a321neo else a321
            "319" in t -> a319
            "320" in t || "airbus" in t -> if (neo) a320neo else a320
            else -> null
        }
    }
}
