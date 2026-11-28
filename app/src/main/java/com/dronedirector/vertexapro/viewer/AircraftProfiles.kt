package com.dronedirector.vertexapro.viewer

/**
 * Built-in aircraft: real dimensions and where the control surfaces sit on the wing.
 * Spanwise positions are shares of the half-span from the aircraft's centreline,
 * chord is the share of the local wing chord measured forward from the trailing edge.
 * Positions are close approximations of the real layouts; the outlines can be fine-tuned after.
 */
data class Surface(val name: String, val etaStart: Float, val etaEnd: Float, val chord: Float, val aileron: Boolean = false)

data class AircraftProfile(
    val name: String,
    val lengthM: Double,
    /** Wingspan variants in metres (e.g. with and without sharklets / winglets). */
    val spansM: List<Pair<String, Double>>,
    val surfaces: List<Surface>,
    val generic: Boolean = false,
)

object AircraftProfiles {
    private val airbusNarrow = listOf(
        Surface("Flap inboard", 0.11f, 0.36f, 0.27f),
        Surface("Flap outboard", 0.38f, 0.77f, 0.25f),
        Surface("Aileron", 0.78f, 0.94f, 0.24f, aileron = true),
    )
    private val boeing737 = listOf(
        Surface("Flap inboard", 0.12f, 0.37f, 0.28f),
        Surface("Flap outboard", 0.40f, 0.72f, 0.26f),
        Surface("Aileron", 0.73f, 0.92f, 0.22f, aileron = true),
    )
    private val atr72 = listOf(
        Surface("Flap inboard", 0.10f, 0.33f, 0.30f),
        Surface("Flap outboard", 0.35f, 0.62f, 0.28f),
        Surface("Aileron", 0.64f, 0.93f, 0.27f, aileron = true),
    )

    val all = listOf(
        AircraftProfile("Airbus A320ceo", 37.57, listOf("Wingtip fences" to 34.10, "Sharklets" to 35.80), airbusNarrow),
        AircraftProfile("Airbus A320neo", 37.57, listOf("Sharklets" to 35.80), airbusNarrow),
        AircraftProfile("Airbus A321ceo", 44.51, listOf("Wingtip fences" to 34.10, "Sharklets" to 35.80), airbusNarrow),
        AircraftProfile("Airbus A321neo", 44.51, listOf("Sharklets" to 35.80), airbusNarrow),
        AircraftProfile("Boeing 737-800", 39.47, listOf("No winglets" to 34.32, "Winglets" to 35.79), boeing737),
        AircraftProfile("Boeing 737 MAX 8", 39.52, listOf("Split-tip winglets" to 35.92), boeing737),
        AircraftProfile("ATR 72-600", 27.17, listOf("Standard" to 27.05), atr72),
        AircraftProfile(
            "Other / unknown", 0.0, emptyList(),
            listOf(
                Surface("Flap inboard", 0.12f, 0.37f, 0.28f),
                Surface("Flap outboard", 0.39f, 0.74f, 0.26f),
                Surface("Aileron", 0.76f, 0.93f, 0.23f, aileron = true),
            ),
            generic = true,
        ),
    )
}
