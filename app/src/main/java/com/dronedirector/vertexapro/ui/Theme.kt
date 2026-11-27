package com.dronedirector.vertexapro.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val label: String) { DARK("Dark"), GRAY("Gray"), LIGHT("Light") }

data class Palette(
    val mode: ThemeMode,
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val glass: Color,
    val glassStrong: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accent2: Color,
    val accent3: Color,
    val auroraA: Color,
    val auroraB: Color,
    val danger: Color,
    // values for the native renderer
    val viewTop: Color,
    val viewBottom: Color,
    val grid: Color,
    val wire: Color,
    val clay: Color,
) {
    val accentBrush get() = Brush.linearGradient(listOf(accent, accent2, accent3))
    val isLight get() = mode == ThemeMode.LIGHT

    fun nativeTheme(): FloatArray {
        fun c3(c: Color) = floatArrayOf(c.red, c.green, c.blue)
        fun c4(c: Color) = floatArrayOf(c.red, c.green, c.blue, c.alpha)
        return c3(viewTop) + c3(viewBottom) + c4(grid) + c4(wire) + c3(clay)
    }
}

val Cyan = Color(0xFF22D3EE)
val Violet = Color(0xFF8B5CF6)
val Pink = Color(0xFFEC4899)

val DarkPalette = Palette(
    mode = ThemeMode.DARK,
    bg = Color(0xFF06070B), bg2 = Color(0xFF0C0E15), surface = Color(0xFF12141C),
    glass = Color(0x14FFFFFF), glassStrong = Color(0xCC12141C), border = Color(0x1FFFFFFF),
    text = Color(0xFFEEF1F7), muted = Color(0xFF8A91A5),
    accent = Cyan, accent2 = Violet, accent3 = Pink,
    auroraA = Color(0x5522D3EE), auroraB = Color(0x558B5CF6), danger = Color(0xFFFF5C7A),
    viewTop = Color(0xFF161A26), viewBottom = Color(0xFF05060A),
    grid = Color(0x6680A8FF), wire = Color(0xFF4CE0FF), clay = Color(0xFFC9CCD4),
)

val GrayPalette = Palette(
    mode = ThemeMode.GRAY,
    bg = Color(0xFF34373D), bg2 = Color(0xFF3D4047), surface = Color(0xFF45484F),
    glass = Color(0x1AFFFFFF), glassStrong = Color(0xD93D4047), border = Color(0x26FFFFFF),
    text = Color(0xFFF2F3F5), muted = Color(0xFFB7BBC4),
    accent = Color(0xFF5EE7FF), accent2 = Color(0xFFA78BFA), accent3 = Color(0xFFF472B6),
    auroraA = Color(0x3355E0FF), auroraB = Color(0x33A78BFA), danger = Color(0xFFFF7A90),
    viewTop = Color(0xFF6A6E77), viewBottom = Color(0xFF33363C),
    grid = Color(0x66E6EAF2), wire = Color(0xFF8EF0FF), clay = Color(0xFFD5D7DC),
)

val LightPalette = Palette(
    mode = ThemeMode.LIGHT,
    bg = Color(0xFFF3F5F9), bg2 = Color(0xFFE9EDF4), surface = Color(0xFFFFFFFF),
    glass = Color(0xB3FFFFFF), glassStrong = Color(0xF2FFFFFF), border = Color(0x1A0E1220),
    text = Color(0xFF0E1220), muted = Color(0xFF5D6475),
    accent = Color(0xFF0891B2), accent2 = Color(0xFF7C3AED), accent3 = Color(0xFFDB2777),
    auroraA = Color(0x3322D3EE), auroraB = Color(0x338B5CF6), danger = Color(0xFFE11D48),
    viewTop = Color(0xFFFFFFFF), viewBottom = Color(0xFFD6DCE6),
    grid = Color(0x592B3A55), wire = Color(0xFF2563EB), clay = Color(0xFFB4B9C3),
)

fun paletteFor(mode: ThemeMode) = when (mode) {
    ThemeMode.DARK -> DarkPalette
    ThemeMode.GRAY -> GrayPalette
    ThemeMode.LIGHT -> LightPalette
}

val LocalPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun VertexaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val p = paletteFor(mode)
    val scheme = if (p.isLight) lightColorScheme(
        primary = p.accent, secondary = p.accent2, tertiary = p.accent3,
        background = p.bg, surface = p.surface, onBackground = p.text, onSurface = p.text,
        surfaceVariant = p.bg2, onSurfaceVariant = p.muted, error = p.danger,
    ) else darkColorScheme(
        primary = p.accent, secondary = p.accent2, tertiary = p.accent3,
        background = p.bg, surface = p.surface, onBackground = p.text, onSurface = p.text,
        surfaceVariant = p.bg2, onSurfaceVariant = p.muted, error = p.danger,
        surfaceContainerLow = p.surface, surfaceContainer = p.surface,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
